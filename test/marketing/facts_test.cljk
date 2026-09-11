(ns marketing.facts-test
  (:require [clojure.test :refer [deftest is]]
            [marketing.facts :as facts]))

(deftest consent-status-recognized?-rejects-unlisted-statuses
  (is (facts/consent-status-recognized? :opted-in))
  (is (facts/consent-status-recognized? :opted-out))
  (is (facts/consent-status-recognized? :expired))
  (is (not (facts/consent-status-recognized? :subscribed)))
  (is (not (facts/consent-status-recognized? nil))))

(deftest send-authorized?-requires-opted-in-and-not-unsubscribed
  (is (facts/send-authorized? {:consent-status :opted-in :unsubscribed? false}))
  (is (not (facts/send-authorized? {:consent-status :opted-out :unsubscribed? false})))
  (is (not (facts/send-authorized? {:consent-status :expired :unsubscribed? false})))
  (is (not (facts/send-authorized? {:consent-status :opted-in :unsubscribed? true}))
      "an independent unsubscribe/suppression flag blocks a send even when opted-in")
  (is (not (facts/send-authorized? {:consent-status :opted-out :unsubscribed? true}))))

(deftest coverage-is-honest-not-aspirational
  (let [c (facts/coverage)]
    (is (= 3 (count (:consent-statuses c))) "3 consent statuses")
    (is (= 5 (:lifecycle-stage-count c)))
    (is (= 3 (:exit-stage-count c)))))

;; ───────── Verified consent-regime citations (2026-07-25) ─────────

(deftest every-consent-regime-rests-on-a-fetched-primary-source
  (doseq [id (keys facts/consent-regimes)]
    (is (facts/regime-cited? id)
        (str id " must carry a legal-basis, an http(s) provenance URL and verbatim text")))
  (is (not (facts/regime-cited? "XX")) "an unknown jurisdiction is never treated as cited")
  (is (nil? (facts/consent-regime "XX"))))

(deftest regime-coverage-separates-opt-in-from-opt-out
  (let [c (facts/regime-coverage)]
    (is (= 4 (:jurisdictions c)))
    (is (= 4 (:cited c)))
    (is (= [] (:uncited-jurisdictions c)))
    (is (= ["CA" "EU" "JP"] (:opt-in-jurisdictions c))
        "EU/Canada/Japan require a consent basis before the first message")
    (is (= ["US"] (:opt-out-jurisdictions c))
        "CAN-SPAM is opt-out: a first message is lawful until the recipient objects")))

(deftest prior-consent-is-never-defaulted-to-false-for-unknown-jurisdictions
  (is (nil? (facts/regime-requires-prior-consent? "XX"))
      "defaulting an unknown jurisdiction to 'no prior consent needed' is the unsafe direction")
  (is (true? (facts/regime-requires-prior-consent? "JP")))
  (is (false? (facts/regime-requires-prior-consent? "US"))))

(deftest opt-out-deadlines-match-the-statutes
  (is (= 10 (facts/opt-out-honoring-deadline-business-days "US"))
      "15 U.S.C. 7704(a)(4)(A)(i): unlawful more than 10 business days after the request")
  (is (= 0 (facts/opt-out-honoring-deadline-business-days "JP"))
      "特定電子メール法 第三条第3項: 通知を受けたときは送信してはならない")
  (is (= 0 (facts/opt-out-honoring-deadline-business-days "EU"))
      "GDPR Art. 7(3): withdrawal at any time")
  (is (nil? (facts/opt-out-honoring-deadline-business-days "XX"))))

(deftest send-gate-is-stricter-than-can-spam-by-design
  ;; CAN-SPAM would permit a first send to a contact who has never opted in.
  ;; This actor refuses it in every jurisdiction, including the US.
  (is (false? (facts/regime-requires-prior-consent? "US")))
  (is (not (facts/send-authorized? {:consent-status :expired :unsubscribed? false}))
      "no standing consent basis blocks the send even where the statute is opt-out")
  (is (not (facts/send-authorized? {:unsubscribed? false}))
      "absent consent-status blocks the send"))

(deftest coverage-reports-regime-citations
  (let [c (facts/coverage)]
    (is (= 4 (-> c :consent-regime-coverage :cited)))))
