# HTTP API (`src/marketing/http.clj`)

This is the first HTTP service layer over the `cloud-itonami-isic-6201`
actor. It is a **thin adapter**: it does not reimplement any governance
logic. Every governed decision is produced by the exact same
`marketing.operation`/`marketing.policy`/`marketing.dashboard` code the
library entry points (`clojure -M:dev:run`, the test suite) already use.
It mirrors `cloud-itonami-isic-5820`'s `src/crm/http.clj` (same
http-kit/ring-core/data.json dependency choices, same fail-closed
bearer-token auth design, same namespaced-keyword JSON handling
approach).

## Honest scope (read this first)

This is a **real, network-callable service** — not a mock, not a demo
stub. It is also **not yet production-hardened**:

- **Single process, single tenant.** One JVM process, one in-memory or
  Datomic-backed `Store` injected at startup. There is no multi-tenant
  isolation — every caller with a valid token shares the same store.
- **No TLS termination.** This binds plain HTTP. Put a reverse proxy
  (nginx, Caddy, Cloudflare, an ALB, …) in front for HTTPS/TLS — that is
  explicitly the reverse proxy's job, not this process's.
- **No rate limiting.** Any caller with a valid bearer token can call
  `/send`, `/advance-stage`, `/update-score`, or `/dashboard` as fast as
  they like.
- **No request logging/observability** beyond whatever the operator adds
  externally (this file adds none beyond what `httpkit`/the JVM already
  emit on stdout/stderr).
- **`clojure -M:serve` (`marketing.http/-main`) is disk-durable only if
  you set `$ISIC6201_STORE_FILE`.** If unset, it falls back to a fresh,
  in-memory `marketing.store/seed-db` and now prints a stderr WARNING
  every time it does so — there is no silent ephemeral default. See
  "Persistence" below for the full story, including why
  `marketing.store/DatomicStore` is deliberately NOT offered as a
  persistent alternative despite its name.
- **No HTTP endpoint for human approval/rejection of an escalated
  proposal.** `marketing.operation`'s graph has a real human-in-the-loop
  interrupt (`:request-approval`) for a `lead-score-mismatch` SOFT
  escalation (and, at lower rollout phases, for any op not yet in that
  phase's `auto` set); `POST /update-score` (or any op reached before
  its phase allows auto-commit) surfaces that as `202 escalated` with a
  `thread-id`, but there is currently no HTTP route to submit the
  approval/rejection that would resume that graph run. That resume path
  today only exists in-process (`langgraph.graph/run*` with
  `:resume? true`) — a follow-up task, not part of this first HTTP
  layer.

If you need multi-tenant isolation, TLS, rate limiting, or the approval
resume endpoint, that is future work — do not assume this service
already has it. Persistence is covered separately below, since it now
has a genuinely durable option.

## Auth

All endpoints except `GET /` and `GET /health` require:

```
Authorization: Bearer <token>
```

The token is whatever value the server was started with — see
"Running the server" below. **Auth is fail-closed**:

- `marketing.http/start-server!` throws (refuses to start) if given a
  nil/blank token.
- `clojure -M:serve` (`marketing.http/-main`) reads
  `$ISIC6201_API_TOKEN` at startup; if it is unset or blank, it prints a
  fatal error to stderr and exits `1` **without starting the server at
  all**. There is no "runs with auth disabled" fallback anywhere in this
  code.
- Every request to a protected endpoint that doesn't present the exact
  matching bearer token gets `401 {"error": "unauthorized"}`.
- The token match itself is a **constant-time comparison**
  (`java.security.MessageDigest/isEqual` over UTF-8 bytes, not `=`) —
  closes the timing-attack gap recorded as an unmitigated finding in
  ADR-2607124600 (raised against the `cloud-itonami-isic-5820` sibling's
  identical `authorized?` pattern, and equally applicable here).

There is no built-in default/fallback token anywhere in `marketing.http`
— you must supply one.

## Running the server

```bash
ISIC6201_API_TOKEN=<your-token> clojure -M:serve
# optional: ISIC6201_HTTP_PORT=9000 (default 8080)
# optional: ISIC6201_STORE_FILE=/path/to/db.edn -- see "Persistence" below
```

