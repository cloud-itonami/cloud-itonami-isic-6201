(ns marketing.policy
  "ConsentGovernor — the independent compliance layer that earns the
  MarketingOps-LLM the right to send a contact a marketing message,
  advance a lead's lifecycle stage, or update a lead's score. The LLM
  has no notion of consent-authorization validity, lifecycle stage-
  sequence validity, or a lead's true engagement-derived score, so this
  MUST be a separate system able to *reject* a proposal and fall back to
  HOLD.

  Four checks, in priority order. The first three are HARD violations: a
  human approver CANNOT override them. The last is SOFT/always-escalate:
  it routes to a human, who may approve.

    1. rbac                       — does actor-role have permission?
    2. consent-revoked-send-gate  — a genuinely NEW check kind for this
                                     fleet: an authorization-to-contact
                                     validity check (distinct from every
                                     existing check kind in this fleet —
                                     not arithmetic, not party-screening/
                                     independence, not engagement-type,
                                     not entitlement-scope, not stage-
                                     sequence). Rejects any propose-send
                                     where the target contact's consent
                                     record shows revoked consent
                                     (`:opted-out`), expired consent
                                     (`:expired`), or an active
                                     unsubscribe/suppression flag
                                     (`:unsubscribed?` true) — regardless
                                     of confidence. Real regulatory
                                     basis, never fabricated: CAN-SPAM
                                     Act 15 U.S.C. §7704 (unsubscribe
                                     honoring, sender identification), EU
                                     GDPR Art. 6(1)(a) + Art. 7 (consent
                                     as a withdrawable lawful basis),
                                     Canada's CASL S.C. 2010 c.23
                                     (express/implied consent, mandatory
                                     unsubscribe mechanism). Modeled as
                                     the DEDICATED `:consent-status`/
                                     `:unsubscribed?` facts, never
                                     inferred from `:lifecycle-stage`
                                     (`cloud-itonami-isic-6920`'s
                                     status-lifecycle bug, ADR-2607071351
                                     — the exact failure mode that bug
                                     was).
    3. double-send-gate           — guards a DEDICATED `:sent?` boolean
                                     fact per (campaign, contact) pairing
                                     rather than a `:status` value (same
                                     6920-lesson discipline as
                                     `cloud-itonami-isic-5820`'s
                                     double-close-gate).
    4. stage-sequence-gate        — another new-to-this-actor check: a
                                     lead lifecycle-stage transition must
                                     be a valid forward step (or an exit)
                                     per `kotoba.crm.pipeline` — no
                                     skipping ahead to `:customer`.
    5. confidence floor (SOFT)    — low confidence → escalate.
    6. lead-score-mismatch (SOFT) — a proposed lead-score update that
                                     disagrees with
                                     `kotoba.crm.leadscore`'s
                                     engagement-history recompute →
                                     ALWAYS escalate, regardless of
                                     confidence (this actor's analog of
                                     `cloud-itonami-isic-5820`'s
                                     revenue-mismatch-imminent gate — the
                                     same 'recompute the ground truth,
                                     never trust the claimed figure'
                                     family)."
  (:require [marketing.facts :as facts]
            [marketing.store :as store]
            [kotoba.crm.pipeline :as pipeline]
            [kotoba.crm.leadscore :as leadscore]))

;; ───────────────────────── policy tables ─────────────────────────

(def confidence-floor 0.6)

(def permissions
  {:marketer          #{:campaign/send-message :lead/advance-stage :lead/update-score}
   :marketing-manager #{:campaign/send-message :lead/advance-stage :lead/update-score}})

;; ───────────────────────── checks ─────────────────────────

(defn- rbac-violations [{:keys [op]} {:keys [actor-role]}]
  (when-not (contains? (get permissions actor-role #{}) op)
    [{:rule :rbac :detail (str actor-role " は " op " の権限を持たない")}]))

(defn- consent-revoked-send-violations
  "HARD. Genuinely new check kind: authorization-to-contact validity.
  Looks up the send's target contact and rejects if `send-authorized?`
  (defined in `marketing.facts`) is false — i.e. consent-status is not
  `:opted-in`, OR the independent `:unsubscribed?` suppression flag is
  set. A contact absent from the store is treated as NOT authorized
  (fail closed — never assume consent for an unknown contact)."
  [{:keys [op]} proposal st]
  (when (= op :campaign/send-message)
    (let [{:keys [contact-id]} (:value proposal)
          c (store/contact st contact-id)]
      (when (or (nil? c) (not (facts/send-authorized? c)))
        [{:rule :consent-revoked-send-gate
          :detail (str "contact " contact-id
                       (if c
                         (str " consent-status=" (:consent-status c)
                              " unsubscribed?=" (boolean (:unsubscribed? c)))
                         " は未登録(fail closed)")
                       " — CAN-SPAM 15 U.S.C. §7704 / EU GDPR Art.6(1)(a)+"
                       "Art.7 / Canada's CASL S.C. 2010 c.23 により送信不可")}]))))

(defn- double-send-violations
  "HARD. Guards the DEDICATED `:sent?` boolean fact for this
  (campaign, contact) pairing — not a `:status` value."
  [{:keys [op]} proposal st]
  (when (= op :campaign/send-message)
    (let [{:keys [campaign-id contact-id]} (:value proposal)
          sr (store/send-record st campaign-id contact-id)]
      (when (:sent? sr)
        [{:rule :double-send-gate
          :detail (str "campaign " campaign-id " は contact " contact-id " へ既に送信済み")}]))))

(defn- stage-sequence-violations
  "HARD. Delegates to the shared `kotoba.crm.pipeline` ordered-stage
  validator — no skip-ahead, no reverse transition, no further
  transition once a contact has reached a terminal/exit stage."
  [{:keys [op]} proposal st]
  (when (= op :lead/advance-stage)
    (let [{:keys [contact-id to-stage]} (:value proposal)
          c (store/contact st contact-id)]
      (when (and c (not (pipeline/valid-transition?
                          facts/lifecycle-stage-order facts/exit-stages
                          (:lifecycle-stage c) to-stage)))
        [{:rule :stage-sequence-gate
          :detail (str "contact " contact-id " の現stage " (:lifecycle-stage c)
                       " から " to-stage " への遷移は無効(スキップまたは逆行)")}]))))

(defn- lead-score-mismatch
  "SOFT, always-escalate. Ground-truth recompute via
  `kotoba.crm.leadscore/mismatch` against the contact's engagement
  history — never silently trusts nor silently rejects a proposed
  score; a genuine mismatch always reaches a human."
  [{:keys [op]} proposal st]
  (when (= op :lead/update-score)
    (let [{:keys [contact-id score]} (:value proposal)
          hist (store/engagement-history st contact-id)]
      (leadscore/mismatch hist score))))

(defn check
  "Censors a MarketingOps-LLM proposal against the policy tables.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :hard? bool :score-mismatch map|nil}."
  [request context proposal st]
  (let [hard (into []
                   (concat (rbac-violations request context)
                           (consent-revoked-send-violations request proposal st)
                           (double-send-violations request proposal st)
                           (stage-sequence-violations request proposal st)))
        conf           (:confidence proposal 0.0)
        low?           (< conf confidence-floor)
        score-mismatch (lead-score-mismatch request proposal st)
        hard?          (boolean (seq hard))]
    {:ok?            (and (not hard?) (not low?) (nil? score-mismatch))
     :violations     hard
     :confidence     conf
     :hard?          hard?
     :escalate?      (and (not hard?) (or low? (some? score-mismatch)))
     :score-mismatch score-mismatch}))

(defn hold-fact
  [request context verdict]
  {:t          :policy-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
