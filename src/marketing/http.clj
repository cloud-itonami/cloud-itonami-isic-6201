(ns marketing.http
  "Minimal, real HTTP service layer over the existing `marketing.operation` /
  `marketing.policy` / `marketing.dashboard` actor graph — the first step
  toward running this actor as a live, network-callable process instead
  of a Clojure library invoked only via `clojure -M:dev:run` or test
  code.

  JVM-only (`.clj`, not `.cljc`) is the correct choice here per this
  fleet's runtime-priority convention (CLAUDE.md's `.cljc`/`.kotoba`
  section): there is no portable 'bind a TCP socket and run an HTTP
  server' primitive at the kotoba-wasm/clojurewasm/cljs/nbb level, and
  this file is infrastructure glue (a server binding) over app logic
  that already lives in portable `.cljc` (`marketing.operation`,
  `marketing.policy`, `marketing.dashboard`, …) — it reimplements NONE of
  that logic, it only adapts HTTP requests/responses onto it. Mirrors
  `cloud-itonami-isic-5820`'s `crm.http` (same http-kit/ring-core/
  data.json dependency choices, same fail-closed bearer-token auth
  design, same namespaced-keyword JSON handling approach).

  Endpoints (full shapes in docs/api.md):
    GET  /               no auth  — actor info + links
    GET  /health          no auth — liveness (+ store reachability)
    POST /send            auth    — thin adapter over `marketing.operation/
                                     build`'s compiled StateGraph for
                                     `:campaign/send-message` (advise ->
                                     govern -> decide -> commit/hold/
                                     escalate)
    POST /advance-stage   auth    — same pattern for `:lead/advance-stage`
    POST /update-score    auth    — same pattern for `:lead/update-score`
    GET  /dashboard        auth   — calls `marketing.dashboard/snapshot`
                                     directly, passing the caller's role
                                     through; the dashboard's OWN
                                     `{:authorized? false :reason :rbac}`
                                     gate is never bypassed or reimplemented
                                     here, it is surfaced as HTTP 403

  Auth: bearer token (`Authorization: Bearer <token>`) compared against a
  token supplied at server-start time (from `$ISIC6201_API_TOKEN` when
  started via `-main`/`clojure -M:serve`). FAIL CLOSED: `start-server!`
  refuses to start at all if given a blank/nil token, and `-main` exits 1
  without starting anything if the env var is unset — there is no 'runs
  with auth disabled' path. See docs/api.md for the full contract, and
  its explicit honest-scope statement (single-process, single-tenant, no
  TLS termination, no rate limiting).

  MarketingOps-LLM advisor selection (see `resolve-advisor!`, `marketing.
  llm-realmodel`): defaults to `marketing.llm/mock-advisor` (the sealed/
  deterministic advisor `marketing.operation/build` itself already
  defaults to) unless `$ISIC6201_MODEL_API_KEY` is set and non-blank, in
  which case it wires `marketing.llm-realmodel/real-advisor` — a real
  OpenAI-compatible/Anthropic HTTP model call — instead. Either way,
  `resolve-advisor!` prints which mode it picked (and `marketing.llm-
  realmodel/preflight`'s config, minus the key value) at server start,
  the same fail-visible discipline `warn-ephemeral-store!` already
  established for storage. Mirrors `cloud-itonami-isic-5820`'s `crm.http/
  resolve-advisor!`."
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [org.httpkit.server :as httpkit]
            [ring.middleware.params :refer [wrap-params]]
            [langgraph.graph :as g]
            [marketing.store :as store]
            [marketing.file-store :as file-store]
            [marketing.llm :as llm]
            [marketing.llm-realmodel :as llm-realmodel]
            [marketing.operation :as operation]
            [marketing.dashboard :as dashboard])
  (:gen-class))

(def actor-name "cloud-itonami-isic-6201")
(def isic-code "6201")
(def service-version "0.1.0")
(def default-port 8080)

;; ───────────────────────── JSON encode/decode ─────────────────────────
;; clojure.data.json's default keyword encoding drops namespaces (writes
;; (name kw)); this actor's domain vocabulary is namespaced keywords
;; (:campaign/send-message, :lead/advance-stage, :tier/pro-style values,
;; …), so we walk values ourselves before encoding to preserve the
;; namespace instead of silently truncating it.

(defn- kw->str [k] (subs (str k) 1))

(defn- ->json-safe [v]
  (cond
    (keyword? v)    (kw->str v)
    (map? v)        (into {} (map (fn [[k v]] [(if (keyword? k) (kw->str k) k) (->json-safe v)])) v)
    (set? v)        (mapv ->json-safe v)
    (sequential? v) (mapv ->json-safe v)
    :else           v))

(defn- write-json [v] (json/write-str (->json-safe v)))

(defn- json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body (write-json body)})

(defn- read-body-json
  "Reads+parses the request body as JSON with string->keyword keys.
  Returns `::parse-error` on invalid/absent JSON so callers can 400
  instead of 500ing on a malformed body."
  [req]
  (try
    (if-let [b (:body req)]
      (let [s (slurp b)]
        (if (str/blank? s) {} (json/read-str s :key-fn keyword)))
      {})
    (catch Exception _ ::parse-error)))

;; ───────────────────────── auth ─────────────────────────

(defn- bearer-token [req]
  (when-let [h (get-in req [:headers "authorization"])]
    (when (str/starts-with? h "Bearer ")
      (str/trim (subs h (count "Bearer "))))))

(defn- constant-time-string=
  "Constant-time equality for secret comparison (bearer tokens), NOT
  plain `=`. `=`/`.equals` on strings/CharSequences short-circuits at
  the first differing character/length, so its wall-clock time leaks
  how many leading bytes matched — an attacker who can measure response
  latency could in principle recover a valid token one byte at a time,
  far cheaper than brute force (flagged as a real, unmitigated gap in
  ADR-2607124600, made against this actor's `cloud-itonami-isic-5820`
  sibling but equally applicable here — same `authorized?` pattern).

  Uses `java.security.MessageDigest/isEqual`, the JDK's standard
  constant-time byte-array comparator (no extra dependency). It has
  been constant-time WITH RESPECT TO CONTENT regardless of a length
  mismatch since JDK 6; this fleet's JDK (Temurin 21, confirmed via
  `java -version` at implementation time) postdates that by over a
  decade, so an explicit `(= (count a) (count b))` length-gate before
  calling it is deliberately NOT added here — that would only
  reintroduce a smaller but real length-based timing signal that
  `isEqual` itself already avoids internally."
  [^String a ^String b]
  (java.security.MessageDigest/isEqual
   (.getBytes a "UTF-8")
   (.getBytes b "UTF-8")))

(defn- authorized?
  "True iff `token` (the server's configured secret) is non-blank AND
  matches the request's `Authorization: Bearer <...>` header. The
  match uses `constant-time-string=`, not `=`, specifically so this
  comparison does not leak per-byte match information via timing (see
  its docstring and ADR-2607124600) — this is internal hardening only,
  the function's contract (boolean, requires a non-blank `token` and a
  present `Bearer` header) is unchanged."
  [req token]
  (and (some? token) (not (str/blank? token))
       (let [presented (bearer-token req)]
         (and (some? presented)
              (constant-time-string= token presented)))))

;; ───────────────────────── request coercion ─────────────────────────
;; JSON has no keyword type. This actor's request/proposal maps are
;; already keyword-keyed 1:1 with the JSON field names we document in
;; docs/api.md (op, subject, campaign-id, contact-id, to-stage, score,
;; …) — `json/read-str :key-fn keyword` gets keys for free. Only VALUES
;; that must be keywords (not strings) in the existing marketing.
;; operation/marketing.policy code are coerced explicitly here, nothing
;; else — this is a thin adapter, not a schema/validation layer.

(defn- kw-val [v] (when (some? v) (if (keyword? v) v (keyword (str v)))))

(defn- coerce-request
  "`:subject` defaults to `:contact-id` when omitted — this actor's own
  code (`marketing.sim`, `marketing.policy-contract-test`) always sets
  `:subject` equal to the contact-id for all three write ops, so this is
  filling in the actor's own existing convention, not inventing a new
  shape."
  [m]
  (cond-> m
    (contains? m :op)                              (update :op kw-val)
    (contains? m :to-stage)                         (update :to-stage kw-val)
    (and (not (contains? m :subject))
         (contains? m :contact-id))                 (assoc :subject (:contact-id m))))

(defn- coerce-context
  [m]
  (cond-> m
    (contains? m :actor-role) (update :actor-role kw-val)))

;; ───────────────────────── /send, /advance-stage, /update-score ───────

(defn- run-operation
  "Runs `request`/`context` through the EXISTING OperationActor graph
  (`actor`, built once at server-start via `marketing.operation/build`)
  — this function contains no governance logic of its own, it only
  shapes the graph's result into an HTTP response.

  One HTTP call = one fresh thread-id = one graph run. A `:commit`/
  `:hold` result is final. An `:escalate` result means the graph
  interrupted before `:request-approval` (human-in-the-loop) — there is
  no HTTP endpoint yet to submit that approval/rejection (out of scope
  for this first HTTP layer, see docs/api.md), so it's surfaced as 202
  with the thread-id and reason rather than silently blocking or
  500ing. This is the SAME shape `lead-score-mismatch` escalations use
  (`:lead/update-score` is the only op that reaches this path today,
  since `marketing.phase`'s phase-approval gate is bypassed at
  phase 3 — see docs/api.md)."
  [actor request context]
  (let [thread-id (str (java.util.UUID/randomUUID))
        res (g/run* actor {:request request :context context} {:thread-id thread-id})
        state (:state res)
        disposition (:disposition state)]
    (case (:status res)
      :interrupted
      (json-response 202
        {:decision    "escalated"
         :op          (:op request)
         :subject     (:subject request)
         :thread-id   thread-id
         :reason      (-> state :audit last :reason)
         :confidence  (-> state :verdict :confidence)
         :note        (str "Escalated for human approval; this HTTP layer does not yet "
                            "expose an approval/rejection endpoint (see docs/api.md).")})

      ;; :done
      (case disposition
        :commit
        (json-response 200
          {:decision "committed"
           :op       (:op request)
           :subject  (:subject request)
           :record   (:record state)})

        :hold
        (json-response 200
          {:decision   "held"
           :op         (:op request)
           :subject    (:subject request)
           :violations (-> state :verdict :violations)
           :confidence (-> state :verdict :confidence)})

        (json-response 500 {:error "unexpected disposition" :disposition disposition})))))

;; ───────────────────────── /dashboard ─────────────────────────

(defn- dashboard-response
  "`marketing.dashboard/snapshot` already owns its own RBAC gate
  (`marketing.dashboard/authorized?`, checked against `marketing.policy/
  permissions`'s `:marketing/view-dashboard` entitlement) — this
  function does not reimplement or bypass that gate, it only routes the
  HTTP caller's role through `snapshot` and maps its own
  `{:authorized? false :reason :rbac}` result onto HTTP 403."
  [store actor-role]
  (let [res (dashboard/snapshot store {:actor-role actor-role})]
    (if (:authorized? res)
      (json-response 200 (dissoc res :authorized?))
      (json-response 403 {:error "forbidden" :reason (:reason res)}))))

;; ───────────────────────── root/info ─────────────────────────

(defn- root-info []
  {:actor     actor-name
   :isic-code isic-code
   :version   service-version
   :links     {:health         "/health"
               :send           "/send"
               :advance-stage  "/advance-stage"
               :update-score   "/update-score"
               :dashboard      "/dashboard"
               :api-docs       "docs/api.md"}})

;; ───────────────────────── handler ─────────────────────────

(defn- handle-operation
  "Shared body for the three protected write endpoints: auth check ->
  JSON body parse -> required-field check -> `run-operation`. `op`
  is the fixed `:op` this route always uses (the HTTP route names the
  operation; the body never needs to repeat it), `required` are the
  additional request fields that must be present."
  [req token actor op required]
  (if-not (authorized? req token)
    (json-response 401 {:error "unauthorized"})
    (let [body (read-body-json req)]
      (cond
        (= body ::parse-error)
        (json-response 400 {:error "invalid JSON body"})

        (not (map? body))
        (json-response 400 {:error "body must be a JSON object"})

        :else
        (let [missing (remove #(contains? body %) required)]
          (if (seq missing)
            (json-response 400 {:error (str "missing required field(s): "
                                            (str/join ", " (map name missing)))})
            (let [request (coerce-request (assoc (dissoc body :context) :op op))
                  context (coerce-context (get body :context {}))]
              (run-operation actor request context))))))))

(defn make-handler
  "Builds the Ring handler. `store` and `actor` (a compiled
  `marketing.operation/build` graph over `store`) are injected so
  tests/callers control the backend; `token` is the bearer token every
  protected request must present."
  [{:keys [store actor token]}]
  (fn [{:keys [request-method uri params] :as req}]
    (try
      (cond
        (and (= :get request-method) (= "/" uri))
        (json-response 200 (root-info))

        (and (= :get request-method) (= "/health" uri))
        (let [reachable? (try (store/all-contacts store) true (catch Exception _ false))]
          (json-response (if reachable? 200 503)
                          {:status (if reachable? "ok" "degraded")
                           :store  (if reachable? "reachable" "unreachable")}))

        (and (= :post request-method) (= "/send" uri))
        (handle-operation req token actor :campaign/send-message
                           #{:campaign-id :contact-id})

        (and (= :post request-method) (= "/advance-stage" uri))
        (handle-operation req token actor :lead/advance-stage
                           #{:contact-id :to-stage})

        (and (= :post request-method) (= "/update-score" uri))
        (handle-operation req token actor :lead/update-score
                           #{:contact-id :score})

        (and (= :get request-method) (= "/dashboard" uri))
        (if-not (authorized? req token)
          (json-response 401 {:error "unauthorized"})
          (let [role (kw-val (get params "role"))]
            (if (nil? role)
              (json-response 400 {:error "missing required query param: role"})
              (dashboard-response store role))))

        :else
        (json-response 404 {:error "not found"}))
      (catch Exception e
        (json-response 500 {:error (or (ex-message e) (str e))})))))

;; ───────────────────────── server lifecycle ─────────────────────────

(defn- describe-advisor-mode
  "Formats `marketing.llm-realmodel/preflight`'s config for a startup
  print line. Never includes the API key value — `preflight`'s map only
  ever carries `:api-key?` (boolean), not the key itself."
  [mode {:keys [provider url model ok? missing]}]
  (str "marketing.http: MarketingOps-LLM advisor = " mode
       " (provider=" (name provider) " model=" model
       (when url (str " url=" url))
       (when-not ok? (str " -- WARNING missing env: " (pr-str missing)))
       ")"))

(defn- resolve-advisor!
  "Picks the `marketing.llm/Advisor` for `start-server!`/`-main`: the
  sealed mock (`marketing.llm/mock-advisor` — deterministic, offline, no
  real model calls; the same default `marketing.operation/build` itself
  already falls back to) unless `$ISIC6201_MODEL_API_KEY` is set and
  non-blank, in which case `marketing.llm-realmodel/real-advisor` (a real
  HTTP call to an OpenAI-compatible/Anthropic endpoint) is used instead.
  Always prints which mode it picked, plus `marketing.llm-realmodel/
  preflight`'s honest missing/present report, before returning — this
  MUST work correctly (and say so) with zero credentials present, which
  is exactly this build's own sandbox.

  NOTE the real-model path's END-TO-END behavior against an actual model
  API has not been exercised anywhere in this build (no credentials were
  ever available to do so) — only `preflight`'s reporting and the
  request/response wire shape against a local stub server are verified
  (see `test/marketing/llm_realmodel_test.clj`). Choosing this mode wires
  a real, untested-against-a-real-model adapter, not a proven-safe one."
  []
  (let [{:keys [api-key?] :as pf} (llm-realmodel/preflight)]
    (if api-key?
      (do (println (describe-advisor-mode "REAL MODEL" pf))
          (llm-realmodel/real-advisor))
      (do (println (describe-advisor-mode "SEALED MOCK (no ISIC6201_MODEL_API_KEY)" pf))
          (llm/mock-advisor)))))

(defn start-server!
  "Starts the real HTTP server. `store` — any `marketing.store/Store`
  (`MemStore`, `marketing.store/DatomicStore`, or `marketing.file-store/
  FileStore` — see `-main`'s docstring for which of these actually
  survives a process restart); `port` — TCP port (default `default-port`);
  `token` — the bearer token EVERY protected request must present, and
  MUST be a non-blank string. FAIL CLOSED: throws (refuses to start) if
  `token` is blank/nil rather than starting with auth silently disabled.
  `advisor` — optional `marketing.llm/Advisor` override (tests/callers
  that want a specific advisor injected); when omitted, `resolve-advisor!`
  picks the sealed mock or the real-model adapter from
  `$ISIC6201_MODEL_API_KEY` (see its docstring) and prints which one it
  picked.

  Returns the `org.httpkit.server.HttpServer`; use
  `org.httpkit.server/server-port` to read the actual bound port (useful
  with `:port 0` for tests) and `org.httpkit.server/server-stop!` to
  stop it."
  [{:keys [store port token advisor] :or {port default-port}}]
  (when (str/blank? token)
    (throw (ex-info (str "ISIC6201_API_TOKEN (or explicit `token`) must be a non-blank "
                          "value — refusing to start marketing.http with auth disabled")
                     {})))
  (let [advisor (or advisor (resolve-advisor!))
        actor (operation/build store {:advisor advisor})
        handler (-> (make-handler {:store store :actor actor :token token})
                    wrap-params)]
    (httpkit/run-server handler {:port port :legacy-return-value? false})))

(defn- warn-ephemeral-store!
  "Prints a loud, unmissable stderr warning that `-main` is about to run
  against an ephemeral (process-lifetime-only) store. There is
  deliberately no quiet/default path into this mode — see `resolve-store!`."
  []
  (binding [*out* *err*]
    (println "WARNING: ISIC6201_STORE_FILE is not set — running against an"
             "EPHEMERAL in-memory store (marketing.store/seed-db). ALL STATE"
             "(contacts, campaigns, sends, engagement history, the audit"
             "ledger) WILL BE LOST when this process exits or restarts.")
    (println "WARNING: do not use this mode for real operation. Set"
             "ISIC6201_STORE_FILE=/path/to/db.edn to run against a"
             "disk-durable store instead (see docs/api.md's Persistence"
             "section).")))

(defn- resolve-store!
  "Picks the `Store` backend for `-main` from environment configuration.

    $ISIC6201_STORE_FILE — if set, a disk-durable `marketing.file-store/
                            FileStore` at that path: loads existing state
                            if the file is already there, otherwise seeds
                            it with the same demo dataset `seed-db` uses
                            and writes that as the first snapshot. Every
                            mutating call persists a fresh snapshot to
                            that path — this is the ONLY backend wired
                            here that survives a process restart, and it
                            has been verified end-to-end (real process
                            start -> commit over real HTTP -> kill ->
                            restart -> data still there), not just
                            unit-tested.

  If unset, falls back to `marketing.store/seed-db` (ephemeral,
  in-memory, discarded on exit) and prints a WARNING to stderr via
  `warn-ephemeral-store!` so an operator can never end up running
  without persistence silently/by accident.

  NOTE, deliberately NOT wired here: `marketing.store/datomic-store`
  (`DatomicStore`). Despite the name, as implemented in this repo today
  it provides NO durability beyond `MemStore` — its constructor
  (`(langchain.db/create-conn schema)`) is a plain in-process atom with
  no connection URI/socket/file, so selecting it here under a
  durability-implying env var (e.g. an `ISIC6201_DATOMIC_URI`) would be
  exactly the 'fake persistence' this fix is supposed to remove, not
  add — this is the SAME finding `cloud-itonami-isic-5820` made about
  its own `crm.store/DatomicStore`, verified independently here rather
  than assumed by analogy (see `marketing.file-store`'s ns docstring).
  Making `DatomicStore` genuinely durable needs `marketing.store`
  refactored to accept an injected `:db-api` (see `langchain.db/api` /
  `langchain.kotoba-db/kotoba-api`) pointed at a real Datomic Local or a
  live kotoba-server pod — real infrastructure this entry point does not
  have in this environment. See `marketing.file-store`'s ns docstring
  and docs/api.md's Persistence section for the full explanation."
  []
  (if-let [path (System/getenv "ISIC6201_STORE_FILE")]
    (file-store/file-store! path)
    (do (warn-ephemeral-store!)
        (store/seed-db))))

(defn -main
  "Entry point for `clojure -M:serve`. Reads:
    $ISIC6201_API_TOKEN — REQUIRED. If unset/blank, prints a fatal error
                          to stderr and exits 1 WITHOUT starting the
                          server (fail closed — no 'runs with no auth'
                          fallback).
    $ISIC6201_HTTP_PORT — optional, default `default-port` (8080).
    $ISIC6201_STORE_FILE — optional. See `resolve-store!`: if set, runs
                          against a disk-durable `marketing.file-store/
                          FileStore` at that path (survives restart); if
                          unset, runs against an ephemeral `marketing.
                          store/seed-db` and prints a stderr WARNING that
                          state will be lost on exit.
    $ISIC6201_MODEL_API_KEY (+ optional $ISIC6201_MODEL_PROVIDER/_URL/
                          _MODEL) — optional. See `resolve-advisor!`/
                          `marketing.llm-realmodel`: if set and non-blank,
                          runs the MarketingOps-LLM advisor as a real
                          model call instead of the sealed mock; either
                          way, prints which mode it picked at startup.
                          Real-model end-to-end behavior against an
                          actual API is UNVERIFIED in this build (see
                          docs/api.md's Real-model advisor section)."
  [& _]
  (let [token (System/getenv "ISIC6201_API_TOKEN")
        port  (if-let [p (System/getenv "ISIC6201_HTTP_PORT")]
                (Integer/parseInt p)
                default-port)]
    (if (str/blank? token)
      (do (binding [*out* *err*]
            (println "FATAL: ISIC6201_API_TOKEN is not set (or blank)."
                     "Refusing to start marketing.http with auth disabled."))
          (System/exit 1))
      (let [store (resolve-store!)
            srv (start-server! {:store store :port port :token token})]
        (println (str "marketing.http listening on :" (httpkit/server-port srv)
                       " (actor=" actor-name " isic=" isic-code
                       " version=" service-version ")"))
        (httpkit/server-join srv)))))
