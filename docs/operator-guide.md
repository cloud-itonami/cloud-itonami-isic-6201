# Operator Guide

## 1. Fork and Run

```bash
git clone https://github.com/cloud-itonami/cloud-itonami-isic-6201
cd cloud-itonami-isic-6201
clojure -M:dev:test
clojure -M:dev:run
```

## 2. Production Checklist

- replace demo contacts/campaigns/sends/engagement history with real,
  source-cited data
- configure Datomic Local, kotoba-server or an equivalent durable SSoT
- configure the LLM adapter through environment variables or secret manager
- sync `:consent-status`/`:unsubscribed?` from your email service
  provider's suppression list / CRM opt-out log — this actor enforces
  consent, it does not originate it
- define RBAC rules for `:marketer`/`:marketing-manager` roles
- run `clojure -M:dev:test` / `clojure -M:lint`
- verify audit-ledger export
- document backup/restore and incident response
- get written legal/compliance review on consent handling (CAN-SPAM Act,
  EU GDPR, Canada's CASL, and any other applicable regime) for the
  jurisdictions you serve

## 3. Operator Responsibilities

- verify a contact's consent state against your own consent-capture
  records and unsubscribe/suppression list before registering them in
  the store — this actor never invents consent, only enforces what's
  already recorded
- verify campaign send history against your email service provider's
  actual delivery log before registering it in the store
- secure infrastructure and tenant isolation
- human review workflow for lead-score-mismatch operations
- data-retention policy (including consent-withdrawal record retention
  requirements under GDPR/CASL)
- security updates

The OSS project provides software and an operating blueprint. It does
not verify a contact's actual consent status, an email service
provider's suppression-list accuracy, or regulatory compliance on the
operator's behalf.

## 4. Explicitly out of scope for R0

- Sales-pipeline/subscription-entitlement management (opportunity
  pipeline, discount authority, subscription tiers) —
  `cloud-itonami-isic-5820`'s scope, a distinct sibling business model,
  never folded into this one.
- Customer-service/support ticketing (cases, SLAs, knowledge base) —
  candidate for another sibling actor, not yet built.
- ML/predictive lead scoring, per-account custom scoring-weight
  overrides, and inactivity-based score decay by default —
  `kotoba.crm.leadscore` models a fixed weighted-point sum only (opt-in
  recency decay is supported but off by default).
