# Open Business Blueprint: cloud-itonami-isic-6201

This repository publishes an OSS business model for operating a
marketing-automation SaaS platform on itonami.cloud — the HubSpot
Marketing Hub / Salesforce Marketing Cloud-class business.

## Classification

- Repository name: `cloud-itonami-isic-6201`
- Primary classification: ISIC Rev.4 6201 (Computer programming
  activities), narrowed to a specific business model: **licensing and
  operating a multi-tenant marketing-campaign + lead-lifecycle
  automation platform for other businesses** — not computer programming
  activities in general (custom software development, bespoke
  integration work, etc.)
- Served domain: marketing-campaign send governance, consent/
  authorization-to-contact enforcement, lead lifecycle-stage governance,
  lead-score integrity — never sales-pipeline/subscription-entitlement
  management, never customer-service ticketing (see sibling-actor notes
  below)

## Customer

- SMBs/mid-market marketing teams needing a governed, audit-ready
  marketing-automation platform without building consent/compliance
  logic themselves
- SaaS vendors needing to enforce that a campaign send cannot itself
  violate CAN-SPAM/GDPR/CASL consent requirements
- other `cloud-itonami-{ISIC}` blueprint operators needing marketing-
  lifecycle governance as a licensed capability

## Problem

Commercial marketing-automation platforms (HubSpot, Salesforce Marketing
Cloud) route consent/suppression enforcement, send deduplication, and
lead-scoring accuracy through configurable-but-optional workflow rules
and list hygiene processes, with no STRUCTURAL guarantee against a
contact who revoked consent or unsubscribed still receiving a
commercial message, the same campaign reaching a contact twice, a
lifecycle stage being skipped to fabricate pipeline momentum, or a lead
score reaching sales that disagrees with the contact's actual engagement
history. This platform seals the MarketingOps-LLM into a single node
and wraps it with an independent ConsentGovernor, a human review
workflow, and an immutable audit ledger — the same discipline
`cloud-itonami-isic-5820`, `-6209`, `-6920`, and every other actor in
this fleet apply to their own domain.

## Revenue Model

- Per-seat subscription (mirrors the platform's own product: a
  marketing-automation business licenses seats to its own customers)
- Certification/audit fee for itonami.cloud operator certification
- Optional managed-hosting fee for operators who do not self-host

## Honest scope (R0)

- Marketing-campaign send governance and lead-lifecycle (5-stage linear
  + 3 exit stages) governance only.
- A fixed weighted-point lead-scoring model only
  (`kotoba.crm.leadscore`) — no ML/predictive scoring, no per-account
  custom weight overrides, no inactivity-based score decay by default.
- 3 consent states, 1 independent unsubscribe/suppression flag — extend
  only by adding real, documented consent states or citing a real
  regulatory basis.

## Explicitly distinct from two fleet siblings

- **`cloud-itonami-isic-7310`** (AdOps-LLM ⊣ Campaign Governor) is an
  **advertising AGENCY** actor — it buys/places ads for clients, a
  services business. This actor is a **software platform**: it is never
  an agency and never manages a client's ad spend.
- **`cloud-itonami-isic-5820`** (RevOps-LLM ⊣ SubscriptionGovernor) is
  the **sales/subscription-commerce CRM** side (opportunity pipeline,
  discount authority, subscription entitlement). This actor is the
  **marketing side** (campaigns, sends, lead scoring, lead lifecycle) —
  a sibling business model per the owner's explicit "split
  responsibility per business model into separate `cloud-itonami-*`
  actors" directive. The two share `kotoba-lang/crm`'s technical
  commons (`kotoba.crm.pipeline`) but are never merged into one actor.

## Sibling-actor roadmap (not yet built)

Consistent with this fleet's narrowing discipline (one business model
per actor, never a monolith):

- **Customer-service hub** (support cases, SLAs, knowledge base) —
  HubSpot Service Hub / Salesforce Service Cloud equivalent. Note
  `cloud-itonami-isic-6209` already covers IT-managed-services/helpdesk
  ticket routing specifically; a CRM/marketing-integrated
  customer-service hub would be a distinct, contact-aware sibling, not a
  duplicate of 6209's scope. `cloud-itonami-isic-5820` already defers
  this same future sibling in its own roadmap — this actor defers it
  too, and it remains unbuilt for either.

Each would get its own ISIC-narrowed registry entry and its own ADR,
following this fleet's one-business-model-per-actor discipline.
