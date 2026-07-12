(ns marketing.store
  "SSoT for the marketing-automation SaaS platform actor, behind a
  `Store` protocol so the backend is a swap, not a rewrite:

    - `MemStore`     — atom of Datomic-shaped EDN. The deterministic
                       default for dev/tests/demo (no deps).
    - `DatomicStore` — backed by `langchain.db`, a Datomic-API-compatible
                       EAV store. Pure `.cljc`, so it runs offline AND can
                       be pointed at a real Datomic Local or a
                       kotoba-server pod.

  Both implement the same protocol and pass the same contract
  (test/marketing/store_contract_test.clj) — the actor, the
  ConsentGovernor and the audit ledger never know which SSoT they run
  on.

  Entity shapes:
    - a contact (consent-status/unsubscribed?/lifecycle-stage/lead-score)
    - a campaign (name/channel)
    - a send record keyed by [campaign-id contact-id], a DEDICATED
      `:sent?` boolean — NOT inferred from any status value (the same
      6920-lesson discipline `cloud-itonami-isic-5820`'s double-close-
      gate applies)
    - per-contact engagement history (for `kotoba.crm.leadscore`'s
      ground-truth recompute)

  There is NO field anywhere in this schema for a sales-opportunity/
  subscription-billing record or a support-ticket SLA — this actor
  covers marketing-campaign + lead-lifecycle governance only.
  Sales-pipeline/subscription-entitlement governance
  (`cloud-itonami-isic-5820`) and customer-service ticketing are
  explicitly out of scope for R0 (see README / docs/business-model.md).

  The ledger stays append-only on every backend."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.db :as d]))

(defprotocol Store
  (contact [s id])
  (all-contacts [s])
  (campaign [s id])
  (all-campaigns [s])
  (send-record [s campaign-id contact-id])
  (engagement-history [s contact-id])
  (ledger [s])
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision/disclosure fact")
  (with-contacts [s contacts]     "replace/seed contacts (map id→contact)")
  (with-campaigns [s campaigns]   "replace/seed campaigns (map id→campaign)")
  (with-sends [s sends]           "replace/seed sends (map [campaign-id contact-id]→{:sent? bool})")
  (with-engagement [s engagement] "replace/seed engagement history (map contact-id→[event ..])"))

;; ───────────────────────── demo data (fictitious) ─────────────────────

(defn demo-data
  "A small, entirely fictitious dataset so the actor + tests run
  offline. `contact-500`'s engagement history sums to exactly 39 points
  under `kotoba.crm.leadscore/point-values` (1+3+5+10+20), matching its
  seeded `:lead-score` — a clean recompute-matches baseline used to
  exercise the non-mismatch path of `lead-score-mismatch`."
  []
  {:contacts
   {"contact-100" {:id "contact-100" :name "山田 太郎(デモ)" :consent-status :opted-in
                   :unsubscribed? false :lifecycle-stage :lead :lead-score 0}
    "contact-200" {:id "contact-200" :name "Jane Doe (demo)" :consent-status :opted-out
                   :unsubscribed? false :lifecycle-stage :lead :lead-score 0}
    "contact-300" {:id "contact-300" :name "佐藤 花子(デモ)" :consent-status :expired
                   :unsubscribed? false :lifecycle-stage :subscriber :lead-score 0}
    "contact-400" {:id "contact-400" :name "John Roe (demo)" :consent-status :opted-in
                   :unsubscribed? true :lifecycle-stage :lead :lead-score 0}
    "contact-500" {:id "contact-500" :name "鈴木 一郎(デモ)" :consent-status :opted-in
                   :unsubscribed? false :lifecycle-stage :mql :lead-score 39}
    "contact-600" {:id "contact-600" :name "Mary Major (demo)" :consent-status :opted-in
                   :unsubscribed? false :lifecycle-stage :subscriber :lead-score 0}}
   :campaigns
   {"camp-100" {:id "camp-100" :name "Q3 Nurture (demo)" :channel :email}
    "camp-200" {:id "camp-200" :name "Product Launch (demo)" :channel :email}}
   :sends
   {["camp-200" "contact-600"] {:sent? true}}
   :engagement
   {"contact-500" [{:kind :email-open} {:kind :email-click}
                   {:kind :pricing-page-view} {:kind :form-fill}
                   {:kind :demo-request}]}})

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (contact [_ id] (get-in @a [:contacts id]))
  (all-contacts [_] (sort-by :id (vals (:contacts @a))))
  (campaign [_ id] (get-in @a [:campaigns id]))
  (all-campaigns [_] (sort-by :id (vals (:campaigns @a))))
  (send-record [_ campaign-id contact-id] (get-in @a [:sends [campaign-id contact-id]]))
  (engagement-history [_ contact-id] (get-in @a [:engagement contact-id] []))
  (ledger [_] (:ledger @a))
  (commit-record! [s {:keys [effect value]}]
    (case effect
      :send-record-upsert
      (swap! a assoc-in [:sends [(:campaign-id value) (:contact-id value)]] {:sent? true})
      :stage-transition-upsert
      (swap! a update-in [:contacts (:contact-id value)] merge {:lifecycle-stage (:to-stage value)})
      :score-update-upsert
      (swap! a update-in [:contacts (:contact-id value)] merge {:lead-score (:score value)})
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-contacts [s cs]   (when (seq cs) (swap! a assoc :contacts cs)) s)
  (with-campaigns [s cs]  (when (seq cs) (swap! a assoc :campaigns cs)) s)
  (with-sends [s sds]     (when (seq sds) (swap! a assoc :sends sds)) s)
  (with-engagement [s eng] (when (seq eng) (swap! a assoc :engagement eng)) s))

(defn seed-db
  "A MemStore seeded with the demo data. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger []))))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────

(def ^:private schema
  {:contact/id          {:db/unique :db.unique/identity}
   :campaign/id         {:db/unique :db.unique/identity}
   :send/key            {:db/unique :db.unique/identity}
   :engagement/contact-id {:db/unique :db.unique/identity}
   :ledger/seq          {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- contact->tx [{:keys [id name consent-status unsubscribed? lifecycle-stage lead-score]}]
  {:contact/id id :contact/name name
   :contact/consent-status consent-status
   :contact/unsubscribed (boolean unsubscribed?)
   :contact/lifecycle-stage lifecycle-stage
   :contact/lead-score (or lead-score 0)})

(defn- pull->contact [m]
  (when (:contact/id m)
    {:id (:contact/id m) :name (:contact/name m)
     :consent-status (:contact/consent-status m)
     :unsubscribed? (:contact/unsubscribed m)
     :lifecycle-stage (:contact/lifecycle-stage m)
     :lead-score (:contact/lead-score m)}))

(def ^:private contact-pull
  [:contact/id :contact/name :contact/consent-status :contact/unsubscribed
   :contact/lifecycle-stage :contact/lead-score])

(defn- campaign->tx [{:keys [id name channel]}]
  {:campaign/id id :campaign/name name :campaign/channel channel})

(defn- pull->campaign [m]
  (when (:campaign/id m)
    {:id (:campaign/id m) :name (:campaign/name m) :channel (:campaign/channel m)}))

(def ^:private campaign-pull [:campaign/id :campaign/name :campaign/channel])

(defn- send->tx [campaign-id contact-id sent?]
  {:send/key (enc [campaign-id contact-id]) :send/sent? (boolean sent?)})

(defn- pull->send [m]
  (when (:send/key m)
    {:sent? (:send/sent? m)}))

(def ^:private send-pull [:send/key :send/sent?])

(defn- engagement->tx [contact-id events]
  {:engagement/contact-id contact-id :engagement/events (enc events)})

(defn- pull->engagement [m]
  (if (:engagement/contact-id m)
    (or (dec* (:engagement/events m)) [])
    []))

(def ^:private engagement-pull [:engagement/contact-id :engagement/events])

(defrecord DatomicStore [conn]
  Store
  (contact [_ id] (pull->contact (d/pull (d/db conn) contact-pull [:contact/id id])))
  (all-contacts [_]
    (->> (d/q '[:find [?id ...] :where [?e :contact/id ?id]] (d/db conn))
         (map #(pull->contact (d/pull (d/db conn) contact-pull [:contact/id %])))
         (sort-by :id)))
  (campaign [_ id] (pull->campaign (d/pull (d/db conn) campaign-pull [:campaign/id id])))
  (all-campaigns [_]
    (->> (d/q '[:find [?id ...] :where [?e :campaign/id ?id]] (d/db conn))
         (map #(pull->campaign (d/pull (d/db conn) campaign-pull [:campaign/id %])))
         (sort-by :id)))
  (send-record [_ campaign-id contact-id]
    (pull->send (d/pull (d/db conn) send-pull [:send/key (enc [campaign-id contact-id])])))
  (engagement-history [_ contact-id]
    (pull->engagement (d/pull (d/db conn) engagement-pull [:engagement/contact-id contact-id])))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (commit-record! [s {:keys [effect value]}]
    (case effect
      :send-record-upsert
      (d/transact! conn [(send->tx (:campaign-id value) (:contact-id value) true)])
      :stage-transition-upsert
      (d/transact! conn [(contact->tx (merge (contact s (:contact-id value))
                                              {:lifecycle-stage (:to-stage value)}))])
      :score-update-upsert
      (d/transact! conn [(contact->tx (merge (contact s (:contact-id value))
                                              {:lead-score (:score value)}))])
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-contacts [s cs]
    (when (seq cs) (d/transact! conn (mapv contact->tx (vals cs)))) s)
  (with-campaigns [s cs]
    (when (seq cs) (d/transact! conn (mapv campaign->tx (vals cs)))) s)
  (with-sends [s sds]
    (when (seq sds)
      (d/transact! conn (mapv (fn [[[campaign-id contact-id] {:keys [sent?]}]]
                                 (send->tx campaign-id contact-id sent?))
                               sds)))
    s)
  (with-engagement [s eng]
    (when (seq eng)
      (d/transact! conn (mapv (fn [[contact-id events]] (engagement->tx contact-id events)) eng)))
    s))

(defn datomic-store
  ([] (datomic-store {}))
  ([{:keys [contacts campaigns sends engagement]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-contacts contacts) (with-campaigns campaigns)
         (with-sends sends) (with-engagement engagement)))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo data — proves protocol parity."
  []
  (datomic-store (demo-data)))

;; ───────────────────────── ledger formatting ─────────────────────────

(defn ledger-line
  [{:keys [op actor subject disposition basis]}]
  (str/join " · "
            [(name disposition)
             (str "op=" op)
             (str "actor=" actor)
             (str "subject=" subject)
             (str "basis=" (pr-str basis))]))