### Running via Docker

`Dockerfile` (repo root) packages this exact `-main` entry point into a
container: an `eclipse-temurin:21-jdk-jammy` builder stage clones the
public `kotoba-lang/crm`/`kotoba-lang/langgraph` sibling repos this
repo's `deps.edn` `:local/root` paths expect and pre-fetches all deps
(`clojure -P -M:serve`, including `kotoba-lang/langchain` via
langgraph's pinned `:git/sha`), and a minimal `eclipse-temurin:
21-jre-jammy` runtime stage runs `clojure -M:serve` as a non-root user
(uid 10001) with only the pre-warmed caches + source tree + `curl`
(for `HEALTHCHECK GET /health`) — no JDK, no git, no build tooling.
Config is read from the container environment only, never baked in.

```bash
docker build -t cloud-itonami-isic-6201:local .

mkdir -p /tmp/isic6201-data
docker run -d --name isic6201 -p 8080:8080 \
  -e ISIC6201_API_TOKEN=<your-token> \
  -e ISIC6201_STORE_FILE=/data/db.edn \
  -v /tmp/isic6201-data:/data \
  cloud-itonami-isic-6201:local

curl -s http://localhost:8080/health
curl -s "http://localhost:8080/dashboard?role=marketer" \
  -H "Authorization: Bearer <your-token>"

docker stop isic6201 && docker rm isic6201
```

**Verified end-to-end** (real `docker build`/`docker run`, not just a
written-but-untested Dockerfile): `GET /health` returned
`{"status":"ok","store":"reachable"}`; `GET /dashboard` without a
bearer token returned `401`; with the token it returned real aggregated
dashboard data over the seeded demo dataset; `docker exec ... id`
confirmed the process runs as `uid=10001(isic6201)`, not root; and the
bind-mounted `ISIC6201_STORE_FILE` held the persisted snapshot on the
host filesystem after the container was stopped and removed. See
README.md's "Running via Docker" section for the same transcript.
`.github/workflows/ci.yml` runs `docker build .` (build-only) on every
push/PR — no `docker push`/registry/deploy step is included (out of
scope here; needs credentials and an infra decision not available in
this environment).

If `$ISIC6201_STORE_FILE` is **unset**, `-main` starts the server against
a fresh `marketing.store/seed-db` — the same small fictitious demo
dataset `marketing.sim` uses (contacts `contact-100`..`contact-600`,
campaigns `camp-100`/`camp-200`) — **and prints a WARNING to stderr**
that all state will be lost when the process exits. There is no
silent/default path into that mode.

### Persistence

`marketing.http/-main` picks its `Store` backend from
`$ISIC6201_STORE_FILE`:

- **Set** (e.g. `ISIC6201_STORE_FILE=/var/lib/isic6201/db.edn`) — runs
  against `marketing.file-store/FileStore`: a full EDN snapshot of every
  contact/campaign/send/engagement-history record and the audit ledger
  is written to that path after every mutating call (write-then-rename,
  so a crash mid-write can't leave a truncated snapshot), and loaded
  back from that path the next time the process starts. **This is
  disk-durable and has been verified end-to-end**: a real
  `clojure -M:serve` process was started against a temp
  `ISIC6201_STORE_FILE`, a real `POST /advance-stage` committed
  `contact-100`'s lifecycle stage `:lead` → `:mql` over real HTTP, the
  process was killed (`kill -9`, not a graceful shutdown), restarted
  against the same file, and `GET /dashboard` showed the committed
  change was still there (lead-lifecycle stage-counts shifted from
  `{:lead 3 :mql 1}` to `{:lead 2 :mql 2}`). As an independent
  confirmation that the RESTARTED process actually loaded the persisted
  state (not just that the response happened to say "held"): a follow-up
  `POST /advance-stage` attempting to revert `contact-100` from `:mql`
  back to `:lead` was rejected by `stage-sequence-gate`, and the
  violation detail text explicitly named the contact's current stage as
  `:mql` (`"contact contact-100 の現stage :mql から :lead への遷移は無効"`) —
  a value the restarted process could only have produced by reading it
  back from the on-disk snapshot, since a fresh in-memory seed would
  have shown `:lead`. See `marketing.file-store`'s ns docstring for what
  this backend is NOT (not multi-writer-safe — one path, one process at
  a time; no query engine; no transaction history).
- **Unset** — runs against `marketing.store/seed-db` (ephemeral,
  in-memory, discarded on exit) and prints a stderr WARNING every time.

**`marketing.store/datomic-store`/`DatomicStore` is deliberately NOT
wired into `-main` at all**, despite this file's prior revision
suggesting operators call it directly for a "persistent/real backend" —
that suggestion was inaccurate for how `DatomicStore` is actually
implemented in this repo (checked directly, not assumed by analogy to
`cloud-itonami-isic-5820`'s identical finding about its own
`crm.store/DatomicStore`). Its constructor
(`(->DatomicStore (langchain.db/create-conn schema))`) builds a plain
`(atom {:db ... :log []})` via `langchain.db` (a pure, dependency-free,
**in-process** EAV emulation — see that ns's docstring) — there is no
connection URI, no socket, no file, nothing that outlives the JVM heap.
It is Datomic-API-*shaped* (which is what makes
`test/marketing/store_contract_test.clj`'s `MemStore ≡ DatomicStore`
parity test meaningful for a *future* backend swap), not
Datomic-*backed*. As shipped, selecting `DatomicStore` would be exactly
as ephemeral as `seed-db` — just with a name that implies otherwise —
so this fix does not offer it as an `-main` option under any env var
name (e.g. an `ISIC6201_DATOMIC_URI`), to avoid exactly the "silently
substitute an in-memory store relabeled as persistent" trap.

Making `DatomicStore` genuinely durable is real follow-up work, not done
here: `marketing.store` would need to accept an injected `:db-api` map
(the shape `langchain.db/api` already documents) instead of hardcoding
calls to `langchain.db` directly, pointed at either a real Datomic Local
process or a live kotoba-server pod via `langchain.kotoba-db/kotoba-api`
— both require infrastructure (a running server, credentials) this
sandboxed build environment does not have, so that path was not
attempted here rather than faked.

## Endpoints

### `GET /`

No auth. Info/discovery page (JSON, not HTML — this is a headless
service).

```bash
curl -s http://localhost:8080/
```

```json
{
  "actor": "cloud-itonami-isic-6201",
  "isic-code": "6201",
  "version": "0.1.0",
  "links": {
    "health": "/health",
    "send": "/send",
    "advance-stage": "/advance-stage",
    "update-score": "/update-score",
    "dashboard": "/dashboard",
    "api-docs": "docs/api.md"
  }
}
```

### `GET /health`

No auth. Liveness + a cheap store-connectivity check (calls
`marketing.store/all-contacts` against the injected store and reports
whether it threw).

```bash
curl -s http://localhost:8080/health
```

```json
{"status": "ok", "store": "reachable"}
```

Returns `503 {"status": "degraded", "store": "unreachable"}` if the
store call throws.

### `POST /send`, `POST /advance-stage`, `POST /update-score`

Auth required, all three. Each is a thin JSON adapter over ONE
`langgraph.graph/run*` call with a fresh thread-id against the
**existing** `marketing.operation/build` OperationActor graph — exactly
what `marketing.sim`/the test suite already exercise: MarketingOps-LLM
(`marketing.llm`) drafts a proposal -> ConsentGovernor
(`marketing.policy/check`) censors it -> the phase gate
(`marketing.phase/gate`) applies rollout-phase restrictions -> commit /
hold / escalate. The route determines `"op"` (`:campaign/send-message`,
`:lead/advance-stage`, `:lead/update-score` respectively) — the request
body never repeats it.

**Request body fields** (top-level fields map 1:1 onto
`marketing.operation`'s existing `request` map; see `marketing.llm`'s ns
docstring / `marketing.sim` for the canonical shapes this actually
accepts), plus a nested `"context"` for the caller's role/phase:

| Field | Required by | Notes |
|---|---|---|
| `campaign-id` | `/send` | string |
| `contact-id` | all three | string |
| `to-stage` | `/advance-stage` | string, coerced to a keyword (e.g. `"mql"` -> `:mql`) |
| `score` | `/update-score` | number |
| `subject` | none (optional) | defaults to `contact-id` when omitted — this actor's own code (`marketing.sim`, `marketing.policy-contract-test`) always sets `subject` equal to the contact-id for all three write ops, so this fills in that existing convention rather than inventing a new shape. Pass it explicitly only if you want a different value in the response/ledger. |
| `context.actor-id` | none (optional) | string |
| `context.actor-role` | effectively required | coerced to a keyword (e.g. `"marketer"` -> `:marketer`); omitting it means no role is authorized under `marketing.policy/permissions` and the op is HELD by the `rbac` gate |
| `context.phase` | none (optional) | integer 0-3, see `marketing.phase`; omitted = `marketing.phase/default-phase` = `1`, the most conservative phase, same fail-closed default the library itself uses |

**Responses** (identical shape across all three endpoints):

- `200` — the graph ran to completion (`:done`). Body:
  - Committed: `{"decision": "committed", "op": .., "subject": .., "record": {...}}`
  - Held (a HARD governor violation, or phase-disabled): `{"decision": "held", "op": .., "subject": .., "violations": [{"rule": "...", "detail": "..."}], "confidence": 0.0-1.0}`
- `202` — the graph interrupted before human approval (SOFT/always-escalate — today only reachable via `/update-score`'s `lead-score-mismatch` gate, or any op not yet in the caller's phase's `auto` set): `{"decision": "escalated", "op": .., "subject": .., "thread-id": "...", "reason": "...", "confidence": .., "note": "..."}`. See "Honest scope" above — there is no HTTP endpoint yet to submit the approval for this `thread-id`.
- `400` — missing/invalid JSON body, or a missing required field for that endpoint.
- `401` — missing/incorrect bearer token.
- `500` — unexpected error (includes the exception message; this is a bug if it happens for a documented request shape).

**curl examples**:

```bash
# Clean send -> commit (contact-100 is opted-in, not yet sent camp-100)
curl -s -X POST http://localhost:8080/send \
  -H "Authorization: Bearer $ISIC6201_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"campaign-id":"camp-100","contact-id":"contact-100",
       "context":{"actor-id":"mktr-1","actor-role":"marketer","phase":3}}'

