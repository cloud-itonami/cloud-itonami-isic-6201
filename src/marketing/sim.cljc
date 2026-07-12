(ns marketing.sim
  "Demo runner: push ten representative operations through one
  OperationActor and watch the ConsentGovernor + approval workflow earn
  the MarketingOps-LLM the right to send a contact a marketing message,
  advance a lead's lifecycle stage, or update a lead's score.

    op1  クリーンな送信(contact-100, opted-in)                          → commit
    op2  opted-out の contact への送信                                    → consent-revoked REJECT → hold
    op3  expired consent の contact への送信                              → consent-revoked REJECT → hold
    op4  opted-in だが unsubscribed? の contact への送信                  → consent-revoked REJECT → hold
    op5  既に送信済みの campaign/contact ペアへの再送信                    → double-send REJECT → hold
    op6  stage をスキップした遷移提案(lead → customer)                    → stage-sequence REJECT → hold
    op7  クリーンな stage 遷移(contact-100, lead → mql)                   → commit
    op8  engagement history と一致する lead-score 更新(contact-500 → 39)  → commit
    op9  engagement history と乖離する lead-score 更新(contact-500 → 90)  → escalate → approve → commit
    op10 権限のない role からの送信提案                                   → rbac REJECT → hold

  Run: clojure -M:dev:run"
  (:require [langgraph.graph :as g]
            [marketing.store :as store]
            [marketing.operation :as op]
            [marketing.facts :as facts]))

(defn- line [& xs] (println (apply str xs)))

(defn- run-op!
  [actor thread-id request context approve?]
  (let [res (g/run* actor {:request request :context context} {:thread-id thread-id})]
    (if (= :interrupted (:status res))
      (do (line "   ⏸  人間レビュー待ち (reason: "
                (-> res :state :audit last :reason) ")")
          (let [res2 (g/run* actor
                             {:approval {:status (if approve? :approved :rejected)
                                         :by "mktg-manager-1"}}
                             {:thread-id thread-id :resume? true})]
            (line "   ▶  " (if approve? "承認 → " "却下 → ") "disposition = "
                  (get-in res2 [:state :disposition]))
            res2))
      (do (line "   → disposition = " (get-in res [:state :disposition])
                "  (confidence " (get-in res [:state :verdict :confidence]) ")")
          res))))

(defn -main [& _]
  (let [db    (store/seed-db)
        actor (op/build db)
        marketer {:actor-id "mktr-1" :actor-role :marketer :phase 3}
        manager  {:actor-id "mgr-1" :actor-role :marketing-manager :phase 3}]

    (line "── R0 カバレッジ(正直な現状) ──")
    (line (pr-str (facts/coverage)))

    (line "\n── OperationActor (MarketingOps-LLM sealed; ConsentGovernor active) ──")

    (line "\nop1  クリーンな送信: camp-100 → contact-100 (opted-in)")
    (run-op! actor "op1"
             {:op :campaign/send-message :subject "contact-100"
              :campaign-id "camp-100" :contact-id "contact-100"}
             marketer true)

    (line "\nop2  opted-out の contact への送信: camp-100 → contact-200")
    (run-op! actor "op2"
             {:op :campaign/send-message :subject "contact-200"
              :campaign-id "camp-100" :contact-id "contact-200"}
             marketer true)

    (line "\nop3  expired consent の contact への送信: camp-100 → contact-300")
    (run-op! actor "op3"
             {:op :campaign/send-message :subject "contact-300"
              :campaign-id "camp-100" :contact-id "contact-300"}
             marketer true)

    (line "\nop4  opted-in だが unsubscribed? な contact への送信: camp-100 → contact-400")
    (run-op! actor "op4"
             {:op :campaign/send-message :subject "contact-400"
              :campaign-id "camp-100" :contact-id "contact-400"}
             marketer true)

    (line "\nop5  既に送信済みの campaign/contact ペア: camp-200 → contact-600(seed で送信済み)")
    (run-op! actor "op5"
             {:op :campaign/send-message :subject "contact-600"
              :campaign-id "camp-200" :contact-id "contact-600"}
             marketer true)

    (line "\nop6  stage をスキップした遷移: contact-100 :lead → :customer(direct)")
    (run-op! actor "op6"
             {:op :lead/advance-stage :subject "contact-100"
              :contact-id "contact-100" :to-stage :customer}
             marketer true)

    (line "\nop7  クリーンな stage 遷移: contact-100 :lead → :mql")
    (run-op! actor "op7"
             {:op :lead/advance-stage :subject "contact-100"
              :contact-id "contact-100" :to-stage :mql}
             marketer true)

    (line "\nop8  engagement history と一致する lead-score 更新: contact-500 → 39")
    (run-op! actor "op8"
             {:op :lead/update-score :subject "contact-500"
              :contact-id "contact-500" :score 39}
             marketer true)

    (line "\nop9  engagement history と乖離する lead-score 更新: contact-500 → 90")
    (run-op! actor "op9"
             {:op :lead/update-score :subject "contact-500"
              :contact-id "contact-500" :score 90}
             manager true)

    (line "\nop10 権限のない role(account-holder 相当)からの送信提案")
    (run-op! actor "op10"
             {:op :campaign/send-message :subject "contact-100"
              :campaign-id "camp-100" :contact-id "contact-100"}
             {:actor-id "guest-1" :actor-role :guest :phase 3} true)

    (line "\n── 監査台帳 (append-only; 誰が・何を・どの同意状態/lifecycle stage で送信/遷移/更新したか) ──")
    (doseq [f (store/ledger db)]
      (line "  " (store/ledger-line f)))

    (line "\ndone.")))
