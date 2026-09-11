(ns marketing.dashboard
  "The actor's FIRST aggregate-view capability. `docs/DESIGN.md` §8
  explains why this actor has no `report.cljc` (no GOVERNED single-record
  disclosure op like `cloud-itonami-isic-5820`'s `:disclosure/query`) —
  this namespace is deliberately NOT that. It never renders or discloses
  ONE contact/campaign record; it only aggregates counts ACROSS every
  contact/campaign already in a `marketing.store/Store` into a
  marketing-manager-facing dashboard (lead-lifecycle funnel, conversion
  rates, campaign send/rejection rollup, lead-score distribution) — the
  same DISTINCT-from-per-record-disclosure framing
  `kotoba.crm.funnel`'s own docstring draws.

  Ground-truth discipline, consistent with this actor's own governor:
    - The lead-lifecycle funnel and conversion rates are computed with
      `kotoba.crm.funnel` over `marketing.facts/lifecycle-stage-order` and
      `marketing.facts/exit-stages` — the SAME stage vocabulary
      `marketing.policy`'s `stage-sequence-gate` enforces, never
      redefined here.
    - The lead-score distribution NEVER trusts a contact's stored/cached
      `:lead-score` — every figure is recomputed from
      `marketing.store/engagement-history` via
      `kotoba.crm.leadscore/recompute-score`, the same 'recompute the
      ground truth, never trust the claimed figure' discipline
      `marketing.policy`'s `lead-score-mismatch` gate applies at write
      time. A contact whose stored score disagrees with the recompute is
      surfaced explicitly (`:stale-contacts`/`:stale-count`), never
      silently averaged over.
    - The campaign performance rollup aggregates data the ConsentGovernor
      ALREADY generates as ledger facts (successful sends via
      `marketing.store/send-record`, `consent-revoked-send-gate`
      rejections via `marketing.store/ledger`) — no new tracking is
      invented. Building this rollup surfaced one real, minimal gap: a
      rejected send's `policy-hold` ledger fact did not carry a
      `:campaign-id` at all (only `:op`/`:subject`(=contact-id)/`:basis`),
      making per-campaign rejection correlation impossible from ledger
      history alone. Fixed at the source in `marketing.policy/hold-fact`
      and (symmetrically) `marketing.operation`'s private `commit-fact`
      — see those docstrings. A `policy-hold` fact from BEFORE that fix
      (no `:campaign-id` key) is bucketed under `:unknown` here rather
      than silently dropped or mis-attributed.

  This namespace does NO I/O of its own and commits NOTHING — it only
  reads a `Store` already handed to it, exactly like
  `kotoba.crm.funnel`/`kotoba.crm.leadscore` are pure, storage-agnostic,
  entities-supplied-by-caller namespaces with no governance built in.
  Governance for calling it at all lives one layer up, in `authorized?`/
  `snapshot` below — see their docstrings and `docs/DESIGN.md`'s
  \"Dashboard\" section for the RBAC-gating decision and its reasoning."
  (:require [marketing.store :as store]
            [marketing.facts :as facts]
            [marketing.policy :as policy]
            [kotoba.crm.funnel :as funnel]
            [kotoba.crm.leadscore :as leadscore]))

;; ───────────────────────── lead lifecycle funnel ─────────────────────

(defn- as-funnel-entities
  "`kotoba.crm.funnel` reads a caller-supplied `:stage` key; this actor's
  own contact shape names the same fact `:lifecycle-stage`
  (`marketing.store`'s contact shape, unchanged elsewhere in this actor).
  This is the ONLY place that bridges the two names — never renamed at
  the source, per the task's own instruction not to invent incompatible
  field names."
  [contacts]
  (mapv (fn [c] {:id (:id c) :stage (:lifecycle-stage c)}) contacts))

(defn lifecycle-funnel
  "Point-in-time snapshot funnel over every contact in `store`, keyed by
  this actor's own `marketing.facts/lifecycle-stage-order` /
  `marketing.facts/exit-stages` (reused, never redefined):

    :stage-counts   — `kotoba.crm.funnel/stage-counts`: how many contacts
                       are CURRENTLY at each stage right now (every
                       ordered stage zero-filled; exit stages included
                       under their own key when occupied).
    :reached-counts — `kotoba.crm.funnel/reached-counts`: how many
                       contacts got AT LEAST as far as each ordered
                       stage. INHERITED R0 LIMIT (see
                       `kotoba.crm.funnel/coverage`): a contact currently
                       in an exit stage (e.g. `:unsubscribed`) is EXCLUDED
                       from this count entirely, because which forward
                       stage it exited from is not recoverable from
                       `:lifecycle-stage` alone — this actor's contact
                       shape (`marketing.store`) carries no
                       `:reached-stage` fact, so exited contacts are
                       never guessed into a bucket here."
  [store]
  (let [entities (as-funnel-entities (store/all-contacts store))]
    {:stage-counts   (funnel/stage-counts entities facts/lifecycle-stage-order)
     :reached-counts (funnel/reached-counts entities facts/lifecycle-stage-order)}))

(defn conversion-rates
  "Stage-to-stage conversion rates (subscriber→lead→mql→sql→customer)
  via `kotoba.crm.funnel/conversion-rate` over this store's
  `reached-counts` (computed fresh, not a cached figure). A pair with no
  entity ever reaching its `from-stage` is `nil` (no data), never a
  fabricated 0% or a crash — see `kotoba.crm.funnel/conversion-rate`."
  [store]
  (let [{:keys [reached-counts]} (lifecycle-funnel store)]
    (funnel/conversion-rate reached-counts facts/lifecycle-stage-order)))

;; ───────────────────────── campaign performance rollup ────────────────

(defn- send-succeeded?
  [store campaign-id contact-id]
  (boolean (:sent? (store/send-record store campaign-id contact-id))))

(defn- consent-rejection-campaign-id
  "The `:campaign-id` a `policy-hold` ledger fact correlates to, or
  `:unknown` for a pre-fix fact that never carried one (see this
  namespace's top docstring) — never silently dropped."
  [fact]
  (get fact :campaign-id :unknown))

(defn- consent-rejected-send-fact?
  [fact]
  (and (= :campaign/send-message (:op fact))
       (= :hold (:disposition fact))
       (some #{:consent-revoked-send-gate} (:basis fact))))

(defn campaign-rollup
  "Per-campaign performance rollup across every campaign in `store`:
  `{campaign-id {:sent <n> :consent-rejected <n>}}`. `:sent` is the count
  of contacts with a successful (committed) send for that campaign
  (`marketing.store/send-record`'s dedicated `:sent?` fact — never
  inferred from any status value, same discipline as
  `marketing.policy/double-send-violations`). `:consent-rejected` is the
  count of `consent-revoked-send-gate` rejections recorded in
  `marketing.store/ledger` for that campaign — real violation records
  the ConsentGovernor already produced, aggregated here, never
  re-derived by re-running the governor's checks.

  Every campaign in `(store/all-campaigns store)` is included even at
  zero (mirrors `kotoba.crm.funnel/stage-counts`'s zero-fill discipline —
  a caller building a rollup table should see every campaign's row, not
  only the ones with activity). A `:consent-rejected` count attributable
  to no known campaign (a pre-fix ledger fact missing `:campaign-id`, see
  this namespace's top docstring) is bucketed separately under
  `:unknown`, present only when non-zero."
  [store]
  (let [campaigns   (store/all-campaigns store)
        contacts    (store/all-contacts store)
        sent-counts (into {}
                          (map (fn [{:keys [id]}]
                                 [id (count (filter #(send-succeeded? store id (:id %)) contacts))]))
                          campaigns)
        rejections  (->> (store/ledger store)
                          (filter consent-rejected-send-fact?)
                          (group-by consent-rejection-campaign-id)
                          (reduce-kv (fn [m k v] (assoc m k (count v))) {}))
        known-ids   (mapv :id campaigns)
        rollup      (into {}
                          (map (fn [cid]
                                 [cid {:sent             (get sent-counts cid 0)
                                       :consent-rejected (get rejections cid 0)}]))
                          known-ids)]
    (cond-> rollup
      (pos? (get rejections :unknown 0))
      (assoc :unknown {:sent 0 :consent-rejected (get rejections :unknown 0)}))))

;; ───────────────────────── lead-score distribution ─────────────────────

(defn- recomputed-scores
  "One row per contact: `{:id :stored-score :score}`, where `:score` is
  ALWAYS the fresh `kotoba.crm.leadscore/recompute-score` over that
  contact's `marketing.store/engagement-history` — the stored/cached
  `:lead-score` is read only for comparison, never trusted as the
  figure itself (same discipline as `marketing.policy/lead-score-
  mismatch`)."
  [store]
  (mapv (fn [{:keys [id lead-score]}]
          {:id id
           :stored-score lead-score
           :score (leadscore/recompute-score (store/engagement-history store id))})
        (store/all-contacts store)))

(defn- median [xs]
  (when (seq xs)
    (let [sorted (vec (sort xs))
          n      (count sorted)
          mid    (quot n 2)]
      (if (odd? n)
        (double (nth sorted mid))
        (/ (+ (double (nth sorted (dec mid))) (double (nth sorted mid))) 2.0)))))

(defn lead-score-distribution
  "Lead-score distribution snapshot across every contact in `store`,
  ground-truthed via `kotoba.crm.leadscore/recompute-score` (never the
  stored/cached `:lead-score` value — see `recomputed-scores`).

    :by-score       — exact `score -> count` frequency map.
    :buckets        — `[bucket-lo bucket-lo+width) -> count` histogram,
                       `bucket-width` (default 10) points wide.
    :count          — total contacts included.
    :min/:max/:mean/:median — summary stats over the recomputed scores
                       (all `nil` when `store` has no contacts, never a
                       divide-by-zero or a fabricated 0).
    :stale-count    — how many contacts' stored `:lead-score` disagrees
                       with the recompute right now.
    :stale-contacts — those contacts' ids, so a caller can act on the
                       specific records rather than only the count.

  INHERITED R0 LIMIT (see `kotoba.crm.leadscore/coverage`): the recompute
  is `kotoba.crm.leadscore`'s fixed weighted-point model only — no
  ML/predictive scoring, no per-account custom weight overrides, no
  inactivity-based decay applied here (this snapshot never opts into the
  recency-decay variant, so it always uses the same undecayed ground
  truth `marketing.policy/lead-score-mismatch` compares against)."
  ([store] (lead-score-distribution store {}))
  ([store {:keys [bucket-width] :or {bucket-width 10}}]
   (let [rows   (recomputed-scores store)
         scores (mapv :score rows)
         n      (count scores)
         bucket-lo (fn [s] (* bucket-width (quot s bucket-width)))
         stale  (filterv #(not= (:score %) (:stored-score %)) rows)]
     {:by-score (frequencies scores)
      :buckets  (->> scores
                     (group-by bucket-lo)
                     (reduce-kv (fn [m lo group] (assoc m lo (count group))) (sorted-map)))
      :count    n
      :min      (when (pos? n) (apply min scores))
      :max      (when (pos? n) (apply max scores))
      :mean     (when (pos? n) (double (/ (reduce + scores) n)))
      :median   (median scores)
      :stale-count    (count stale)
      :stale-contacts (mapv :id stale)})))

;; ───────────────────────── RBAC + entry point ──────────────────────────

(defn authorized?
  "Fail-closed RBAC check for viewing this dashboard, checked directly
  against `marketing.policy/permissions`'s `:marketing/view-dashboard`
  entitlement — see `docs/DESIGN.md`'s \"Dashboard\" section for why this
  aggregate-view capability IS gated even though it never routes through
  the MarketingOps-LLM -> ConsentGovernor -> commit pipeline (there is no
  LLM proposal to censor for a deterministic aggregate rollup — only an
  RBAC check applies, not the whole `marketing.operation` graph)."
  [actor-role]
  (contains? (get policy/permissions actor-role #{}) :marketing/view-dashboard))

(defn snapshot
  "The single entry point intended for real callers (services, UI
  backends): `context` is the SAME shape `marketing.operation/build`
  expects (`{:actor-id .. :actor-role ..}`, see `marketing.sim`). Returns
  `{:authorized? false :reason :rbac}` (never partial data — fail closed)
  when `authorized?` rejects the caller's `:actor-role`; otherwise the
  full aggregate view."
  [store {:keys [actor-role]}]
  (if (authorized? actor-role)
    {:authorized?             true
     :lifecycle-funnel        (lifecycle-funnel store)
     :conversion-rates        (conversion-rates store)
     :campaign-rollup         (campaign-rollup store)
     :lead-score-distribution (lead-score-distribution store)}
    {:authorized? false :reason :rbac}))

(defn coverage
  "Honest, machine-checkable report of what R0 actually covers."
  []
  {:model :point-in-time-aggregate-dashboard
   :note (str "R0 scope: a single point-in-time snapshot only, built by "
              "reading marketing.store directly -- no time-series, no "
              "trending, no caching layer. Lifecycle funnel/conversion "
              "rates inherit kotoba.crm.funnel's R0 limits (see its own "
              "coverage): no stage-history log, exited contacts excluded "
              "from reached-counts (this actor's contact shape has no "
              ":reached-stage fact). Lead-score distribution inherits "
              "kotoba.crm.leadscore's R0 limits (see its own coverage): "
              "a fixed weighted-point model only, undecayed. Campaign "
              "rollup only counts consent-revoked-send-gate rejections "
              "(not double-send-gate/stage-sequence-gate/rbac/low-"
              "confidence holds), matching the task this dashboard was "
              "built to serve; extend only by adding a genuinely new, "
              "documented metric, never by guessing.")})
