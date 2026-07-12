(ns marketing.file-store-test
  "Fast, in-process regression coverage for `marketing.file-store`'s
  persistence contract: mutate a `FileStore`, then construct a BRAND NEW
  `FileStore` instance (a fresh record wrapping a fresh atom, not the
  same object — the closest thing to 'restart' reachable inside one
  JVM/test run) at the SAME path, and confirm the new instance sees the
  old instance's writes.

  This test does NOT itself kill and restart an OS process — it proves
  the snapshot-to-disk/load-from-disk logic is correct, not that a real
  `clojure -M:serve` process survives a real restart. That end-to-end
  claim was verified separately and manually (real `-main` process,
  real HTTP POSTs, real `kill`, real restart, real HTTP GETs) — see the
  commit/PR description for that transcript; it is not automated here
  because spawning a JVM subprocess per test run is slow and this file's
  job is fast regression coverage of the persistence LOGIC."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [marketing.store :as store]
            [marketing.file-store :as file-store])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-path
  "A filesystem path that does not yet exist -- `Files/createTempFile`
  actually creates the (empty) file to reserve a unique name, so it is
  deleted immediately; `marketing.file-store/file-store!` should treat a
  nonexistent path as 'seed fresh', not try to `edn/read-string` an
  empty file."
  []
  (let [f (Files/createTempFile "marketing-file-store-test" ".edn" (make-array FileAttribute 0))]
    (Files/delete f)
    (str f)))

(deftest fresh-path-seeds-demo-data-and-writes-it
  (let [path (temp-path)
        s (file-store/file-store! path)]
    (testing "seeded like seed-db"
      (is (= 6 (count (store/all-contacts s))))
      (is (= 2 (count (store/all-campaigns s))))
      (is (= :mql (:lifecycle-stage (store/contact s "contact-500"))))
      (is (:sent? (store/send-record s "camp-200" "contact-600"))))
    (testing "the seed was actually written to disk, not just held in memory"
      (let [on-disk (edn/read-string (slurp path))]
        (is (= 6 (count (:contacts on-disk))))
        (is (= [] (:ledger on-disk)))))))

(deftest writes-survive-a-fresh-instance-at-the-same-path
  (let [path (temp-path)
        s1 (file-store/file-store! path)]
    ;; Mutate through s1: a send, a stage transition, a score update, and
    ;; two ledger entries -- the same operations marketing.operation's
    ;; :commit node performs.
    (store/commit-record! s1 {:effect :send-record-upsert
                               :value {:campaign-id "camp-100" :contact-id "contact-100"}})
    (store/commit-record! s1 {:effect :stage-transition-upsert
                               :value {:contact-id "contact-100" :to-stage :mql}})
    (store/commit-record! s1 {:effect :score-update-upsert
                               :value {:contact-id "contact-100" :score 42}})
    (store/append-ledger! s1 {:op :a :disposition :commit})
    (store/append-ledger! s1 {:op :b :disposition :hold})

    ;; A BRAND NEW FileStore record/atom at the same path -- not s1, not
    ;; sharing any Clojure object with it. If this sees s1's writes, the
    ;; state genuinely round-tripped through the file on disk.
    (let [s2 (file-store/file-store! path)]
      (testing "send persisted"
        (is (:sent? (store/send-record s2 "camp-100" "contact-100"))))
      (testing "stage transition persisted"
        (is (= :mql (:lifecycle-stage (store/contact s2 "contact-100")))))
      (testing "score update persisted"
        (is (= 42 (:lead-score (store/contact s2 "contact-100")))))
      (testing "ledger persisted, order-preserving"
        (is (= [:commit :hold] (mapv :disposition (take-last 2 (store/ledger s2))))))
      (testing "unmodified fields also carried over"
        (is (= :opted-out (:consent-status (store/contact s2 "contact-200"))))))))

(deftest with-contacts-persists-and-is-visible-to-a-fresh-instance
  (let [path (temp-path)
        s1 (file-store/file-store! path)]
    (store/with-contacts s1 (assoc (into {} (map (juxt :id identity) (store/all-contacts s1)))
                                    "contact-new" {:id "contact-new" :name "New Lead"
                                                    :consent-status :opted-in
                                                    :unsubscribed? false
                                                    :lifecycle-stage :subscriber :lead-score 0}))
    (let [s2 (file-store/file-store! path)]
      (is (some? (store/contact s2 "contact-new")))
      (is (= "New Lead" (:name (store/contact s2 "contact-new")))))))

(deftest with-engagement-persists-and-is-visible-to-a-fresh-instance
  (let [path (temp-path)
        s1 (file-store/file-store! path)]
    (store/with-engagement s1 (assoc (into {} (map (fn [c] [(:id c) (store/engagement-history s1 (:id c))])
                                                    (store/all-contacts s1)))
                                      "contact-100" [{:kind :email-open} {:kind :email-click}]))
    (let [s2 (file-store/file-store! path)]
      (is (= [{:kind :email-open} {:kind :email-click}]
             (store/engagement-history s2 "contact-100"))))))