# Consent revoked (contact-200 is opted-out) -> held by consent-revoked-send-gate
curl -s -X POST http://localhost:8080/send \
  -H "Authorization: Bearer $ISIC6201_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"campaign-id":"camp-100","contact-id":"contact-200",
       "context":{"actor-id":"mktr-1","actor-role":"marketer","phase":3}}'

# Clean stage advance -> commit (contact-100 is :lead)
curl -s -X POST http://localhost:8080/advance-stage \
  -H "Authorization: Bearer $ISIC6201_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"contact-id":"contact-100","to-stage":"mql",
       "context":{"actor-id":"mktr-1","actor-role":"marketer","phase":3}}'

# Stage skip (lead -> customer) -> held by stage-sequence-gate
curl -s -X POST http://localhost:8080/advance-stage \
  -H "Authorization: Bearer $ISIC6201_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"contact-id":"contact-400","to-stage":"customer",
       "context":{"actor-id":"mktr-1","actor-role":"marketer","phase":3}}'

# Lead-score update matching the engagement-history recompute -> commit
curl -s -X POST http://localhost:8080/update-score \
  -H "Authorization: Bearer $ISIC6201_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"contact-id":"contact-500","score":39,
       "context":{"actor-id":"mgr-1","actor-role":"marketing-manager","phase":3}}'

