# 実装スコープと完成条件

## 方針

参照実装は学習可能性、相互運用性、安全な境界の順に最適化します。短いこと自体は目的ではありません。protocol の本質ではない plumbing は JDK を使い、本質的な wire format は実装を見える状態にします。

## 依存ポリシー

runtime / test dependency は原則ゼロです。

| 領域 | 選択 | 理由 |
| --- | --- | --- |
| HTTP client | `java.net.http.HttpClient` | HTTP stack の再実装は学習対象外 |
| HTTP server | JDK `HttpServer` | routing と XRPC の関係を隠さない |
| JSON | 自作 | Lexicon value と error handling を理解する |
| DNS | JNDI DNS provider | wire-level DNS client は identity の本筋ではない |
| crypto primitives | JCA | primitive の自作は危険。署名形式変換は実装する |
| DAG-CBOR / CID / CAR | 自作 | repository interoperability の中心 |
| database | file-backed block/state store | 永続化境界を観察できるようにする |
| test | 小さな自作 runner | production code に test framework を要求しない |

## protocol core と application の境界

`com.atproto.*` の identity、server、repo、sync を core として扱います。`app.bsky.*` は client 演習で使用しますが、timeline algorithm や moderation UI を PDS の責務には入れません。

## 段階別の完成条件

### 教材 PDS

- 単一 process、少数 account
- file-backed state と content-addressed block store
- P-256 repository signing key
- create / put / delete / get / list record
- repository CAR export/import
- event sequence と resumable stream
- legacy session と app password
- local HTTPS reverse proxy の背後で動作可能

### interoperability client

- handle / DID resolution と双方向検証
- public XRPC query
- legacy session を使う明示的な開発モード
- OAuth discovery と authorization code flow
- CAR と repository signature verification
- reconnect、cursor、full resync

### この参照実装だけでは production-ready と呼ばない条件

以下は protocol の理解を越えて運用組織・policy・継続的 security work が必要です。

- internet-facing OAuth authorization UI の hardening
- distributed database、multi-process transaction、large blob storage
- email verification、account recovery、PLC rotation key custody
- abuse prevention、rate limit、spam detection、moderation operations
- Relay/AppView の internet-scale indexing
- SLO、on-call、disaster recovery、privacy/legal compliance

教材の endpoint が応答することを、これらが満たされた証拠にはしません。

## 仕様追従

仕様ごとのテストを分けます。

- `syntax`: 公式 interop fixtures の valid / invalid case
- `codec`: canonical bytes と round trip
- `crypto`: 公式 signature fixtures
- `repo`: insertion-order independence、proof、tamper rejection
- `xrpc`: Lexicon request/response examples
- `e2e`: client -> PDS -> export -> verifier

教材を更新するときは、参照した公式 repository commit と確認日を README で更新します。

