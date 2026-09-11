(ns marketing.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [marketing.store :as store]
            [marketing.operation :as op]))

(def marketer {:actor-id "mktr-1" :actor-role :marketer})
(def manager  {:actor-id "mgr-1" :actor-role :marketing-manager})

(def clean-send
  {:op :campaign/send-message :subject "contact-100"
   :campaign-id "camp-100" :contact-id "contact-100"})

(def clean-stage-advance
  {:op :lead/advance-stage :subject "contact-100"
   :contact-id "contact-100" :to-stage :mql})

(def clean-score-update
  {:op :lead/update-score :subject "contact-500"
   :contact-id "contact-500" :score 39})

(defn- run [phase req ctx]
  (let [s (store/seed-db)
        actor (op/build s)]
    [s (g/run* actor {:request req :context (assoc ctx :phase phase)}
               {:thread-id (str "ph-" phase "-" (:op req) "-" (:contact-id req))})]))

(deftest phase0-holds-all-writes
  (let [[s res] (run 0 clean-send marketer)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= :phase-disabled (-> (store/ledger s) first :phase-reason)))))

(deftest phase1-forces-approval-on-clean-send
  (let [[_ res] (run 1 clean-send marketer)]
    (is (= :interrupted (:status res)))
    (is (= :phase-approval (-> res :state :audit last :reason)))))

(deftest phase1-holds-stage-advance-not-yet-enabled
  (let [[s res] (run 1 clean-stage-advance marketer)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= :phase-disabled (-> (store/ledger s) first :phase-reason)))))

(deftest phase2-forces-approval-on-stage-advance-and-score-update
  (let [[_ res1] (run 2 clean-stage-advance marketer)]
    (is (= :interrupted (:status res1))))
  (let [[_ res2] (run 2 clean-score-update manager)]
    (is (= :interrupted (:status res2)))))

(deftest phase3-auto-commits-clean-send
  (let [[s res] (run 3 clean-send marketer)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (true? (:sent? (store/send-record s "camp-100" "contact-100"))))))

(deftest phase3-auto-commits-clean-stage-advance
  (let [[s res] (run 3 clean-stage-advance marketer)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :mql (:lifecycle-stage (store/contact s "contact-100"))))))

(deftest governor-hold-beats-phase
  (testing "a hard governor violation (revoked consent) holds even in the most permissive phase"
    (let [[_ res] (run 3 {:op :campaign/send-message :subject "contact-200"
                          :campaign-id "camp-100" :contact-id "contact-200"}
                       marketer)]
      (is (= :hold (get-in res [:state :disposition]))))))

(deftest lead-score-mismatch-always-escalates-at-any-phase-where-the-op-is-enabled
  (testing "phase 1 doesn't yet enable :lead/update-score at all, so it holds (phase-disabled) rather than escalating"
    (let [[s res] (run 1 {:op :lead/update-score :subject "contact-500"
                          :contact-id "contact-500" :score 90}
                       manager)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (= :phase-disabled (-> (store/ledger s) first :phase-reason)))))
  (doseq [ph [2 3]]
    (let [[_ res] (run ph {:op :lead/update-score :subject "contact-500"
                           :contact-id "contact-500" :score 90}
                       manager)]
      (is (= :interrupted (:status res))
          (str "phase " ph " must escalate a lead-score mismatch"))
      (is (= :lead-score-mismatch (-> res :state :audit last :reason))))))
