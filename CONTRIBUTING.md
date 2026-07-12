# Contributing

`cloud-itonami-isic-6201` accepts contributions to the OSS actor, governor
tests, documentation, examples and open business blueprint.

## Development

```bash
clojure -M:dev:test
clojure -M:lint
```

Keep changes small and include tests for governor, audit, store or
consent behavior.

## Rules

- Do not commit real customer/contact data, real lead PII, or
  credentials.
- Keep production sends, stage transitions and score updates behind
  ConsentGovernor.
- Treat every new lifecycle stage or consent state as high-risk: add
  tests for consent-revoked-send-gate, double-send-gate,
  stage-sequence-gate, confidence floor, lead-score-mismatch, and audit
  logging.
- Never fabricate a regulatory citation (CAN-SPAM Act 15 U.S.C. §7704,
  EU GDPR Art. 6(1)(a)/Art. 7, Canada's CASL S.C. 2010 c.23) to expand
  apparent coverage.
- Never model consent state (`:consent-status`/`:unsubscribed?`) as an
  overload of `:lifecycle-stage` or any other status value — use
  dedicated facts.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe:

- what behavior changed
- which governor invariant is affected
- how it was tested
- whether operator or certification docs need updates
