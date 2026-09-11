(ns marketing.phase
  "Phase 0→3 staged rollout. Where the ConsentGovernor answers 'is this
  allowed?', the phase answers 'how much autonomy does the actor have
  *yet*?'. It can only ever make the actor MORE conservative than the
  governor.

    Phase 0  read-only        — no writes at all. This actor has no
                                read-only op of its own (unlike
                                `cloud-itonami-isic-5820`'s
                                `:disclosure/query`), so phase 0 holds
                                EVERY operation — the most conservative
                                floor.
    Phase 1  assisted-send    — `:campaign/send-message` allowed, every
                                send needs human approval.
    Phase 2  assisted-full    — adds `:lead/advance-stage` and
                                `:lead/update-score` (still
                                approval-only).
    Phase 3  supervised auto  — governor-clean, high-confidence ops may
                                auto-commit.

  A `lead-score-mismatch` verdict escalates unconditionally at every
  phase (it is resolved in `marketing.policy/check` before phase logic
  even runs), the same discipline `cloud-itonami-isic-5820` applies to
  `revenue-mismatch-imminent`."
  )

(def write-ops #{:campaign/send-message :lead/advance-stage :lead/update-score})

(def phases
  {0 {:label "read-only"       :writes #{}                       :auto #{}}
   1 {:label "assisted-send"   :writes #{:campaign/send-message}  :auto #{}}
   2 {:label "assisted-full"   :writes write-ops                  :auto #{}}
   3 {:label "supervised-auto" :writes write-ops                  :auto write-ops}})

(def default-phase
  "The phase used when `context` carries no :phase at all. This is
  directly reachable by any ordinary caller that simply omits :phase —
  not just malformed/malicious input — so it must be the MOST
  CONSERVATIVE phase reachable by ordinary use, never the most
  permissive (the same fail-open bug class sibling actors have found
  and fixed across this fleet)."
  1)

(defn gate
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)      {:disposition :hold :reason nil}
      (not (contains? writes op))         {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))     {:disposition :escalate :reason :phase-approval}
      :else                               {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
