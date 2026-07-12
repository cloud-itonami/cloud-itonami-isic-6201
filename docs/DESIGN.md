# Marketing-Automation SaaS Platform Actor Design

MarketingOps-LLM を最下層ノードに封じ込め、ConsentGovernor(独立系統)が
consent 状態(authorization-to-contact)・二重送信・lead lifecycle stage
順序・lead score 整合性を検閲する構図。HubSpot Marketing Hub/Salesforce
Marketing Cloud クラスのマーケティングオートメーション SaaS プラットフォーム
事業を ISIC Rev.4 6201(Computer programming activities)に narrow して
実装した。`cloud-itonami-isic-5820`(RevOps-LLM ⊣ SubscriptionGovernor、
sales/subscription 側)の sibling — 同じ `kotoba-lang/crm` 技術commonsを
共有するが、オーナー指示「business modelごとに actor を分ける」に従い別 actor
として実装(5820 に統合しない)。

## 1. なぜ actor 層が要るのか

マーケティング配信/lead 管理は LLM で加速できるが、**最終的な確定権限を
持たせるのは危険**:

| LLM が起こしうる失敗 | 帰結 |
|---|---|
| 同意を取り消した/unsubscribe 済みの contact への送信 | CAN-SPAM/GDPR/CASL 違反 |
| 同一 campaign を同一 contact へ二重送信 | スパム化・信頼毀損 |
| lifecycle stage をスキップして進行を確定 | パイプライン整合性の空洞化 |
| engagement history と乖離した lead score を確定 | sales への誤ったシグナル伝播 |

## 2. OperationActor(`src/marketing/operation.cljc`)

```
intake → advise → govern → decide ─┬─ commit
                                   ├─ escalate ─▶ request-approval → commit|hold
                                   └─ hold
```

## 3. ConsentGovernor(`src/marketing/policy.cljc`)

優先順位(HARD は人間承認でも上書き不可):

1. rbac
2. **consent-revoked-send-gate**(この fleet で新規の check kind) —
   `:consent-status` が `:opted-in` でない、または独立した
   `:unsubscribed?` フラグが立っている contact への送信提案を拒否。
   実在する規制根拠のみを引用: CAN-SPAM Act 15 U.S.C. §7704(unsubscribe
   遵守・送信者identification)、EU GDPR Art. 6(1)(a) + Art. 7(consent
   は撤回可能な適法根拠)、Canada's CASL S.C. 2010 c.23(明示/黙示同意、
   unsubscribe機構の義務化)。
3. **double-send-gate** — 専用 `:sent?` boolean で (campaign, contact)
   ペアの二重送信を防止(6920 の教訓と同じ設計)。
4. **stage-sequence-gate**(新規 check kind) — `kotoba.crm.pipeline` に
   よる lead lifecycle stage 遷移の正当性(スキップ不可)。
5. 確信度フロア(SOFT)
6. **lead-score-mismatch**(SOFT、常時 escalate) — `kotoba.crm.leadscore`
   の engagement history recompute と乖離したら常に人間承認へ回す
   (5820 の revenue-mismatch-imminent と同じ「片側を recompute して
   比較」ファミリー)。

## 4. SSoT(`src/marketing/store.cljc`)

contacts(consent-status/unsubscribed?/lifecycle-stage/lead-score)・
campaigns(name/channel)・sends([campaign-id contact-id] → 専用
`:sent?` boolean)・engagement history(contact-id → event 列、
`kotoba.crm.leadscore` recompute 用)・append-only ledger。

## 5. R0(`src/marketing/facts.cljc`)

3状態 consent カタログ(opted-in/opted-out/expired) + 独立
unsubscribed? フラグ + 5-stage 線形 lead lifecycle(+3 exit stages)。

## 6. Phase 0→3(`src/marketing/phase.cljc`)

`default-phase` = 1(保守的、5820 と同じ規約)。phase 0 はこの actor に
disclosure 相当の read op が無いため全 write を hold する最も保守的な
床。phase 1 で `:campaign/send-message` のみ有効化(承認必須)、phase 2
で `:lead/advance-stage`/`:lead/update-score` を追加(承認必須)、phase 3
で governor-clean かつ確信度十分な場合のみ自動commit。`lead-score-
mismatch` は governor 段階で escalate? が確定するため、phase の auto
セットに関わらずどの phase でも(そのopが有効な phase であれば)必ず
人間承認へ回る。

## 7. 技術的共通(`kotoba-lang/crm`)

`kotoba.crm.pipeline`(汎用 stage 遷移検証、5820 と共有)と
`kotoba.crm.leadscore`(決定論的 weighted-point lead-scoring recompute
— この build で新規追加され、この actor がその最初の consumer)は
actor 固有ロジックではなく kotoba-lang の技術commonsとして配置されて
いる。

## 8. `report.cljc` を持たない理由

`cloud-itonami-isic-5820` の `crm.report` はガバナンス済み disclosure
(`:disclosure/query`)の列レンダリングだった。この actor には disclosure
op が存在しない(send/stage-advance/score-update の3op のみ)ため、
存在しないガバナンス経路のために `report.cljc` を新設することはしない
— 未使用のコードパスを追加するより、必要になった時点で明示的に設計する
方針(honesty over coverage)。
