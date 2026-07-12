# ADR-0001: cloud-itonami-isic-6201 — MarketingOps-LLM を封じ込めた知能ノードとするマーケティングオートメーション SaaS actor 設計

- Status: Accepted (2026-07-12)
- 関連: `cloud-itonami-isic-5820`(RevOps-LLM ⊣ SubscriptionGovernor、直接
  の構造的手本 — sales/subscription 側の sibling)、`cloud-itonami-isic-
  6920`(double-guard を dedicated boolean で行う設計の教訓元、
  ADR-2607071351)、`cloud-itonami-isic-7310`(広告代理店 actor、業種として
  の区別対象)、`kotoba-lang/crm`(`kotoba.crm.pipeline` 共有 + この build
  で新規追加した `kotoba.crm.leadscore`)、langgraph-clj ADR-0001

## 課題

ISIC Rev.4 6201「Computer programming activities」は広いコードであり、
単純な relabeling を避けるため、**マーケティングオートメーション SaaS
プラットフォーム事業**(HubSpot Marketing Hub/Salesforce Marketing Cloud
クラス)に narrow した。MarketingOps-LLM に送信確定・lifecycle stage
確定・lead score 確定を直接行わせると、同意(consent)を無視した送信に
よる CAN-SPAM/GDPR/CASL 違反・二重送信によるスパム化・lifecycle の
不正な進行・誤った lead score の sales への伝播のリスクがある。

オーナー指示「business modelごとに `cloud-itonami-*` を分ける」に従い、
`cloud-itonami-isic-5820`(sales/subscription 側)とは別の actor として
実装する — マーケティング側(campaigns/sends/lead scoring/lead
lifecycle)は 5820 に統合しない、独立した sibling actor とする。また
`cloud-itonami-isic-7310`(広告代理店、AdOps-LLM ⊣ Campaign Governor)
とも業種として明確に区別する: 7310 はクライアントの広告を買い付ける
サービス業(代理店)だが、この actor はソフトウェアプラットフォームで
あり、広告買い付けやクライアント予算の代行運用は一切行わない。

## 決定

MarketingOps-LLM は proposal のみを返す助言者とし、独立した
ConsentGovernor がすべての送信・lifecycle stage 遷移・lead score
更新を検閲する。**単一不変条件**: MarketingOps-LLM は、ConsentGovernor
が拒否する送信・stage 遷移・score 更新を決して行わない。

