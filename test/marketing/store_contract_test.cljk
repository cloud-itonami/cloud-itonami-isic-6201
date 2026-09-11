(ns marketing.store-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [marketing.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= :opted-in (:consent-status (store/contact s "contact-100"))))
      (is (= :opted-out (:consent-status (store/contact s "contact-200"))))
      (is (true? (:unsubscribed? (store/contact s "contact-400"))))
      (is (= :mql (:lifecycle-stage (store/contact s "contact-500"))))
      (is (= 39 (:lead-score (store/contact s "contact-500"))))
      (is (= :email (:channel (store/campaign s "camp-100"))))
      (is (true? (:sent? (store/send-record s "camp-200" "contact-600"))))
      (is (nil? (store/send-record s "camp-100" "contact-100")))
      (is (= 5 (count (store/engagement-history s "contact-500"))))
      (is (= [] (store/engagement-history s "contact-100")))
      (is (= 6 (count (store/all-contacts s))))
      (is (= 2 (count (store/all-campaigns s)))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "send-record upsert commits"
        (store/commit-record! s {:effect :send-record-upsert
                                 :value {:campaign-id "camp-100" :contact-id "contact-100"}})
        (is (true? (:sent? (store/send-record s "camp-100" "contact-100")))))
      (testing "stage-transition upsert commits"
        (store/commit-record! s {:effect :stage-transition-upsert
                                 :value {:contact-id "contact-100" :to-stage :mql}})
        (is (= :mql (:lifecycle-stage (store/contact s "contact-100")))))
      (testing "score-update upsert commits"
        (store/commit-record! s {:effect :score-update-upsert
                                 :value {:contact-id "contact-100" :score 15}})
        (is (= 15 (:lead-score (store/contact s "contact-100")))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (take-last 2 (store/ledger s)))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/contact s "nope")))
    (is (nil? (store/campaign s "nope")))
    (is (nil? (store/send-record s "camp-x" "contact-x")))
    (is (= [] (store/engagement-history s "contact-x")))
    (is (= [] (store/all-contacts s)))
    (is (= [] (store/all-campaigns s)))
    (is (= [] (store/ledger s)))))