# Lead-score update disagreeing with the recompute -> 202 escalated
curl -s -X POST http://localhost:8080/update-score \
  -H "Authorization: Bearer $ISIC6201_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"contact-id":"contact-500","score":90,
       "context":{"actor-id":"mgr-1","actor-role":"marketing-manager","phase":3}}'
```

### `GET /dashboard`

Auth required, **plus** the exact same RBAC check
`marketing.dashboard/snapshot` already enforces for any caller
(`marketing.dashboard/authorized?` against `marketing.policy/
permissions`'s `:marketing/view-dashboard` entitlement, granted to
`:marketer`/`:marketing-manager`) — this endpoint calls
`marketing.dashboard/snapshot` directly with the caller's role and maps
its own `{:authorized? false :reason :rbac}` result onto HTTP 403. The
RBAC decision is never bypassed or reimplemented here (unlike 5820's
`/dashboard`, this actor's `marketing.dashboard/snapshot` owns its own
RBAC gate internally, so there is no separate `marketing.policy/check`
call in `marketing.http` — see `docs/DESIGN.md` §9 for why).

**Query params**: `role` (required — `marketer`/`marketing-manager`/
anything else). Unlike 5820's `/dashboard`, there is no `year`/`month`
param — this actor's dashboard is a point-in-time snapshot with no
revenue-recognition/ASC 606 date threading (see `marketing.dashboard`'s
ns docstring).

**Responses**:

- `200` — `marketing.dashboard/snapshot`'s result as JSON minus the
  `authorized?` key (`lifecycle-funnel`, `conversion-rates`,
  `campaign-rollup`, `lead-score-distribution`).
- `400` — missing `role`.
- `401` — missing/incorrect bearer token.
- `403` — `{"error": "forbidden", "reason": "rbac"}` — the role is not
  `:marketer`/`:marketing-manager`.

**curl examples**:

```bash
# marketer -> real data
curl -s "http://localhost:8080/dashboard?role=marketer" \
  -H "Authorization: Bearer $ISIC6201_API_TOKEN"

