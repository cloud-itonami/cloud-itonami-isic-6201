(ns marketing.dashboard-test
  "Real coverage for the actor's first aggregate-view capability: a
  multi-contact fixture spanning several lifecycle stages (including an
  :unsubscribed exit), several campaign sends run through the REAL
  OperationActor (a mix of authorized and consent-rejected outcomes, so
  the ledger facts dashboard.cljc reads are genuine governor output, not
  hand-fabricated), and varied engagement histories including one
  deliberately stale stored :lead-score."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [marketing.store :as store]
            [marketing.operation :as op]
            [marketing.dashboard :as dashboard]))

(def marketer {:actor-id "mktr-1" :actor-role :marketer :phase 3})

(defn- exec-op! [actor tid request]
  (g/run* actor {:request request :context marketer} {:thread-id tid}))

(defn- fixture-contacts []
  {"c-sub-1"  {:id "c-sub-1"  :name "sub1"  :consent-status :opted-in  :unsubscribed? false :lifecycle-stage :subscriber :lead-score 1}
   "c-sub-2"  {:id "c-sub-2"  :name "sub2"  :consent-status :opted-out :unsubscribed? false :lifecycle-stage :subscriber :lead-score 0}
   "c-lead-1" {:id "c-lead-1" :name "lead1" :consent-status :opted-in  :unsubscribed? false :lifecycle-stage :lead       :lead-score 4}
   "c-lead-2" {:id "c-lead-2" :name "lead2" :consent-status :opted-in  :unsubscribed? true  :lifecycle-stage :lead       :lead-score 0}
   "c-mql-1"  {:id "c-mql-1"  :name "mql1"  :consent-status :opted-in  :unsubscribed? false :lifecycle-stage :mql        :lead-score 9}
   "c-sql-1"  {:id "c-sql-1"  :name "sql1"  :consent-status :opted-in  :unsubscribed? false :lifecycle-stage :sql        :lead-score 19}
   "c-cust-1" {:id "c-cust-1" :name "cust1" :consent-status :opted-in  :unsubscribed? false :lifecycle-stage :customer   :lead-score 39}
   "c-exit-1" {:id "c-exit-1" :name "exit1" :consent-status :opted-out :unsubscribed? true  :lifecycle-stage :unsubscribed :lead-score 5}})

(defn- fixture-campaigns []
  {"camp-a" {:id "camp-a" :name "Campaign A" :channel :email}
   "camp-b" {:id "camp-b" :name "Campaign B" :channel :email}})

(defn- fixture-engagement []
  {"c-sub-1"  [{:kind :email-open}]
   "c-lead-1" [{:kind :email-open} {:kind :email-click}]
   "c-mql-1"  [{:kind :email-open} {:kind :email-click} {:kind :pricing-page-view}]
   "c-sql-1"  [{:kind :email-open} {:kind :email-click} {:kind :pricing-page-view} {:kind :form-fill}]
   "c-cust-1" [{:kind :email-open} {:kind :email-click} {:kind :pricing-page-view} {:kind :form-fill} {:kind :demo-request}]
   ;; deliberately stale: engagement recomputes to 1, stored :lead-score is 5
   "c-exit-1" [{:kind :email-open}]})

(defn- fresh-store []
  (-> (store/->MemStore (atom {:ledger []}))
      (store/with-contacts (fixture-contacts))
      (store/with-campaigns (fixture-campaigns))
      (store/with-engagement (fixture-engagement))))

(defn- fixture-store!
  "Builds the store AND runs real send proposals through the actual
  OperationActor so `:sends` and `:ledger` reflect genuine
  ConsentGovernor decisions, never hand-fabricated facts.

    camp-a -> c-sub-1  (opted-in)              => commit
    camp-a -> c-sub-2  (opted-out)              => consent-revoked hold
    camp-a -> c-lead-2 (unsubscribed? flag)     => consent-revoked hold
    camp-b -> c-lead-1 (opted-in)               => commit
    camp-b -> c-exit-1 (opted-out+unsubscribed) => consent-revoked hold"
  []
  (let [db    (fresh-store)
        actor (op/build db)
        send! (fn [tid campaign-id contact-id]
                (exec-op! actor tid
                          {:op :campaign/send-message :subject contact-id
                           :campaign-id campaign-id :contact-id contact-id}))]
    (send! "d1" "camp-a" "c-sub-1")
    (send! "d2" "camp-a" "c-sub-2")
    (send! "d3" "camp-a" "c-lead-2")
    (send! "d4" "camp-b" "c-lead-1")
    (send! "d5" "camp-b" "c-exit-1")
    db))

