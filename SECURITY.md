# Security Policy

This project handles contact consent records, marketing-campaign send
history, and lead lifecycle/scoring data. Treat vulnerabilities as
potentially high impact even when the demo data is synthetic — sending
a commercial message to a contact who revoked consent or unsubscribed is
a real CAN-SPAM Act (15 U.S.C. §7704) / EU GDPR (Art. 6(1)(a), Art. 7) /
Canada's CASL (S.C. 2010, c. 23) compliance failure, not a cosmetic one.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential exposure
- ConsentGovernor bypass (consent-revoked-send-gate, double-send-gate,
  stage-sequence-gate)
- audit-ledger tampering
- sending a marketing message to a contact whose consent has been
  revoked, has expired, or who carries an active unsubscribe/
  suppression flag
- tenant/contact isolation failures
- lifecycle stage transitions or lead-score commits bypassing the
  required consent or stage-sequence check

## Reporting

Use GitHub private vulnerability reporting when available for the
repository. If that is unavailable, contact the repository maintainers
through the cloud-itonami organization before publishing details.

## Production Guidance

- Store secrets outside Git.
- Run governor tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for marketer/marketing-manager roles and service
  accounts.
- Alert on any consent-revoked-send-gate, double-send-gate, or
  lead-score-mismatch HOLD/escalate spike.
- Sync `:consent-status`/`:unsubscribed?` from your system of record
  (email service provider suppression list, CRM opt-out log) — this
  actor enforces consent, it does not originate it.
