(ns marketing.llm
  "MarketingOps-LLM client — the *contained intelligence node*.

  It normalizes an incoming send/stage-advance/score-update request into
  a proposal (which contact, which campaign, which lifecycle stage,
  what lead score). CRITICAL: it is a smart-but-untrusted advisor. It
  returns a *proposal*, never a committed send/stage-transition/score.
  Every output is censored downstream by `marketing.policy` (the
  ConsentGovernor) before anything touches the SSoT.

  Deterministic mock so the actor graph runs offline and the governor
  contract is exercised end-to-end. In production this calls a real LLM
  (kotoba-llm) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str
     :rationale  str
     :cites      [kw|str ..]
     :source     nil  ; reserved for shape-parity with sibling actors;
                       ; this actor's governor has no source-provenance
                       ; gate (see docs/DESIGN.md) so it is always nil
     :effect     kw
     :value      map|nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.model :as model]
            [marketing.store :as store]))

(defn- propose-send
  "Send proposal — the LLM only normalizes which campaign/contact pair
  is being requested (adds no consent judgment)."
  [_db {:keys [campaign-id contact-id]}]
  {:summary   (str "送信提案: campaign " campaign-id " → contact " contact-id)
   :rationale "同意状態・送信済みフラグの妥当性判断はあなたの責務ではありません(governor が判定)。"
   :cites     [:campaign-id :contact-id]
   :source    nil
   :effect    :send-record-upsert
   :value     {:campaign-id campaign-id :contact-id contact-id}
   :confidence 0.95})

(defn- propose-stage-advance
  "Lead lifecycle-stage transition proposal — the LLM only normalizes
  the target stage (adds no stage-sequence judgment)."
  [_db {:keys [contact-id to-stage]}]
  {:summary   (str "lifecycle stage 遷移提案: " contact-id " → " to-stage)
   :rationale "stage 遷移順序の妥当性判断はあなたの責務ではありません(governor が判定)。"
   :cites     [:contact-id :to-stage]
   :source    nil
   :effect    :stage-transition-upsert
   :value     {:contact-id contact-id :to-stage to-stage}
   :confidence 0.95})

(defn- propose-score-update
  "Lead-score update proposal — the LLM only normalizes the proposed
  figure (adds no engagement-recompute judgment)."
  [_db {:keys [contact-id score]}]
  {:summary   (str "lead score 更新提案: " contact-id " → " score)
   :rationale "engagement history からの recompute 照合はあなたの責務ではありません(governor が判定)。"
   :cites     [:contact-id :score]
   :source    nil
   :effect    :score-update-upsert
   :value     {:contact-id contact-id :score score}
   :confidence 0.9})

(defn infer
  [db {:keys [op] :as request}]
  (case op
    :campaign/send-message (propose-send db request)
    :lead/advance-stage    (propose-stage-advance db request)
    :lead/update-score     (propose-score-update db request)
    {:summary "未対応の操作" :rationale (str op) :cites [] :source nil
     :effect :noop :confidence 0.0}))

;; ───────────────────────── Advisor protocol ─────────────────────────

(defprotocol Advisor
  (-advise [advisor store request] "store + request → proposal map"))

(defn mock-advisor
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたはマーケティングオートメーションSaaSプラットフォームの"
       "MarketingOpsアドバイザーです。与えられた事実のみに基づき、提案を1つだけ "
       "EDN マップで返します。説明や前置きは一切書かず、EDN だけを出力します。\n"
       "キー: :summary :rationale :cites :source(常に nil) "
       ":effect(:send-record-upsert|:stage-transition-upsert|:score-update-upsert) "
       ":value :confidence(0..1)。\n"
       "重要: 同意(consent)状態の妥当性判断、二重送信の判定、lifecycle stage "
       "遷移順序の妥当性判断、lead score の正しさの判断はあなたの責務ではありません"
       "(governor が判定します)。"))

(defn- facts-for [st {:keys [contact-id subject]}]
  {:contact (store/contact st (or contact-id subject))})

(defn- parse-proposal
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :source nil :effect :noop :confidence 0.0})))

(defn llm-advisor
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace [request proposal]
  {:t          :marketingllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :source     (:source proposal)
   :confidence (:confidence proposal)})
