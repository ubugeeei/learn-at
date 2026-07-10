# 06: XRPC を普通の HTTP として実装する

## この章のゴール

Lexicon の query と procedure を HTTP request に変換し、成功・protocol error・transport failure を区別します。公開 endpoint と認証 endpoint の両方に使える client core を作ります。

実装は `src/main/scala/learnat/xrpc/Xrpc.scala` にあります。

## XRPC は何を決めるのか

XRPC (Lexicon RPC) は AT Protocol の HTTP 規約です。endpoint 名に NSID を使います。

```text
GET  /xrpc/com.atproto.repo.getRecord
POST /xrpc/com.atproto.repo.createRecord
```

二種類の method があります。

| Lexicon type | HTTP | input | 意味 |
| --- | --- | --- | --- |
| query | GET | URL query parameter | cache 可能で state を変更しない |
| procedure | POST | request body | state を変更し得る |

「record を読むから GET、作るから POST」と名前から推測するのではなく、Lexicon definition の `type` を読みます。

## transport と protocol を分離する

JDK `HttpClient` を直接すべての client method から呼ばず、最小 interface を置きます。

```scala
trait HttpTransport:
  def send(request: HttpRequestData): Either[XrpcError, HttpResponseData]
```

production では `JdkHttpTransport`、test では request を記録して固定 response を返す `RecordingTransport` を使います。これにより次を network なしで test できます。

- GET / POST
- URI と query encoding
- header
- request body bytes
- status と error decoding

## service URL の不変条件

XRPC path は常に origin の top-level `/xrpc/` です。client 作成時に service URL を検証します。

```text
valid:   https://pds.example
valid:   http://localhost:2583
invalid: ftp://pds.example
invalid: https://pds.example/prefix
```

query と fragment も service URL には置けません。DID document から得た URL も untrusted input なので同じ check を通します。

## query parameter を encode する

parameter は UTF-8 bytes にして RFC 3986 unreserved character 以外を `%HH` にします。

```text
input: alice+test.example
wire:  alice%2Btest.example

input: 日本語
wire:  %E6%97%A5%E6%9C%AC%E8%AA%9E
```

HTML form 用 encoder は space を `+` にする場合があります。XRPC URI builder では percent encoding を明示的に実装しています。

同じ名前の parameter が複数回現れる array encoding を失わないよう、input は `Map` ではなく `Vector[(String, String)]` です。

## query

```scala
val method = Nsid.parse("com.atproto.identity.resolveHandle").toOption.get

client.query(
  method,
  Vector("handle" -> "alice.example.com")
)
```

wire request は次になります。

```http
GET /xrpc/com.atproto.identity.resolveHandle?handle=alice.example.com HTTP/1.1
Host: pds.example
Accept: application/json
```

## JSON procedure

```scala
client.procedure(
  Nsid.parse("com.atproto.repo.createRecord").toOption.get,
  Json.obj(
    "repo" -> Json.Str("did:plc:example"),
    "collection" -> Json.Str("com.example.note")
  ),
  bearerToken = Some(token)
)
```

body は UTF-8 JSON、`Content-Type` は `application/json`、token は `Authorization: Bearer ...` です。

## binary body

すべての XRPC が JSON ではありません。

- `com.atproto.sync.getRepo`: CAR response
- `com.atproto.repo.importRepo`: CAR input
- `com.atproto.repo.uploadBlob`: binary input
- `com.atproto.sync.subscribeRepos`: WebSocket event stream

そこで `queryBytes` と `procedureBytes` は parse 前の status / headers / bytes を返します。caller が content type に対応する codec を選びます。WebSocket は後の sync 章で別 transport として扱います。

## response と error

2xx は成功です。JSON method は body を JSON parser に渡します。parse できない 2xx は success にせず `InvalidResponse` です。

非 2xx の標準 error body は次です。

```json
{
  "error": "InvalidRequest",
  "message": "repo is required"
}
```

client は次を分けます。

```scala
enum XrpcError:
  case InvalidService(...)
  case Transport(...)
  case ResponseTooLarge(...)
  case InvalidResponse(...)
  case Remote(status, error, message)
```

retry の判断に重要です。DNS timeout と `InvalidRequest` を同じ例外として retry してはいけません。401 で token refresh、429/5xx で backoff、400 で input 修正、という policy はこの分類の上に置きます。

## size limit

client は response body の最大値を持ちます。現在の JDK transport は body を受信後に上限を確認するため、完全な streaming defense ではありません。本番 client では limited `BodySubscriber`、timeout、endpoint ごとの上限を追加すべきです。

CAR のような大きい response と、小さい JSON metadata に同じ上限を使わない設計も必要です。この教材では constructor で client ごとに上限を選べます。

## 実行して観察する

```console
$ nix develop --command sbt verify
```

XRPC test は fake transport に届いた `HttpRequestData` を調べます。実 network の可用性、rate limit、account に依存せず wire contract を確かめられます。

## 演習

1. query encoder を `java.net.URLEncoder` に替え、space と plus の結果を比較する。
2. 400 response を 2xx と同じ JSON として返す変更を行い、caller が成功と失敗を区別できなくなることを説明する。
3. `queryBytes` で `Accept: application/vnd.ipld.car` を指定する test を追加する。
4. fake transport が `XrpcError.Transport` を返す test を書き、remote error と表示を分ける。

## まだ実装していないもの

- Lexicon からの request / response validation と code generation
- retry / exponential backoff / rate-limit policy
- OAuth DPoP proof と nonce retry
- service proxy header の policy
- WebSocket event stream

これらを transport 内へ詰め込まず、上の層として追加します。

## 仕様

- [HTTP API (XRPC)](https://atproto.com/specs/xrpc)
- [Lexicon](https://atproto.com/specs/lexicon)

