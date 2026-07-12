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
