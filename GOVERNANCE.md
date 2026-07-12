# Governance

`cloud-itonami-isic-6201` is an OSS open-business blueprint. Governance
covers both code and the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- MarketingOps-LLM cannot directly send a marketing message, advance a
  lead's lifecycle stage, or update a lead's score.
- ConsentGovernor remains independent of the advisor.
- hard governor violations (rbac, consent-revoked-send-gate,
  double-send-gate, stage-sequence-gate) cannot be overridden by human
  approval.
- a lead-score update that disagrees with the engagement-history
  recompute always reaches a human, regardless of confidence.
- every commit, hold and approval event is auditable.
- no schema field exists for sales-opportunity/subscription-billing
  records or support-ticket SLAs — this actor is marketing-campaign +
  lead-lifecycle governance only.
- consent state (`:consent-status` + `:unsubscribed?`) is a DEDICATED
  fact, never inferred from `:lifecycle-stage` or any other status
  value.

## Decision Records

Architecture decisions live in `docs/adr/`.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, audit, support and
data-flow review.

Certified operators can lose certification for:

- bypassing governor checks
- sending a marketing message to a contact whose consent has been
  revoked, has expired, or who has an active unsubscribe/suppression
  flag
- sending the same campaign to the same contact twice
- skipping ahead in the lead lifecycle
- committing a lead score that disagrees with the engagement-history
  recompute without human review
- misrepresenting a contact's consent state
