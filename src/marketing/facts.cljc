(ns marketing.facts
  "R0 provenance/authority catalog for the marketing-automation SaaS
  platform actor (HubSpot Marketing Hub / Salesforce Marketing Cloud-
  class, ISIC Rev.4 6201 narrowed to marketing-campaign + lead-lifecycle
  governance) — the ONLY consent states and lifecycle-stage shape the
  ConsentGovernor will accept (honesty over coverage, same discipline as
  sibling actors' facts catalogs).

  Two closed sets/scales:
    1. `consent-statuses` — a contact's authorization-to-contact state.
       Modeled as a DEDICATED enum (`:consent-status`) plus an
       INDEPENDENT boolean (`:unsubscribed?`), never inferred from
       `:lifecycle-stage` (this fleet's established lesson from
       `cloud-itonami-isic-6920`'s status-lifecycle bug, ADR-2607071351:
       a distinct legal state needs a dedicated fact, not an overload of
       a business-process stage). Real regulatory basis, never
       fabricated:
         - CAN-SPAM Act, 15 U.S.C. §7704 — commercial email must honor
           opt-out/unsubscribe requests promptly and identify the
           sender; a sender may not contact someone who has opted out.
         - EU GDPR Art. 6(1)(a) + Art. 7 — consent is a lawful basis for
           processing personal data and must be as easy to withdraw as
           it was to give; withdrawn consent removes that lawful basis
           going forward.
         - Canada's Anti-Spam Legislation (CASL), S.C. 2010, c. 23 —
           commercial electronic messages require express or implied
           consent and a mandatory, functioning unsubscribe mechanism.
    2. `lifecycle-stage-order` / `exit-stages` — the marketing lead
       lifecycle shape, delegated to the shared `kotoba.crm.pipeline`
       technical commons (the same generic ordered-stage validator
       `cloud-itonami-isic-5820` uses for its sales pipeline — this
       actor is the marketing side of the same shared commons, a
       distinct sibling business model, never folded into 5820)."
  )

(def consent-statuses
  "A contact's consent state. CAN-SPAM/GDPR/CASL do not recognize a
  'maybe': a contact either currently has a standing lawful basis to be
  contacted (`:opted-in`), has revoked that basis (`:opted-out`), or the
  consent basis has lapsed (`:expired`, e.g. a jurisdiction- or
  contract-defined validity window)."
  #{:opted-in :opted-out :expired})

(defn consent-status-recognized? [status]
  (contains? consent-statuses status))

(defn send-authorized?
  "True iff a contact may currently be sent a marketing message. Checks
  TWO independent facts, both required:
    1. `:consent-status` must be `:opted-in` (not `:opted-out`, not
       `:expired`).
    2. `:unsubscribed?` must be false.
  These are checked independently and BOTH must pass — a contact can be
  `:opted-in` yet still carry an active unsubscribe/suppression flag
  (e.g. a blanket CAN-SPAM opt-out recorded through a separate
  mechanism than the original consent capture), and that flag alone
  must block a send regardless of `:consent-status`. This is
  deliberately NOT a single derived boolean and NOT inferred from
  `:lifecycle-stage` — the same dedicated-fact discipline
  `cloud-itonami-isic-6920` learned the hard way (ADR-2607071351)."
  [{:keys [consent-status unsubscribed?]}]
  (boolean (and (= :opted-in consent-status) (not unsubscribed?))))

(def lifecycle-stage-order
  "The marketing lead lifecycle: an anonymous site visitor becomes a
  known `:subscriber`, then a `:lead`, then a marketing-qualified lead
  (`:mql`), then a sales-qualified lead (`:sql`) handed to sales, then a
  `:customer`. Handoff to `cloud-itonami-isic-5820`'s sales pipeline
  (`:prospecting` etc.) happens at the `:sql`→`:customer` boundary in
  spirit, but this actor does not model that handoff itself — see
  `docs/business-model.md`."
  [:subscriber :lead :mql :sql :customer])

(def exit-stages
  "Terminal lifecycle exits reachable from any non-terminal stage."
  #{:unsubscribed :bounced :disqualified})

(defn coverage
  "Honest, machine-checkable report of what R0 actually covers."
  []
  {:consent-statuses consent-statuses
   :lifecycle-stage-count (count lifecycle-stage-order)
   :exit-stage-count (count exit-stages)
   :note (str "R0 scope: 3-state consent catalog (opted-in/opted-out/"
              "expired) plus an independent unsubscribed? suppression "
              "flag, "
              (count lifecycle-stage-order)
              "-stage linear lead lifecycle plus "
              (count exit-stages)
              " exit stages. Regulatory basis for the consent gate: "
              "CAN-SPAM Act 15 U.S.C. §7704, EU GDPR Art. 6(1)(a) + "
              "Art. 7, Canada's CASL S.C. 2010 c.23. Extend only by "
              "appending a documented consent state or lifecycle "
              "stage — never fabricate either.")})