# guest -> 403 forbidden
curl -s "http://localhost:8080/dashboard?role=guest" \
  -H "Authorization: Bearer $ISIC6201_API_TOKEN"
```

## Testing

`test/marketing/http_test.clj` starts the real `marketing.http` server
on an ephemeral port (`:port 0`) inside the test JVM via
`start-server!`, makes real HTTP requests against it with
`java.net.http` (no mocked handler shortcut), and stops the server in
teardown. Scenarios reuse the exact governed cases `marketing.sim`/
`marketing.policy-contract-test` already exercise rather than inventing
new ones: health/root with no auth; 401 without a token on each
protected endpoint; a clean send -> committed (op1); a send to an
opted-out contact -> held by `consent-revoked-send-gate` (op2); a clean
stage advance -> committed (op7); a stage-skip advance -> held by
`stage-sequence-gate` (op6 shape, run against a distinct contact so test
order never matters); a lead-score update matching the recompute ->
committed (op8); a lead-score update disagreeing with the recompute ->
`202 escalated` (op9); `/dashboard` as an unauthorized role -> 403;
`/dashboard` as `marketer` -> real data.

## Real-model MarketingOps-LLM advisor (`src/marketing/llm_realmodel.clj`)

**Honest gap, and what closes it.** `src/marketing/llm.cljc`'s
MarketingOps-LLM advisor is a SEALED, deterministic mock
(`marketing.llm/mock-advisor` / `marketing.llm/infer`) — it never calls a
real language model. That was a genuine, known gap toward real
production operation. `marketing.llm-realmodel` adds the ADAPTER so an
operator who supplies real model API credentials gets a genuinely wired
real-model advisor — **it does not itself supply those credentials, and
no real model call has been exercised anywhere in this build** (this
sandbox has none of `ANTHROPIC_API_KEY`/`OPENAI_API_KEY`/etc.). See "What
is verified vs. unverified" below before relying on this in production.
Mirrors `cloud-itonami-isic-5820`'s `src/crm/llm_realmodel.clj` (same
design, `ISIC6201_`-prefixed env vars instead of `ISIC5820_`).

`marketing.llm.cljc` already had exactly the seam this needed:
`marketing.llm/llm-advisor` wraps ANY `langchain.model/ChatModel` in the
same `marketing.llm/Advisor` protocol (`-advise`) `marketing.operation/
build`'s `:advise` node calls — same proposal shape in, same proposal
shape out (including `:source` always `nil` for this actor — see
`marketing.llm`'s ns docstring). This file does not change that
graph-facing contract at all; it only supplies a second, real
`ChatModel` implementation (`langchain.model/openai-model`/
`anthropic-model`, both already generic/already-tested upstream in
`kotoba-lang/langchain`) for `llm-advisor` to wrap.

### Env vars (mirrors this org's own `ITO_MODEL_*`/`ISIC5820_MODEL_*` convention)

This repo follows the SAME convention `orgs/gftdcojp/cloud-itonami`'s
`cloud_itonami.runtime` namespace (and `cloud-itonami-isic-5820`'s
`crm.llm-realmodel`) already established for this lineage — adapted with
an `ISIC6201_`-prefix so sibling `cloud-itonami-isic-*` actors on the
same host/CI never collide on one another's model config:

| Env var | Required? | Meaning |
|---|---|---|
| `ISIC6201_MODEL_API_KEY` | **the sole trigger** | If unset/blank, `marketing.http` runs the sealed mock advisor (unchanged default behavior). If set and non-blank, `marketing.http` runs the real-model advisor instead. |
| `ISIC6201_MODEL_PROVIDER` | optional (default `openai`) | `openai` \| `anthropic` \| `openclaw` (any OpenAI-compatible endpoint at a custom URL — self-hosted gateway, Ollama, vLLM, etc.) |
| `ISIC6201_MODEL_URL` | required for `openclaw`, optional override for openai/anthropic | Chat-completions endpoint URL. `openai`/`anthropic` already have a public default hardcoded in `langchain.model`. |
| `ISIC6201_MODEL` | optional | Model name (default `gpt-4o-mini` for openai/openclaw, `claude-opus-4-8` for anthropic). |

```bash
ISIC6201_API_TOKEN=<token> \
ISIC6201_MODEL_API_KEY=<real key> \
ISIC6201_MODEL_PROVIDER=openai \
ISIC6201_MODEL=gpt-4o-mini \
  clojure -M:serve
