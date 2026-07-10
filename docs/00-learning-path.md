# 00: 学習ロードマップ

## このハンズオンの読み方

章は依存関係の順に並んでいます。知らない単語を暗記してから進む必要はありません。各段階で「いま何を識別し、誰を信頼し、どのバイト列を運んでいるか」を確認してください。

各章の作業は次のサイクルで進みます。

1. 目的を一文で言う。
2. wire format またはデータ構造を手で観察する。
3. 最小コードを書く。
4. 正常系を実行する。
5. 1 byte または 1 field を壊し、拒否されることを確認する。
6. 仕様の MUST / SHOULD と実装上の選択を分けて説明する。

## 全体像

| Phase | 章 | 作るもの | 完了時に説明できること |
| --- | --- | --- | --- |
| 0 | 00–03 | 開発環境、メンタルモデル、Scala 入門 | protocol と application の境界 |
| 1 | 04–06 | JSON、識別子、XRPC client | `at://` と `/xrpc/` が指すもの |
| 2 | 07–08 | handle / DID resolver、Lexicon validator | account の PDS を独立に発見する方法 |
| 3 | 09–12 | DAG-CBOR、CID、CAR、MST | repository が改ざんを検出できる理由 |
| 4 | 13 | 署名付き repository | DID の鍵で commit を検証する方法 |
| 5 | 14–15 | CLI client、最小 PDS | client-server の読み書き全体 |
| 6 | 16 | export/import、firehose consumer | batch と streaming sync の役割分担 |
| 7 | 17 | legacy session、OAuth | 認証主体、認可、token binding の違い |
| 8 | 18–19 | federation 演習、production checklist | AppView / Relay を含む運用境界 |

## 推奨順序

### Phase 0: 迷子にならない地図を作る

- [01: AT Protocol のメンタルモデル](01-mental-model.md)
- [02: Nix で学習環境を作る](02-environment.md)
- [03: Scala 3 と型で不変条件を表す](03-scala-foundations.md)

この段階では「分散型」という言葉を曖昧に使わず、identity、hosting、data、indexing が別々に移動・検証できることを捉えます。

### Phase 1: 普通の HTTP として触る

- [04: 依存ゼロ JSON codec](04-json.md)
- [05: DID / handle / NSID / AT URI / record key / TID](05-identifiers.md)
- [06: XRPC query と procedure](06-xrpc.md)

最初の成果物は公開 endpoint を呼べる CLI です。認証も CBOR もまだ要りません。

### Phase 2: account からサーバーを発見する

- [07: handle と DID の双方向検証](07-identity.md)
- 08: Lexicon と schema-driven API

入力された handle をそのまま URL として信頼してはいけない理由と、DID document から PDS endpoint と署名鍵を得る流れを実装します。

### Phase 3: repository のバイト列を理解する

- 09: deterministic DAG-CBOR
- 10: multihash / CID / multibase
- 11: CAR v1
- 12: Merkle Search Tree

ここが protocol の中心です。便利な CBOR/IPLD ライブラリで隠さず、限定した正しい codec を作ります。

### Phase 4: repository を認証する

- 13: P-256、署名、commit、revision

レコード単体ではなく、repository root に署名する意味を確認します。

### Phase 5: client と PDS を接続する

- 14: 実用 CLI client
- 15: 最小 PDS

`createRecord` から commit、block store、`getRecord`、`getRepo` までを一本につなぎます。

### Phase 6: データを転送する

- 16: CAR export/import と event stream

初回同期は repository export、差分追従は firehose、欠落時は再同期という状態機械を作ります。

### Phase 7: 安全に権限を渡す

- 17: legacy session から OAuth へ

学習順序として legacy `createSession` を先に扱いますが、ユーザー向けアプリケーションの最終到達点は [AT Protocol OAuth profile](https://atproto.com/specs/oauth) です。

### Phase 8: production readiness を判断する

- 18: AppView / Relay / Labeler との連合
- 19: 永続化、鍵、abuse、moderation、監視、backup

ここでは「動く PDS」と「インターネットへ安全に公開できる PDS」の差をチェックリスト化します。

## 学習完了の判定

次を資料なしでホワイトボードに描き、実際の失敗をログから切り分けられれば完了です。

1. `alice.example.com` から DID、DID document、PDS へ至る解決経路
2. JSON record から DAG-CBOR block、CID、MST root、signed commit、CAR へ至る変換
3. client、PDS、Relay、AppView の request と data flow
4. OAuth authorization code flow における discovery、PAR、PKCE、DPoP nonce
5. PDS 停止・移行・鍵 rotation・stream gap のそれぞれで再検証する対象