domain-unique HARD チェック2つ(この fleet で新規):
`consent-revoked-send-gate`(送信対象 contact の同意記録が取消/期限切れ、
または独立した unsubscribe/suppression フラグが立っている場合に送信を
拒否 — authorization-to-contact 妥当性チェックという新しい check kind。
実在する規制のみを引用: CAN-SPAM Act 15 U.S.C. §7704、EU GDPR
Art. 6(1)(a) + Art. 7、Canada's CASL S.C. 2010 c.23)、
`stage-sequence-gate`(lead lifecycle stage のスキップ/逆行を拒否 —
`kotoba-lang/crm`の`kotoba.crm.pipeline`を再利用、5820 と同じ汎用ロジック)。

`double-send-gate` は 6920 の教訓(status-lifecycle バグ)を踏まえ、
`:status`/`:lifecycle-stage` 値ではなく専用 `:sent?` boolean(per
campaign/contact ペア)で二重送信を防止する。同様に、同意状態も
`:lifecycle-stage` から推測せず、専用の `:consent-status`(enum)+
`:unsubscribed?`(独立 boolean)の2fact で表現する — これも 6920 の
教訓の直接適用。

`lead-score-mismatch` gate(SOFT、常時 escalate)は、この build で
`kotoba-lang/crm` に新規追加した `kotoba.crm.leadscore`(決定論的
weighted-point recompute)を使い、提案された lead score が engagement
history からの recompute と乖離したら確信度に関わらず人間承認へ回す
(5820 の `revenue-mismatch-imminent` と同じ「片側を recompute して
比較」ファミリーの拡張)。

## kotoba-lang/crm への新規追加

この build で `kotoba-lang/crm` に `kotoba.crm.leadscore` を新規追加
した。`kotoba.crm.pipeline`(5820 と共有済み)に加え、lead-scoring
recompute というこの actor 固有の新しい技術commonsを切り出し、将来の
customer-service 系 sibling actor が同じロジックを再導出せず再利用
できるようにした。

## Consequences

- (+) `kotoba-lang/industry` registry 6201 スロットが実装へ昇格。
- (+) narrowing 判断を明記(computer programming activities 全体の
  relabeling を回避)。
- (+) `consent-revoked-send-gate`/`stage-sequence-gate` はこの fleet の
  check-kind 語彙への genuine な追加(stage-sequence は 5820 と同型だが
  domain が異なる独立適用)。
- (+) `kotoba-lang/crm` の `kotoba.crm.leadscore` は marketing 系
  sibling actor が再利用できる最初の lead-scoring 技術commons。
- (+) `MemStore` ‖ `DatomicStore` parity は
  `test/marketing/store_contract_test.clj` で証明。
- (+) 7310(広告代理店)・5820(sales/subscription CRM)との業種区別を
  README/business-model.md に明記。
- (-) R0 は3consent状態のみ、線形5-stage lifecycle(+3 exit)のみ
  (ブランチ/並行stageは対象外)。
- (-) lead scoring は fixed weighted-point モデルのみ、ML/predictive
  scoring・per-account custom weight・非明示的な inactivity decay は
  対象外。
- (-) customer-service hub は sibling actor として未着手(5820 と同様、
  README/business-model.md にロードマップとして明記)。

## 代替案と不採用理由

| Option | Verdict | Reason |
|---|---|---|
| ISIC 6201 を「computer programming activities 全般」のまま実装 | ❌ | 5820等が確立した narrowing 規律に反する。scope が際限なく広がる |
| マーケティング機能を `cloud-itonami-isic-5820` に統合 | ❌ | オーナー指示「business modelごとに設計」に反する。この fleet の
  one-business-model-per-actor 規律にも反する |
| この actor を広告代理店(7310)と同一視/統合 | ❌ | 業種が異なる(SaaS プラットフォーム vs 広告代理店サービス業)。
  リスクプロファイルも異なる(自社プラットフォームの consent 遵守 vs
  クライアント予算の代行執行) |
| 同意状態を `:lifecycle-stage` の値から推測 | ❌ | ADR-2607071351 で確認済みの status-lifecycle バグと同じ罠 |
| 二重送信を `:status` 値だけで判定 | ❌ | 同上の罠。専用 `:sent?` boolean が必要 |

## References

- `cloud-itonami-isic-5820/docs/adr/0001-architecture.md`(直接の構造的
  手本、sales/subscription 側の sibling)
- ADR-2607071351(`cloud-itonami-isic-6920`、double-guard 設計の教訓元)
- `kotoba-lang/crm`(`kotoba.crm.pipeline` 共有 + この build で新規
  追加した `kotoba.crm.leadscore`)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)

## Addendum(2026-07-13): `src/marketing/llm_realmodel.clj` — 実モデル呼び出し
adapter(honest gap 解消、ただし実呼び出し自体は未検証)

### 課題

`src/marketing/llm.cljc` の MarketingOps-LLM advisor は SEALED/決定論的な
mock(`marketing.llm/mock-advisor`/`marketing.llm/infer`)であり、実際の
言語モデルを一切呼ばない。これは本番運用へ向けた既知の gap であり、後で
operator が実クレデンシャルを与えたときに actor を実モデルへ向けられる
経路が無かった。**本 sandbox には実モデル API のクレデンシャルが一切無い**
(`ANTHROPIC_API_KEY`/`OPENAI_API_KEY` 等、env に確認済みで無し)ため、
この addendum の目的は「実呼び出しを行う」ことではなく「operator が後で
クレデンシャルを与えたときに動く ADAPTER を配線する」ことに限定される。
直接の手本は sibling `cloud-itonami-isic-5820` が同じ日に得た
`src/crm/llm_realmodel.clj`(同一 build 内で先行実装・push 済み)。

### 決定

`orgs/gftdcojp/cloud-itonami`(同一 lineage/org の別 repo、より広い
"business-os" cloud-itonami 本体)の `cloud_itonami.runtime` 名前空間と
`cloud-itonami-isic-5820` の `crm.llm-realmodel` が既に確立していた
`{ITO,ISIC5820}_MODEL_PROVIDER`/`_URL`/`_MODEL`/`_MODEL_API_KEY` という
env-var 駆動の convention をそのまま踏襲し(非互換な新規 shape を発明
しない)、`ISIC6201_`-prefix 版として `src/marketing/llm_realmodel.clj`
(JVM-only、`marketing.http`/`marketing.file-store` と同じ理由——実 HTTP
I/O は kotoba-wasm/clojurewasm/cljs/nbb 層に portable primitive が無い
インフラ glue)に実装した。

