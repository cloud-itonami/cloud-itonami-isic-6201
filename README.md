# cloud-itonami-isic-6201

Open Business Blueprint for **ISIC Rev.4 6201**: computer programming
activities, narrowed to a **marketing-automation SaaS platform**
business — the HubSpot Marketing Hub / Salesforce Marketing Cloud class
of business — published as an OSS business that any qualified operator
can fork, deploy, run, improve and sell.

Marketing campaign sends and lead lifecycle transitions move through a
governed workflow: whether a contact may currently be contacted, whether
a campaign has already been sent to that contact, whether a lead's
lifecycle stage transition is a valid forward step, and whether a
proposed lead score matches the engagement-history ground truth are all
checked before anything commits. Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph) StateGraph
runtime and [`kotoba-lang/crm`](https://github.com/kotoba-lang/crm)'s
technical commons — the same actor pattern as
[`cloud-itonami-isic-5820`](https://github.com/cloud-itonami/cloud-itonami-isic-5820)
(sales-pipeline/subscription-entitlement CRM, a distinct sibling
business model — see "Scope" below) and
[`cloud-itonami-isic-6209`](https://github.com/cloud-itonami/cloud-itonami-isic-6209).

> **Why an actor layer at all?** A MarketingOps-LLM is great at
> normalizing incoming send/stage-advance/score-update requests and
> drafting proposals — but it has **no notion of consent-authorization
> validity, whether a campaign has already been sent to a contact,
> lifecycle stage-sequence validity, or whether a proposed lead score
> actually matches the contact's engagement history**. Letting it commit
> directly invites a commercial message reaching someone who revoked
> consent or already unsubscribed (a real CAN-SPAM/GDPR/CASL compliance
> failure, not a cosmetic one), a contact being spammed twice by the
> same campaign, a lifecycle stage being skipped to fabricate pipeline
> momentum, or a fabricated lead score reaching sales unnoticed. This
> project seals the MarketingOps-LLM into a single node and wraps it
> with an independent **ConsentGovernor**, a human **review workflow**,
> and an immutable **audit ledger**.

## Scope (deliberately narrow — read this before anything else)

This actor governs **marketing-campaign sends and lead-lifecycle
transitions/scoring**. It is explicitly distinct from two fleet
siblings:

- [`cloud-itonami-isic-7310`](https://github.com/cloud-itonami/cloud-itonami-isic-7310)
  (AdOps-LLM ⊣ Campaign Governor) is an **advertising AGENCY** actor —
  it buys/places ads for clients, a services business. This actor is a
  **software platform**, not an agency: it never buys media or acts on
  behalf of a client's ad budget.
- [`cloud-itonami-isic-5820`](https://github.com/cloud-itonami/cloud-itonami-isic-5820)
  is the **sales/subscription-commerce CRM** side (opportunity pipeline,
  discount authority, subscription entitlement). This actor is the
  **marketing side** (campaigns, sends, lead scoring, lead lifecycle) —
  a sibling business model per the owner's explicit "split
  responsibility per business model into separate `cloud-itonami-*`
  actors" directive, never merged into 5820.

It never provides sales-pipeline/subscription-entitlement management or
customer-service ticketing (support cases, SLAs) — a customer-service
hub (Salesforce Service Cloud / HubSpot Service Hub-class) remains an
**UNBUILT future sibling**, same as `cloud-itonami-isic-5820` already
defers it (see `docs/business-model.md`'s roadmap section, never folded
into this one).

## The core contract

```
request + injected role/phase context
        │
        ▼
   ┌─────────────────┐  proposal      ┌──────────────────────────┐
   │ MarketingOps-LLM │ ─────────────▶ │ ConsentGovernor           │  (independent system)
   │ (sealed)         │  draft         │  consent-revoked-send ·   │
   └─────────────────┘                 │  double-send ·            │
                                        │  stage-sequence ·         │
                                        │  lead-score-mismatch      │
                                        └──────────────────────────┘
                                              │
                                   commit only if allowed
                                              ▼
                                    append-only audit ledger
```

**Single invariant**: MarketingOps-LLM never sends, advances a lead's
lifecycle stage, or updates a lead's score the ConsentGovernor would
reject.

## Dashboard (`src/marketing/dashboard.cljc`)

This actor's first aggregate-view capability — NOT the governed
single-record disclosure `docs/DESIGN.md` §8 explains this actor
deliberately does not have (no `report.cljc`, no `:disclosure/query`-
style op). `marketing.dashboard` instead aggregates across every
contact/campaign already in a `marketing.store/Store`:

- **Lead-lifecycle funnel** (`lifecycle-funnel`) — stage-counts (current
  snapshot) and reached-counts (how far contacts got), via
  `kotoba.crm.funnel` over this actor's own `marketing.facts/lifecycle-
  stage-order` / `exit-stages`.
- **Conversion rates** (`conversion-rates`) — stage-to-stage rates
  (subscriber→lead→mql→sql→customer) via `kotoba.crm.funnel/conversion-
  rate`.
- **Campaign performance rollup** (`campaign-rollup`) — per-campaign
  successful-send counts and `consent-revoked-send-gate` rejection
  counts, aggregated from data the ConsentGovernor already produces.
- **Lead-score distribution** (`lead-score-distribution`) — a histogram
  and summary stats over every contact's score, always recomputed via
  `kotoba.crm.leadscore/recompute-score` (never the stored/cached
  `:lead-score`), plus explicit stale-score detection.

`marketing.dashboard/snapshot` is the gated entry point (`marketing.
dashboard/authorized?` checks the caller's role against `marketing.
policy/permissions`'s `:marketing/view-dashboard` entitlement, granted
to `:marketer`/`:marketing-manager`; anything else gets `{:authorized?
false :reason :rbac}`, never partial data). See `docs/DESIGN.md` §9 for
the full RBAC-gating rationale.

**Inherited R0 limits (honest scope, not this actor's own choice):**
- From `kotoba.crm.funnel`: a point-in-time snapshot only — no time
  series, no stage-history log. A contact currently in an exit stage
  (e.g. `:unsubscribed`) is excluded from `reached-counts` entirely,
  because this actor's contact shape carries no `:reached-stage` fact
  to recover which forward stage it exited from.
- From `kotoba.crm.leadscore`: a fixed weighted-point scoring model
  only — no ML/predictive scoring, no per-account custom weights, no
  inactivity-based decay applied here.

## Run

```bash
clojure -M:dev:test
clojure -M:dev:run
```

## Running as a service

`src/marketing/http.clj` is a minimal, real HTTP service layer over the
same `marketing.operation`/`marketing.policy`/`marketing.dashboard`
actor graph — a thin JSON adapter, not a reimplementation of any
governance logic (mirrors `cloud-itonami-isic-5820`'s `src/crm/http.clj`).

```bash
ISIC6201_API_TOKEN=<your-token> clojure -M:serve
# optional: ISIC6201_HTTP_PORT=9000 (default 8080)
# optional: ISIC6201_STORE_FILE=/path/to/db.edn -- disk-durable store (see docs/api.md's Persistence section)
```

Auth is a fail-closed bearer token (`Authorization: Bearer <token>`) —
the server refuses to start at all without `ISIC6201_API_TOKEN` set.
Endpoints: `GET /` and `GET /health` (no auth); `POST /send`,
`POST /advance-stage`, `POST /update-score`, and `GET /dashboard` (auth
required).

**Persistence**: without `$ISIC6201_STORE_FILE`, `-main` runs against an
ephemeral in-memory store and prints a stderr WARNING — all state is
lost on restart. Set `ISIC6201_STORE_FILE` to a path to run against
`marketing.file-store/FileStore` instead, a disk-durable store verified
end-to-end (real process, real HTTP commit, real `kill -9`, real
restart, data still there — including an independent
`stage-sequence-gate` rejection whose violation text named the
restored, not the seeded, lifecycle stage). See
**[`docs/api.md`](docs/api.md)**'s Persistence section for the full
explanation, including why `marketing.store/DatomicStore` — despite its
name — is *not* wired in as a durable option (it is an in-process EAV
atom with no connection URI, exactly as ephemeral as the default store;
the same finding `cloud-itonami-isic-5820` made about its own
`crm.store/DatomicStore`, checked independently here rather than
assumed).

See **[`docs/api.md`](docs/api.md)** for the full endpoint reference
(request/response shapes, auth header, error codes, curl examples) and
its explicit honest-scope statement — this is a real network endpoint,
not yet production-hardened (single-process/single-tenant, no TLS
termination built in, no rate limiting).

### Real-model MarketingOps-LLM advisor (optional)

By default the MarketingOps-LLM advisor (`marketing.llm`) is a SEALED,
deterministic mock — no real language model is ever called.
`src/marketing/llm_realmodel.clj` adds a real OpenAI-compatible/
Anthropic HTTP adapter, wired in via `marketing.http/resolve-advisor!`:
set `ISIC6201_MODEL_API_KEY` and the server uses it instead of the mock
(unset/blank = unchanged sealed-mock default). Mirrors
`cloud-itonami-isic-5820`'s equivalent `ISIC5820_MODEL_API_KEY` feature.

```bash
ISIC6201_API_TOKEN=<token> ISIC6201_MODEL_API_KEY=<real key> clojure -M:serve
# optional: ISIC6201_MODEL_PROVIDER=openai|anthropic|openclaw (default openai)
# optional: ISIC6201_MODEL_URL (required for openclaw), ISIC6201_MODEL
```

**Honest caveat**: this adapter's real-call behavior against an actual
model API has never been exercised in this build (no credentials are
available in the environment it was built in) — it is verified only
against `preflight`'s reporting logic and a local `org.httpkit.server`
stub standing in for the model API. See **[`docs/api.md`](docs/api.md)**'s
"Real-model MarketingOps-LLM advisor" section for exactly what is/isn't
proven.

## Documentation

- `docs/business-model.md` — the OSS open-business blueprint
- `docs/DESIGN.md` — actor architecture (Japanese)
- `docs/operator-guide.md` — fork/run/production checklist
- `docs/api.md` — HTTP API reference (`src/marketing/http.clj`)
- `docs/adr/0001-architecture.md` — the authoritative architecture record

## License

AGPL-3.0 — see `LICENSE`.
