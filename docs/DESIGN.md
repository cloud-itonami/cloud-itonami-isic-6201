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

## 9. Dashboard(`src/marketing/dashboard.cljc`)— 最初の aggregate-view

上記 §8 の「disclosure op が無い」は今も真だが、それとは別の種類の read
capability として `marketing.dashboard` を追加した: **単一レコードの
governed disclosure ではなく、store 全体を横断した aggregate rollup**
(lead lifecycle funnel・conversion rate・campaign send/rejection
rollup・lead-score distribution)。`kotoba.crm.funnel` 自身の docstring が
引く区別と同型 — "funnel は各 actor の `crm.report`(1レコードずつの
governed disclosure rendering)とは別概念。funnel は1レコードを
renderしない、多数のレコードを横断して集計するだけ" — であり、
`marketing.dashboard` はまさにその aggregate 側にあたる。

**中身は `kotoba.crm.funnel`/`kotoba.crm.leadscore` と同じ性質の pure・
storage-agnostic 関数群**(I/O なし、store は呼び出し側が渡す)。lead
score は常に `kotoba.crm.leadscore/recompute-score` で再計算し、格納済み
`:lead-score` を一度も信用しない — `marketing.policy` の
`lead-score-mismatch` gate と同じ「片側を recompute して比較」規律を
read 側にも適用している。campaign rollup は ConsentGovernor が既に生成
している ledger fact(commit / `consent-revoked-send-gate` hold)を集計
するだけで、新しい tracking は発明していない。

### RBAC gating の決定と理由

**この aggregate view には RBAC gate を掛けた**(`marketing.dashboard/
authorized?` が `marketing.policy/permissions` の新規entitlement
`:marketing/view-dashboard` を直接チェックする、`:marketer`/
`:marketing-manager` 双方に付与)。理由:

1. `marketing.phase` 自身が「この actor には5820の`:disclosure/query`の
   ような read op が無いため phase 0 が全writeをholdする最も保守的な床」
   と明記しており、read アクセスへの姿勢が「無条件公開」ではなく
   「まだ存在しない」だったことを示している——read capability を追加する
   以上、既存の permissions 語彙(`:marketer`/`:marketing-manager` が
   write 3opに持つのと同じ table)に沿って明示的に守るのが筋が通る。
2. 一方で、**dashboard は `marketing.operation`(MarketingOps-LLM →
   ConsentGovernor → commit の StateGraph)を一切通らない**——決定論的な
   集計であり LLM proposal が存在しないため、検閲すべき proposal が無い。
   よって governance は「フル graph を通す」のではなく「RBAC check
   だけを直接呼ぶ」形にした(`policy/check`のrbac-violations相当のロジック
   を dashboard 側で再利用する軽量版)。5820 の `:disclosure/query` が
   フル graph(license/column-scope gate込み)を通るのとはこの点で異なる
   —— dashboard には column-scope的な粒度制御もない(store全体の集計のみ、
   1レコード開示ではないため)。
3. `:marketer`/`:marketing-manager` は既存 write 権限が完全に同一集合
   なので、新規 read entitlement もこの2roleに同じスコープで付与した
   (差別化する理由が無い)。`:guest`等の未登録 role は fail-closed で
   拒否(`{:authorized? false :reason :rbac}`、部分データを絶対返さない)。

### 実装過程で見つかった実バグ(fix済み)

`marketing.policy/hold-fact` が REJECTされた送信の ledger fact に
`:campaign-id` を一切含んでいなかった(`:op`/`:subject`(=contact-id)/
`:basis` のみ)。dashboard の campaign rollup が「拒否件数をどの campaign
に帰属させるか」を ledger だけから復元できない——`request` には既に
`:campaign-id` が来ているのに、hold-fact 構築時に捨てられていた実バグ。
`marketing.policy/hold-fact` と(対称性のため)`marketing.operation`の
private `commit-fact` の両方に `:campaign-id`/`:contact-id` を
`cond->` で追加して修正した(既存テストのアサーションは特定キーの
spot-check のみで全体equality比較はしていないため non-breaking)。
修正前の(`:campaign-id` を持たない)過去 ledger fact は dashboard 側で
`:unknown` バケットに集計し、黙って落とさない。
