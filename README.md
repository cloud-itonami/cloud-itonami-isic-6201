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

## Run

```bash
clojure -M:dev:test
clojure -M:dev:run
```

## Documentation

- `docs/business-model.md` — the OSS open-business blueprint
- `docs/DESIGN.md` — actor architecture (Japanese)
- `docs/operator-guide.md` — fork/run/production checklist
- `docs/adr/0001-architecture.md` — the authoritative architecture record

## License

AGPL-3.0 — see `LICENSE`.
