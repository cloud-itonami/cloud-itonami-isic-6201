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
