(ns marketing.policy-contract-test
  "The governor contract as executable tests. Single invariant under
  test: MarketingOps-LLM never sends/advances/updates a record the
  ConsentGovernor would reject."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [marketing.store :as store]
            [marketing.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def marketer {:actor-id "mktr-1" :actor-role :marketer :phase 3})
(def manager  {:actor-id "mgr-1" :actor-role :marketing-manager :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(deftest authorized-send-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :campaign/send-message :subject "contact-100"
                   :campaign-id "camp-100" :contact-id "contact-100"}
                  marketer)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (true? (:sent? (store/send-record db "camp-100" "contact-100"))))
    (is (= 1 (count (store/ledger db))))))

(deftest unauthorized-role-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t2"
                  {:op :campaign/send-message :subject "contact-100"
                   :campaign-id "camp-100" :contact-id "contact-100"}
                  {:actor-id "guest-1" :actor-role :guest :phase 3})]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= [:rbac] (-> (store/ledger db) first :basis)))))

(deftest opted-out-contact-send-is-held
  (testing "revoked consent (:opted-out) blocks a send — CAN-SPAM/GDPR/CASL"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :campaign/send-message :subject "contact-200"
                     :campaign-id "camp-100" :contact-id "contact-200"}
                    marketer)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:consent-revoked-send-gate} (-> (store/ledger db) first :basis)))
      (is (nil? (store/send-record db "camp-100" "contact-200"))))))

(deftest expired-consent-contact-send-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t4"
                  {:op :campaign/send-message :subject "contact-300"
                   :campaign-id "camp-100" :contact-id "contact-300"}
                  marketer)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:consent-revoked-send-gate} (-> (store/ledger db) first :basis)))))

(deftest unsubscribed-flag-blocks-send-even-when-opted-in
  (testing "the :unsubscribed? suppression flag is an INDEPENDENT fact from :consent-status"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :campaign/send-message :subject "contact-400"
                     :campaign-id "camp-100" :contact-id "contact-400"}
                    marketer)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:consent-revoked-send-gate} (-> (store/ledger db) first :basis))))))

(deftest unknown-contact-send-is-held-fail-closed
  (let [[db actor] (fresh)
        res (exec-op actor "t5b"
                  {:op :campaign/send-message :subject "contact-ghost"
                   :campaign-id "camp-100" :contact-id "contact-ghost"}
                  marketer)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:consent-revoked-send-gate} (-> (store/ledger db) first :basis)))))

(deftest double-send-violation-is-held
  (testing "a second send proposal for an already-sent campaign/contact pair → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t6"
                    {:op :campaign/send-message :subject "contact-600"
                     :campaign-id "camp-200" :contact-id "contact-600"}
                    marketer)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:double-send-gate} (-> (store/ledger db) first :basis))))))

(deftest double-send-violation-detected-after-a-real-first-send
  (let [[db actor] (fresh)
        r1 (exec-op actor "t7a"
                 {:op :campaign/send-message :subject "contact-100"
                  :campaign-id "camp-100" :contact-id "contact-100"}
                 marketer)
        _ (is (= :commit (get-in r1 [:state :disposition])))
        r2 (exec-op actor "t7b"
                 {:op :campaign/send-message :subject "contact-100"
                  :campaign-id "camp-100" :contact-id "contact-100"}
                 marketer)]
    (is (= :hold (get-in r2 [:state :disposition])))
    (is (some #{:double-send-gate} (-> (store/ledger db) last :basis)))))

(deftest stage-sequence-violation-is-held
  (testing "skipping ahead in the lifecycle (lead straight to customer) → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t8"
                    {:op :lead/advance-stage :subject "contact-100"
                     :contact-id "contact-100" :to-stage :customer}
                    marketer)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:stage-sequence-gate} (-> (store/ledger db) first :basis)))
      (is (= :lead (:lifecycle-stage (store/contact db "contact-100")))))))

(deftest clean-stage-advance-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t9"
                  {:op :lead/advance-stage :subject "contact-100"
                   :contact-id "contact-100" :to-stage :mql}
                  marketer)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :mql (:lifecycle-stage (store/contact db "contact-100"))))))

(deftest lead-score-matching-recompute-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t10"
                  {:op :lead/update-score :subject "contact-500"
                   :contact-id "contact-500" :score 39}
                  manager)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 39 (:lead-score (store/contact db "contact-500"))))))

(deftest lead-score-mismatch-escalates-then-human-decides
  (testing "a proposed score that disagrees with the engagement-history recompute interrupts for human approval"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t11"
                   {:op :lead/update-score :subject "contact-500"
                    :contact-id "contact-500" :score 90}
                   manager)]
      (is (= :interrupted (:status r1)))
      (is (= :lead-score-mismatch (-> r1 :state :audit last :reason)))
      (let [r2 (g/run* actor {:approval {:status :approved :by "mktg-manager-1"}}
                       {:thread-id "t11" :resume? true})]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 90 (:lead-score (store/contact db "contact-500"))))))))

(deftest lead-score-mismatch-rejected-by-human-holds
  (let [[db actor] (fresh)
        r1 (exec-op actor "t12"
                 {:op :lead/update-score :subject "contact-500"
                  :contact-id "contact-500" :score 90}
                 manager)
        r2 (g/run* actor {:approval {:status :rejected :by "mktg-manager-1"}}
                   {:thread-id "t12" :resume? true})]
    (is (= :interrupted (:status r1)))
    (is (= :hold (get-in r2 [:state :disposition])))
    (is (= 39 (:lead-score (store/contact db "contact-500"))))))
