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
       distinct sibling business model, never folded into 5820).
    3. `consent-regimes` — the per-jurisdiction consent law the gate rests
       on, each entry carrying a `:provenance` URL and `:verbatim` quotes
       read out of the official primary source (govinfo.gov, EUR-Lex,
       laws-lois.justice.gc.ca, e-Gov). Until 2026-07-25 the three statutes
       above were NAMED in this docstring but carried no fetchable source
       and no quoted text, so nothing here was independently checkable;
       `consent-regimes` closes that, and adds Japan (an opt-in regime that
       CAN-SPAM's opt-out shape does not cover)."
  (:require [clojure.string :as str]))

(def consent-statuses
  "A contact's consent state. CAN-SPAM/GDPR/CASL do not recognize a
  'maybe': a contact either currently has a standing lawful basis to be
  contacted (`:opted-in`), has revoked that basis (`:opted-out`), or the
  consent basis has lapsed (`:expired`, e.g. a jurisdiction- or
  contract-defined validity window)."
  #{:opted-in :opted-out :expired})

(def consent-regimes
  "Per-jurisdiction consent law backing the ConsentGovernor's send gate.
  Every `:legal-basis` / `:provenance` / `:verbatim` below was read out of a
  directly-fetched official primary source on 2026-07-25 and re-grepped
  against the raw markup -- not recalled, and not taken from a fetch summary.

  `:regime` records the STRUCTURAL difference this actor must not flatten:
  the US is opt-out (a first message is lawful until the recipient objects),
  while the EU, Canada and Japan require a consent basis BEFORE the first
  message. `send-authorized?` implements the strictest reading -- prior
  `:opted-in` consent required everywhere -- which is deliberately stricter
  than CAN-SPAM rather than jurisdiction-switched."
  {"US"
   {:id "US"
    :name "United States (CAN-SPAM Act)"
    :regime :opt-out
    :requires-prior-consent? false
    :opt-out-honoring-deadline-business-days 10
    :legal-basis "CAN-SPAM Act, 15 U.S.C. §7704(a)(3)-(5) — (a)(4)(A) makes it unlawful to send more than 10 business days after an opt-out request; (a)(5) requires an advertisement identifier, opt-out notice and a valid physical postal address"
    :provenance "https://www.govinfo.gov/content/pkg/USCODE-2023-title15/html/USCODE-2023-title15-chap103-sec7704.htm"
    :verbatim
    {:opt-out "If a recipient makes a request using a mechanism provided pursuant to paragraph (3) not to receive some or any commercial electronic mail messages from such sender, then it is unlawful— (i) for the sender to initiate the transmission to the recipient, more than 10 business days after the receipt of such request, of a commercial electronic mail message that falls within the scope of the request"
     :identification "the message provides— (i) clear and conspicuous identification that the message is an advertisement or solicitation; (ii) clear and conspicuous notice of the opportunity under paragraph (3) to decline to receive further commercial electronic mail messages from the sender; and (iii) a valid physical postal address of the sender."}}

   "EU"
   {:id "EU"
    :name "European Union (GDPR)"
    :regime :opt-in
    :requires-prior-consent? true
    :opt-out-honoring-deadline-business-days 0 ;; withdrawal is effective at any time
    :legal-basis "Regulation (EU) 2016/679 (GDPR) Art. 6(1)(a) (consent as a lawful basis) and Art. 7(3) (withdrawal at any time, as easy to withdraw as to give)"
    :provenance "https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32016R0679"
    :verbatim
    {:lawful-basis "the data subject has given consent to the processing of his or her personal data for one or more specific purposes"
     :withdrawal "shall have the right to withdraw his or her consent at any time. The withdrawal of consent shall not affect the lawfulness of processing based on consent before its withdrawal. Prior to giving consent, the data subject shall be informed thereof. It shall be as easy to withdraw as to give consent."}}

   "CA"
   {:id "CA"
    :name "Canada (CASL, S.C. 2010, c. 23)"
    :regime :opt-in
    :requires-prior-consent? true
    :opt-out-honoring-deadline-business-days 10
    :legal-basis "Canada's Anti-Spam Legislation (CASL), S.C. 2010, c. 23, s. 6(1) (express or implied consent required before sending), s. 6(2)(c) + s. 11(1) (mandatory no-cost unsubscribe mechanism)"
    :provenance "https://laws-lois.justice.gc.ca/eng/acts/E-1.6/FullText.html"
    :verbatim
    {:consent "prohibited to send or cause or permit to be sent to an electronic address a commercial electronic message unless (a) the person to whom the message is sent has consented to receiving it, whether the consent is express or implied; and (b) the message complies with subsection (2)."
     :unsubscribe "unsubscribe mechanism referred to in paragraph 6(2)(c) must (a) enable the person to whom the commercial electronic message is sent to indicate, at no cost to them, the wish to no longer receive any commercial electronic messages, or any specified class of such messages, from the person who sent the message"}}

   "JP"
   {:id "JP"
    :name "日本（特定電子メールの送信の適正化等に関する法律）"
    :regime :opt-in
    :requires-prior-consent? true
    :opt-out-honoring-deadline-business-days 0
    :legal-basis "特定電子メールの送信の適正化等に関する法律（平成14年法律第26号、e-Gov law_id 414AC0100000026、2025-06-01 施行の改正版）第三条第1項（原則オプトイン）・第三条第3項（受信拒否通知後の送信禁止）"
    :provenance "https://laws.e-gov.go.jp/api/2/law_data/414AC0100000026"
    :verbatim
    {:opt-in "送信者は、次に掲げる者以外の者に対し、特定電子メールの送信をしてはならない。／一 あらかじめ、特定電子メールの送信をするように求める旨又は送信をすることに同意する旨を送信者又は送信委託者……に対し通知した者"
     :opt-out "送信者は、第一項各号に掲げる者から総務省令・内閣府令で定めるところにより特定電子メールの送信をしないように求める旨……の通知を受けたとき……は、その通知に示された意思に反して、特定電子メールの送信をしてはならない。"}}})

(defn consent-status-recognized? [status]
  (contains? consent-statuses status))

(defn consent-regime [jurisdiction-id]
  (get consent-regimes jurisdiction-id))

(defn regime-cited?
  "True only when `jurisdiction-id` carries a non-blank `:legal-basis`, a
  `:provenance` that is a real absolute http(s) URL, and at least one
  `:verbatim` quote. A named statute with no fetchable source does not count."
  [jurisdiction-id]
  (let [{:keys [legal-basis provenance verbatim]} (consent-regime jurisdiction-id)]
    (boolean (and (string? legal-basis) (seq legal-basis)
                  (string? provenance) (str/starts-with? provenance "http")
                  (map? verbatim) (seq verbatim)))))

(defn regime-requires-prior-consent?
  "Does `jurisdiction-id` require a consent basis BEFORE the first message?
  nil for an unknown jurisdiction -- never defaults to false, because
  defaulting to 'no prior consent needed' is the unsafe direction."
  [jurisdiction-id]
  (:requires-prior-consent? (consent-regime jurisdiction-id)))

(defn opt-out-honoring-deadline-business-days
  "How long a sender may still lawfully send after an opt-out request.
  0 means the objection is effective immediately (EU/JP). nil for unknown."
  [jurisdiction-id]
  (:opt-out-honoring-deadline-business-days (consent-regime jurisdiction-id)))

(defn regime-coverage
  "Honest citation coverage over `consent-regimes`."
  []
  (let [ids (keys consent-regimes)
        cited (filter regime-cited? ids)]
    {:jurisdictions (count consent-regimes)
     :cited (count cited)
     :cited-jurisdictions (vec (sort cited))
     :uncited-jurisdictions (vec (sort (remove regime-cited? ids)))
     :opt-in-jurisdictions (vec (sort (filter regime-requires-prior-consent? ids)))
     :opt-out-jurisdictions (vec (sort (remove regime-requires-prior-consent? ids)))
     :note (str "cloud-itonami-isic-6201: " (count cited) "/" (count consent-regimes)
                " consent regimes rest on a directly-fetched official source. "
                "send-authorized? applies the strictest reading (prior opted-in "
                "consent required everywhere), stricter than the US opt-out "
                "regime by design. Extend only from a real fetched source; "
                "never fabricate a legal-basis, provenance URL or quote.")}))

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
   :consent-regime-coverage (regime-coverage)
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