```

### Startup log / `preflight`

`marketing.http/-main`/`start-server!` always prints which advisor mode
it picked (`resolve-advisor!`) plus `marketing.llm-realmodel/preflight`'s
report — **the same fail-visible discipline `warn-ephemeral-store!`
already established for storage**. `preflight` never attempts a live
network call and never prints the API key value (only `:api-key?`, a
boolean):

```clojure
(marketing.llm-realmodel/preflight {:provider "openclaw"})
;=> {:provider :openclaw, :url nil, :api-key? false, :model "gpt-4o-mini",
;    :ok? false, :missing [:ISIC6201_MODEL_URL :ISIC6201_MODEL_API_KEY]}
```

### What is verified vs. genuinely unverified

- **Verified**: `preflight`'s missing/present reporting across the full
  permutation matrix (openai/anthropic/openclaw, present/absent url,
  present/absent key, unknown provider, blank-string env values) — see
  `test/marketing/llm_realmodel_test.clj`'s `preflight-*` tests.
- **Verified**: the exact JSON request this adapter sends (method,
  bearer header, model field, message shape) and its parsing of a
  well-formed OpenAI-compatible response — against a **real local
  `org.httpkit.server` stub** bound to an ephemeral port (a real HTTP
  round-trip over a real socket, not an in-process function fake) —
  including a full round-trip through `marketing.llm/llm-advisor` ->
  `marketing.llm/parse-proposal` into the exact proposal shape
  `marketing.operation/build` expects, and the fallback path for a
  non-EDN-parseable model response.
- **Genuinely UNVERIFIED, and cannot be verified in this sandbox**:
  whether any ACTUAL target model API (OpenAI, Anthropic, or a real
  OpenAI-compatible gateway) accepts this exact request shape, or how it
  actually behaves — there are no model API credentials available here,
  and fabricating a fake endpoint would not prove anything about a real
  one. An operator who sets `ISIC6201_MODEL_API_KEY` for real gets a
  genuinely wired adapter, but its real-call behavior is unverified
  until they exercise it themselves.