**graph-facing contract は一切変更しない**: `marketing.llm.cljc` は既に
`marketing.llm/llm-advisor`(任意の `langchain.model/ChatModel` を
`marketing.llm/Advisor` protocol でラップする既存の汎用関数)を持って
いた——`marketing.operation/build`の`:advise`ノードが呼ぶ shape・返す
proposal shape は `mock-advisor` と完全に同一(`:source` は常に `nil`
——このactorにはsource-provenance gateが無いという既存の設計を維持)。
`marketing.llm-realmodel/real-advisor` は `real-chat-model`
(`langchain.model/openai-model`/`anthropic-model`——両方とも
`kotoba-lang/langchain` 側で既に汎用実装・テスト済み——を provider に
応じて呼び分けるだけ)を `llm-advisor` でラップして返すのみで、
`marketing.llm`側のsystem-prompt・fact抽出・EDN parse ロジックを一切
複製しない。

`marketing.http/resolve-advisor!` が唯一のトリガー: `$ISIC6201_MODEL_API_KEY`
が set かつ non-blank なら real advisor、そうでなければ既存の sealed
mock(`marketing.operation/build`自身の既定と同一)——起動時に選んだ
モードを `marketing.llm-realmodel/preflight`(API key の値は一切含まない、
`:api-key?` boolean のみ)と共に必ず print する。`warn-ephemeral-store!`
が確立した"fail-visible"規律をそのまま advisor 選択にも適用した。加えて
`marketing.operation/build`は既に`:advisor`optを受け付ける実装だった
(`crm.operation/build`と同型)ため、`marketing.http/start-server!`側の
変更のみで済み、`marketing.operation.cljc`自体への変更は不要だった。

### 検証したこと・していないこと(正直な線引き)

- ✅ `preflight` の missing/present 判定ロジック——provider 別
  (openai/anthropic/openclaw)・url/key の有無・unknown provider・
  blank env value の全パターンをクレデンシャル無しで検証
  (`test/marketing/llm_realmodel_test.clj`)。
- ✅ 実際に送信する HTTP リクエストの wire shape(method・bearer
  header・JSON body の model/messages フィールド)と、レスポンス
  parse——ただし相手は**本物の実モデル API ではなく、この build 内で
  起動したローカル `org.httpkit.server` stub**(実 socket 越しの実
  HTTP round-trip。`marketing.http_test.clj` が自分自身のサーバーを
  検証するのと同じ手法をクライアント側に転用)。`marketing.llm/
  llm-advisor` -> `marketing.llm/parse-proposal` を経由した proposal
  生成(lifecycle stage 遷移提案、`:source nil` を含む)、および EDN
  として parse できないモデル応答への fallback(`:noop`/confidence
  0.0)経路まで含めて検証済み。
- ❌ **実モデル API(OpenAI/Anthropic/実際の OpenAI 互換ゲートウェイ)が
  この request shape を実際に受理し、期待通り応答するか**は、この
  sandbox にクレデンシャルが存在しないため**検証不能・未検証のまま**。
  偽の endpoint をでっち上げてもこれは証明できないので、行っていない。
  `ISIC6201_MODEL_API_KEY` を実際に設定した operator は、genuinely
  配線された adapter を得るが、その実呼び出し挙動は operator 自身が
  検証するまで未検証のままである。

### Consequences

- (+) `marketing.operation`/`marketing.policy`/`marketing.phase` の
  governance 経路は一切変更なし——advisor の実装が mock から real に
  変わるだけで、ConsentGovernor の censorship・phase gate・監査台帳は
  完全にそのまま。
- (+) この org 内で3つ目(`cloud-itonami` 本体・`cloud-itonami-isic-5820`
  に続く)の `ITO_MODEL_*`/`ISIC*_MODEL_*` 実装——同一 shape の再利用
  により将来の sibling actor(customer-service hub 等)が同じ pattern
  を再発明せず流用できる。
- (-) 実モデル呼び出し経路は end-to-end 未検証(上記)。本番投入前に
  operator が実クレデンシャルで自ら検証する必要がある。
- (-) tool-calling(構造化出力用の JSON schema tool 定義)は本 addendum
  では配線していない——`marketing.llm`の既存 system-prompt は「EDN の
  みを返せ」という自然言語指示に依存しており(mock advisor と同じ
  contract)、`langchain.model`のtool-calling機構(`langchain.tool`)は
  今回未使用。実モデルの出力が安定して EDN にならない場合の改善余地と
  して残す。

### References(追加)

- `cloud-itonami-isic-5820` `src/crm/llm_realmodel.clj` +
  `docs/adr/0001-architecture.md`のAddendum(直接の手本、同日実装)
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/runtime.cljc`
  (`model-config`/`model-preflight`/`real-model`/`jvm-http-fn` ——
  この addendum が踏襲した根本の手本)
- `kotoba-lang/langchain` `src/langchain/model.cljc`(`anthropic-model`/
  `openai-model`——実際の HTTP リクエスト構築・レスポンス parse は
  ここに既に汎用実装済みで、本 addendum はこれを呼ぶだけ)
