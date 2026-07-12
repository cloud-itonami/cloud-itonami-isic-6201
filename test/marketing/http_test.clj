(ns marketing.http-test
  "Exercises `marketing.http` as a REAL running process — starts the
  actual http-kit server on an ephemeral port inside the test JVM and
  makes REAL HTTP requests against it with `java.net.http` (JDK
  built-in, zero extra test dependency — same client
  `cloud-itonami-isic-5820`'s `crm.http-test` already uses). No mocked
  handler, no in-process Ring `handler` invocation shortcut.

  Scenarios reuse the exact governed cases `marketing.sim`/
  `marketing.policy-contract-test` already exercise (op1: clean send ->
  commit; op2: opted-out contact -> consent-revoked-send-gate hold; op6:
  stage-skip -> stage-sequence-gate hold; op7: clean stage advance ->
  commit; op8: matching lead-score update -> commit; op9: mismatched
  lead-score update -> escalate) rather than inventing new ones."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [org.httpkit.server :as httpkit]
            [marketing.http :as http]
            [marketing.store :as store])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

;; Test-only token, set explicitly for this process — never a real
;; secret, never committed anywhere as a default/fallback in
;; marketing.http itself (marketing.http has NO built-in token; this is
;; the value THIS test happens to pass to `start-server!`).
(def ^:private test-token "http-test-only-token-3c8e1a92")

(def ^:private client (HttpClient/newHttpClient))

(def ^:private ^:dynamic *base-url* nil)
(def ^:private server (atom nil))

(defn- with-server [f]
  (let [srv (http/start-server! {:store (store/seed-db) :port 0 :token test-token})]
    (reset! server srv)
    (try
      (binding [*base-url* (str "http://127.0.0.1:" (httpkit/server-port srv))]
        (f))
      (finally
        @(httpkit/server-stop! srv)
        (reset! server nil)))))

(use-fixtures :once with-server)

;; ───────────────────────── HTTP helpers ─────────────────────────

(defn- req!
  [method path {:keys [body headers]}]
  (let [b (HttpRequest/newBuilder (URI/create (str *base-url* path)))]
    (doseq [[k v] headers] (.header b k v))
    (case method
      :get  (.GET b)
      :post (do (.header b "Content-Type" "application/json")
                (.POST b (HttpRequest$BodyPublishers/ofString (or body "")))))
    (let [resp (.send client (.build b) (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode resp)
       :body   (.body resp)})))

(defn- json-req!
  ([method path opts] (json-req! method path opts nil))
  ([method path opts token]
   (let [headers (cond-> (:headers opts {})
                   token (assoc "Authorization" (str "Bearer " token)))
         resp (req! method path (assoc opts :headers headers))]
     (assoc resp :json (when (seq (:body resp))
                         (try (json/read-str (:body resp) :key-fn keyword)
                              (catch Exception _ ::unparseable)))))))

;; ───────────────────────── /health, / ─────────────────────────

(deftest health-check-no-auth-required
  (let [{:keys [status json]} (json-req! :get "/health" {})]
    (is (= 200 status))
    (is (= "ok" (:status json)))))

(deftest root-info-no-auth-required
  (let [{:keys [status json]} (json-req! :get "/" {})]
    (is (= 200 status))
    (is (= "cloud-itonami-isic-6201" (:actor json)))
    (is (= "6201" (:isic-code json)))))

;; ───────────────────────── auth: 401 without token ─────────────────────

(def ^:private marketer-context
  {:actor-id "mktr-1" :actor-role "marketer" :phase 3})

(def ^:private manager-context
  {:actor-id "mgr-1" :actor-role "marketing-manager" :phase 3})

(def ^:private send-body
  (json/write-str
   {:op "campaign/send-message" :campaign-id "camp-100" :contact-id "contact-100"
    :context marketer-context}))

(def ^:private advance-stage-body
  (json/write-str
   {:contact-id "contact-100" :to-stage "mql" :context marketer-context}))

(def ^:private update-score-body
  (json/write-str
   {:contact-id "contact-500" :score 39 :context manager-context}))

(deftest send-without-auth-token-is-unauthorized
  (let [{:keys [status json]} (json-req! :post "/send" {:body send-body})]
    (is (= 401 status))
    (is (= "unauthorized" (:error json)))))

(deftest advance-stage-without-auth-token-is-unauthorized
  (let [{:keys [status json]} (json-req! :post "/advance-stage" {:body advance-stage-body})]
    (is (= 401 status))
    (is (= "unauthorized" (:error json)))))

(deftest update-score-without-auth-token-is-unauthorized
  (let [{:keys [status json]} (json-req! :post "/update-score" {:body update-score-body})]
    (is (= 401 status))
    (is (= "unauthorized" (:error json)))))

(deftest dashboard-without-auth-token-is-unauthorized
  (let [{:keys [status]} (json-req! :get "/dashboard?role=marketer" {})]
    (is (= 401 status))))

;; ───────────────────────── /send ─────────────────────────

(deftest send-with-valid-token-and-clean-target-commits
  (testing "op1 from marketing.sim: camp-100 -> contact-100 (opted-in) -> commit"
    (let [{:keys [status json]} (json-req! :post "/send" {:body send-body} test-token)]
      (is (= 200 status))
      (is (= "committed" (:decision json)))
      (is (= "campaign/send-message" (:op json)))
      (is (= "contact-100" (:subject json))))))

(deftest send-to-opted-out-contact-is-held-by-consent-revoked-gate
  (testing "op2 from marketing.sim: camp-100 -> contact-200 (opted-out) -> consent-revoked-send-gate REJECT"
    (let [body (json/write-str
                {:op "campaign/send-message" :campaign-id "camp-100" :contact-id "contact-200"
                 :context marketer-context})
          {:keys [status json]} (json-req! :post "/send" {:body body} test-token)]
      (is (= 200 status))
      (is (= "held" (:decision json)))
      (is (some #(= "consent-revoked-send-gate" (:rule %)) (:violations json))))))

;; ───────────────────────── /advance-stage ─────────────────────────

(deftest advance-stage-with-valid-token-and-clean-transition-commits
  (testing "op7 from marketing.sim: contact-100 :lead -> :mql -> commit"
    (let [{:keys [status json]} (json-req! :post "/advance-stage" {:body advance-stage-body} test-token)]
      (is (= 200 status))
      (is (= "committed" (:decision json)))
      (is (= "lead/advance-stage" (:op json))))))

(deftest advance-stage-skip-ahead-is-held-by-stage-sequence-gate
  (testing "op6 shape from marketing.sim, on contact-400 (seeded :lead, distinct from
           the contact-100 commit test above so test order never matters):
           :lead -> :customer (skip) -> stage-sequence-gate REJECT"
    (let [body (json/write-str {:contact-id "contact-400" :to-stage "customer"
                                 :context marketer-context})
          {:keys [status json]} (json-req! :post "/advance-stage" {:body body} test-token)]
      (is (= 200 status))
      (is (= "held" (:decision json)))
      (is (some #(= "stage-sequence-gate" (:rule %)) (:violations json))))))

;; ───────────────────────── /update-score ─────────────────────────

(deftest update-score-matching-recompute-commits
  (testing "op8 from marketing.sim: contact-500 -> 39 (matches engagement-history recompute) -> commit"
    (let [{:keys [status json]} (json-req! :post "/update-score" {:body update-score-body} test-token)]
      (is (= 200 status))
      (is (= "committed" (:decision json)))
      (is (= "lead/update-score" (:op json))))))

(deftest update-score-mismatch-escalates
  (testing "op9 from marketing.sim: contact-500 -> 90 (disagrees with engagement-history recompute) -> escalate"
    (let [body (json/write-str {:contact-id "contact-500" :score 90 :context manager-context})
          {:keys [status json]} (json-req! :post "/update-score" {:body body} test-token)]
      (is (= 202 status))
      (is (= "escalated" (:decision json)))
      (is (= "lead-score-mismatch" (:reason json)))
      (is (some? (:thread-id json))))))

;; ───────────────────────── /dashboard ─────────────────────────

(deftest dashboard-without-right-role-is-forbidden
  (let [{:keys [status json]}
        (json-req! :get "/dashboard?role=guest" {} test-token)]
    (is (= 403 status))
    (is (= "forbidden" (:error json)))
    (is (= "rbac" (:reason json)))))

(deftest dashboard-with-marketer-returns-real-data
  (let [{:keys [status json]}
        (json-req! :get "/dashboard?role=marketer" {} test-token)]
    (is (= 200 status))
    (is (contains? json :lifecycle-funnel))
    (is (contains? json :conversion-rates))
    (is (contains? json :campaign-rollup))
    (is (contains? json :lead-score-distribution))))
