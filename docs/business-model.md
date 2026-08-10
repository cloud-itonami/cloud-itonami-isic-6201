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

| Package | Customer | Price shape |
|---|---|---|
| Self-host | operator runs their own instance | AGPL-3.0-or-later, no fee |
| Managed Starter | one tenant, SMB/mid-market B2B marketing team (3–5 seats, ~10,000 contacts) | ¥35,000/月 flat |

**Market-anchored (2026-08-10)**: benchmarked against 5 real
marketing-automation products sold into the Japanese market. **4 of the 5
publish real numbers** — a markedly higher disclosure rate than the
customer-service or market-data categories, and the domestic vendors are the
transparent ones. **SATORI**: 初期費用「300,000円（税別）」+
「148,000円/月（年間契約）（税別）」
(<https://satori.marketing/fee/>). **List Finder**: フリー ¥0、ライト 初期
¥100,000 + 月額 ¥45,000、スタンダード 月額 ¥69,000、プレミアム 月額 ¥92,000
(<https://promote.list-finder.jp/price/>). **Kairos3 Marketing** publishes
floors only: スタンダード「20,000 円/月〜」、プロ「155,000（税別）円〜」, with
「別途、初期費用が発生します」 and the breakdown behind a document request
(<https://www.kairosmarketing.net/kairos3/pricing/ma>). **HubSpot Marketing
Hub**: Starter「最低利用料金：￥840／月／シート」(1,000 marketing contacts),
Professional「￥96,000／月」(3 core seats + 2,000 contacts), Enterprise
「￥432,000／月」(5 seats + 10,000 contacts)
(<https://www.hubspot.jp/pricing/marketing>). **Adobe Marketo Engage**
publishes **nothing** — its four packages (Growth/Select/Prime/Ultimate) are
quoted per database lead count on request only.

For the assumed customer (one tenant, a B2B SMB/mid-market marketing team at
3–5 seats and roughly 10,000 contacts) the measured band is ¥20,000/月
(Kairos3 Standard's published floor, which in practice rises with usage at
that contact count) to ¥148,000/月 (SATORI), with the cheapest *realistic*
full-suite comparable at that size being List Finder ライト at ¥45,000/月 and
HubSpot Professional at ¥96,000/月. **¥35,000/月 sits low in that band, below
every full-suite comparable priced for ~10,000 contacts.** That placement is
deliberate: this actor ships no email delivery engine, no form/landing-page
builder, no web tracking and no scenario builder — it sells the consent,
double-send, stage-sequence and lead-score-integrity **gate** and the
append-only ledger, not a marketing suite. It is priced above the band's
nominal floor because a managed tenant carries a human review queue, which is
per-tenant labour that a self-serve SaaS marginal-cost structure does not
explain. What justifies charging anything at all next to a ¥20,000 full tool
is the one property none of the five comparators has structurally: consent
revocation, unsubscribe suppression, campaign double-send and lifecycle
stage-skipping are enforced by an independent ConsentGovernor that cannot be
switched off in configuration, where every comparator routes the same
CAN-SPAM/GDPR/CASL concerns through configurable-but-optional workflow rules.

**Subscribe (2026-08-10)**: a live Stripe Payment Link for the Managed
Starter tier (¥35,000/月 flat) is available now —
[**subscribe to Managed Marketing Ops — Starter**](https://buy.stripe.com/fZu7sM86z8tqe9O3HIeEo0c).
This is a no-code Stripe-hosted checkout; nothing in this repo's actor code
changed. After subscribing, contact gftdcojp to arrange managed-tenant setup
(manual fulfillment today, no automated onboarding yet). **No marketing team
or operator has claimed or subscribed to this tier yet — this is a live,
working checkout with zero paid tenants, not a claim of existing revenue.**

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
