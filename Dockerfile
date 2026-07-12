# syntax=docker/dockerfile:1
#
# cloud-itonami-isic-6201 (marketing-automation governed actor) container
# image. Runs the real `marketing.http` service (`clojure -M:serve`) — see
# `docs/api.md` for the endpoint contract and `README.md`'s "Running via
# Docker" section for the exact verified commands.
#
# This repo has no compiled-artifact (uberjar) build step today — it runs
# from source via the Clojure CLI, same as `clojure -M:serve` documented in
# README.md/docs/api.md. So both stages need `java` + the `clojure` CLI +
# the source tree; the builder stage's only job is to (a) fetch this repo's
# two LOCAL sibling deps (`kotoba-lang/crm`, `kotoba-lang/langgraph` — this
# repo's deps.edn `:local/root "../../kotoba-lang/..."`, matching the real
# superproject's `orgs/<org>/<repo>` layout one level up) so the relative
# paths resolve, and (b) pre-fetch every jar/git dep (`clojure -P`,
# including `kotoba-lang/langchain`, which langgraph's own deps.edn pulls
# in via a pinned `:git/sha` — NOT this actor's `:dev` alias, which is a
# workspace-local-dev-only override; production `-M:serve` never needs
# `:dev`) so the runtime image never needs network access or a JDK.
#
# JDK version: Temurin 21 — matches this repo's own `constant-time-string=`
# docstring in src/marketing/http.clj ("this fleet's JDK (Temurin 21,
# confirmed via `java -version` at implementation time)"), reconfirmed
# locally when this Dockerfile was written (`java -version` ->
# `Temurin-21.0.1+12`).

# ───────────────────────── builder ─────────────────────────
FROM eclipse-temurin:21-jdk-jammy AS builder

RUN apt-get update \
    && apt-get install -y --no-install-recommends git curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Official Clojure CLI installer (linux-install.sh) — installs into
# /usr/local/lib/clojure + /usr/local/bin/{clojure,clj}. Only `clojure`
# (not the rlwrap-wrapped `clj` REPL script) is used anywhere in this
# image, so rlwrap is never installed.
RUN curl -Ls https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh \
      -o /tmp/linux-install.sh \
    && chmod +x /tmp/linux-install.sh \
    && /tmp/linux-install.sh \
    && rm -f /tmp/linux-install.sh

# Reconstruct the same relative layout this repo's deps.edn expects
# (`:local/root "../../kotoba-lang/crm"` / `"../../kotoba-lang/langgraph"`
# resolved from /app/orgs/cloud-itonami/cloud-itonami-isic-6201 — "../../"
# lands at /app/orgs, NOT /app), i.e. the real superproject's flat
# `orgs/<org>/<repo>` nesting:
#   /app/orgs/cloud-itonami/cloud-itonami-isic-6201   (this repo)
#   /app/orgs/kotoba-lang/crm
#   /app/orgs/kotoba-lang/langgraph
# Both sibling repos are public GitHub repos (kotoba-lang org) — cloned
# fresh at build time rather than assumed present in the build context.
WORKDIR /app/orgs
RUN git clone --depth 1 https://github.com/kotoba-lang/crm.git kotoba-lang/crm \
    && git clone --depth 1 https://github.com/kotoba-lang/langgraph.git kotoba-lang/langgraph

WORKDIR /app/orgs/cloud-itonami/cloud-itonami-isic-6201
COPY . .

# Pre-fetch every dep `clojure -M:serve` needs (base deps.edn: http-kit,
# ring-core, data.json, crm + langgraph via local/root, langchain
# transitively via langgraph's pinned :git/sha) into this stage's
# ~/.m2 + ~/.gitlibs + ~/.clojure/.cpcache, which are copied verbatim
# into the runtime stage below so it starts with zero network access.
RUN clojure -P -M:serve

# ───────────────────────── runtime ─────────────────────────
FROM eclipse-temurin:21-jre-jammy AS runtime

# curl: used only by HEALTHCHECK below to call GET /health.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 isic6201 \
    && useradd --system --uid 10001 --gid isic6201 --home-dir /home/isic6201 \
               --shell /usr/sbin/nologin isic6201 \
    && mkdir -p /home/isic6201 /data \
    && chown -R isic6201:isic6201 /home/isic6201 /data

# Clojure CLI binaries + support libs only (no curl/git/JDK compiler
# needed at runtime) — copied from the builder stage instead of
# reinstalled, so the runtime image never runs the installer or needs
# network access to github.com.
COPY --from=builder /usr/local/lib/clojure /usr/local/lib/clojure
COPY --from=builder /usr/local/bin/clojure /usr/local/bin/clojure

# Pre-warmed dependency caches (jars + git deps + resolved classpath),
# so `clojure -M:serve` starts fully offline.
COPY --from=builder --chown=isic6201:isic6201 /root/.m2 /home/isic6201/.m2
COPY --from=builder --chown=isic6201:isic6201 /root/.gitlibs /home/isic6201/.gitlibs
COPY --from=builder --chown=isic6201:isic6201 \
     /app/orgs/cloud-itonami/cloud-itonami-isic-6201/.cpcache \
     /app/orgs/cloud-itonami/cloud-itonami-isic-6201/.cpcache

# Source tree (this repo runs from source, not an uberjar — see header
# comment). Same relative layout as the builder stage.
COPY --from=builder --chown=isic6201:isic6201 /app/orgs/kotoba-lang /app/orgs/kotoba-lang
COPY --from=builder --chown=isic6201:isic6201 \
     /app/orgs/cloud-itonami/cloud-itonami-isic-6201 \
     /app/orgs/cloud-itonami/cloud-itonami-isic-6201

ENV HOME=/home/isic6201
WORKDIR /app/orgs/cloud-itonami/cloud-itonami-isic-6201
USER isic6201:isic6201

# Runtime configuration — ALL read from the container environment at
# start, NEVER baked into the image (see marketing.http/-main's
# docstring):
#   ISIC6201_API_TOKEN      REQUIRED. No default; -main exits 1 without
#                            it (fail-closed, see docs/api.md's Auth
#                            section) -- intentionally NOT set here.
#   ISIC6201_HTTP_PORT       optional, default 8080.
#   ISIC6201_STORE_FILE      optional but strongly recommended -- e.g.
#                            /data/db.edn (bind-mount /data, see
#                            README.md's "Running via Docker" section).
#                            Unset = ephemeral in-memory store, lost on
#                            restart (a stderr WARNING is printed).
#   ISIC6201_MODEL_API_KEY / ISIC6201_MODEL_PROVIDER / ISIC6201_MODEL /
#   ISIC6201_MODEL_URL       optional -- real-model MarketingOps-LLM
#                            advisor instead of the sealed mock, see
#                            docs/api.md's "Real-model advisor" section.
ENV ISIC6201_HTTP_PORT=8080

EXPOSE 8080

VOLUME ["/data"]

HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD curl -fs "http://127.0.0.1:${ISIC6201_HTTP_PORT}/health" || exit 1

ENTRYPOINT ["clojure", "-M:serve"]
