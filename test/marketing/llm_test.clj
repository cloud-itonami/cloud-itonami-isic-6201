(ns marketing.llm-test
  (:require [clojure.test :refer [deftest is]]
            [marketing.store :as store]
            [marketing.llm :as llm]))

(deftest send-proposal-carries-campaign-and-contact
  (let [db (store/seed-db)
        p (llm/infer db {:op :campaign/send-message :subject "contact-100"
                         :campaign-id "camp-100" :contact-id "contact-100"})]
    (is (= :send-record-upsert (:effect p)))
    (is (= {:campaign-id "camp-100" :contact-id "contact-100"} (:value p)))
    (is (nil? (:source p)))
    (is (>= (:confidence p) 0.9))))

(deftest stage-advance-proposal-carries-to-stage
  (let [db (store/seed-db)
        p (llm/infer db {:op :lead/advance-stage :subject "contact-100"
                         :contact-id "contact-100" :to-stage :mql})]
    (is (= :stage-transition-upsert (:effect p)))
    (is (= :mql (get-in p [:value :to-stage])))))

(deftest score-update-proposal-carries-score
  (let [db (store/seed-db)
        p (llm/infer db {:op :lead/update-score :subject "contact-500"
                         :contact-id "contact-500" :score 39})]
    (is (= :score-update-upsert (:effect p)))
    (is (= 39 (get-in p [:value :score])))))

(deftest unrecognized-op-is-noop
  (let [db (store/seed-db)
        p (llm/infer db {:op :unknown/thing :subject "x"})]
    (is (= :noop (:effect p)))
    (is (zero? (:confidence p)))))