(deftest lifecycle-funnel-stage-counts-and-reached-counts
  (let [db (fixture-store!)
        {:keys [stage-counts reached-counts]} (dashboard/lifecycle-funnel db)]
    (testing "stage-counts: snapshot distribution, zero-filled ordered stages + exit stage present"
      (is (= {:subscriber 2 :lead 2 :mql 1 :sql 1 :customer 1 :unsubscribed 1}
             stage-counts)))
    (testing "reached-counts: :unsubscribed contact excluded (no :reached-stage fact)"
      (is (= {:subscriber 7 :lead 5 :mql 3 :sql 2 :customer 1}
             reached-counts)))))

(deftest conversion-rates-match-hand-computed-ratios
  (let [db (fixture-store!)
        cr (dashboard/conversion-rates db)]
    (is (== (double (/ 5 7)) (get cr [:subscriber :lead])))
    (is (== (double (/ 3 5)) (get cr [:lead :mql])))
    (is (== (double (/ 2 3)) (get cr [:mql :sql])))
    (is (== (double (/ 1 2)) (get cr [:sql :customer])))))

(deftest campaign-rollup-counts-sends-and-consent-rejections-per-campaign
  (let [db (fixture-store!)
        rollup (dashboard/campaign-rollup db)]
    (is (= {"camp-a" {:sent 1 :consent-rejected 2}
            "camp-b" {:sent 1 :consent-rejected 1}}
           rollup))))

(deftest campaign-rollup-zero-fills-a-campaign-with-no-activity
  (let [db (-> (fresh-store)
               (store/with-campaigns (assoc (fixture-campaigns)
                                             "camp-c" {:id "camp-c" :name "Idle" :channel :email})))
        rollup (dashboard/campaign-rollup db)]
    (is (= {:sent 0 :consent-rejected 0} (get rollup "camp-c")))))

(deftest lead-score-distribution-recomputes-ground-truth-never-trusts-stored-score
  (let [db (fixture-store!)
        dist (dashboard/lead-score-distribution db)]
    (testing "by-score frequency over RECOMPUTED scores (c-exit-1 recomputes to 1, not its stored 5)"
      (is (= {0 2 1 2 4 1 9 1 19 1 39 1} (:by-score dist))))
    (testing "summary stats over the recomputed scores [0 0 1 1 4 9 19 39]"
      (is (= 8 (:count dist)))
      (is (= 0 (:min dist)))
      (is (= 39 (:max dist)))
      (is (== 9.125 (:mean dist)))
      (is (== 2.5 (:median dist))))
    (testing "stale detection: only c-exit-1's stored score (5) disagrees with its recompute (1)"
      (is (= 1 (:stale-count dist)))
      (is (= ["c-exit-1"] (:stale-contacts dist))))
    (testing "buckets: 10-wide histogram over recomputed scores [0 0 1 1 4 9 19 39]"
      (is (= {0 6 10 1 30 1} (:buckets dist)))
      (is (= 8 (reduce + (vals (:buckets dist))))))))

(deftest empty-store-never-crashes-and-never-fabricates-stats
  (let [empty-db (store/->MemStore (atom {:contacts {} :campaigns {} :sends {} :engagement {} :ledger []}))
        dist (dashboard/lead-score-distribution empty-db)
        funnel (dashboard/lifecycle-funnel empty-db)]
    (is (= 0 (:count dist)))
    (is (nil? (:min dist)))
    (is (nil? (:max dist)))
    (is (nil? (:mean dist)))
    (is (nil? (:median dist)))
    (is (= {} (:by-score dist)))
    (is (= {:subscriber 0 :lead 0 :mql 0 :sql 0 :customer 0} (:stage-counts funnel)))))

(deftest snapshot-rbac-gate-follows-policy-permissions
  (let [db (fixture-store!)]
    (testing "marketer and marketing-manager are both authorized (same precedent as their write-op scopes)"
      (is (true? (dashboard/authorized? :marketer)))
      (is (true? (dashboard/authorized? :marketing-manager)))
      (is (true? (:authorized? (dashboard/snapshot db {:actor-role :marketer})))))
    (testing "an unlisted/guest role is fail-closed denied, never partial data"
      (is (false? (dashboard/authorized? :guest)))
      (let [res (dashboard/snapshot db {:actor-role :guest})]
        (is (= {:authorized? false :reason :rbac} res))))
    (testing "snapshot bundles all four views when authorized"
      (let [res (dashboard/snapshot db {:actor-role :marketer})]
        (is (contains? res :lifecycle-funnel))
        (is (contains? res :conversion-rates))
        (is (contains? res :campaign-rollup))
        (is (contains? res :lead-score-distribution))))))
