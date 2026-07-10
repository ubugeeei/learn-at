# 07: handle と DID を双方向に検証する

## この章のゴール

ユーザーが入力した handle から DID、DID document、PDS、repository signing key を発見します。片方向の名前解決を account identity として信頼してはいけない理由を、攻撃例と code で説明できるようにします。

実装は `src/main/scala/learnat/identity/Identity.scala` にあります。

## identity graph

```text
handle --DNS TXT / HTTPS--> DID
   ^                         |
   |                         | resolve did:plc / did:web
   |                         v
   +---- alsoKnownAs ---- DID document
                             |  service #atproto_pds
                             +----------------------> PDS URL
                             |  verification #atproto
                             +----------------------> signing key
```

四つを一度に「profile」と呼ばず、edge ごとの authority と freshness を考えます。

## handle から DID

### DNS TXT

推奨される方式は `_atproto.<handle>` の TXT record です。

```dns
_atproto.alice.example.com. TXT "did=did:plc:example123"
```

`did=` 以外の TXT record は無視します。有効な record が複数あり、異なる DID を示す場合は ambiguous として失敗します。たまたま最初に返った値を選ぶと、DNS server ごとの順序で identity が変わります。

JDK 実装は JNDI DNS provider を使います。TXT response は quoted chunk になる場合があるため、JDK が返す quote を取り除いてから prefix を確認します。

### HTTPS well-known

DNS で有効な DID を得られなければ次を取得します。

```http
GET /.well-known/atproto-did HTTP/1.1
Host: alice.example.com
```

2xx の text body を trim し、DID parser に渡します。real-world resolution は HTTPS default port が必須です。HTTP は explicit な local-development config で `.test` / `.localhost` を使う場合だけ許可します。

DNS と HTTPS が別の DID を返す場合、仕様では DNS を優先できます。この実装はまず DNS を試し、有効な単一 DID があれば HTTPS を呼びません。

## handle の resolution policy

syntax として valid でも、resolution してはいけない TLD があります。

```text
.alt .arpa .example .internal .invalid .local .localhost .onion
```

`.test` は開発専用 config でだけ許可します。前章の `Handle.parse` はこれらを syntax error にしません。Lexicon validation と network policy を混ぜないためです。

## DID を解決する

### did:plc

```text
did:plc:example123
  -> https://plc.directory/did:plc:example123
```

PLC directory が返す JSON を DID document として parse します。directory URL は config で差し替えられるので test は外部 service を使いません。

### did:web

hostname-level DID を well-known URL に変換します。

```text
did:web:example.com
  -> https://example.com/.well-known/did.json
```

atproto では path-based `did:web` を対象外にします。`localhost%3A2583` のような port 付き localhost は explicit な development mode だけです。

```text
did:web:localhost%3A2583
  -> http://localhost:2583/.well-known/did.json
```

percent encoding が壊れている場合は exception を外へ漏らさず `ResolutionDisallowed` にします。

### unsupported method

generic DID parser が `did:example:123` を受理しても resolver は `UnsupportedDidMethod("example")` を返します。

```text
invalid syntax
valid syntax but unsupported method
supported method but resolution failed
```

この三状態を潰さないことが protocol evolution に必要です。

## DID document の検証

### id

取得対象の DID と top-level `id` が完全一致しなければ拒否します。正しい URL から JSON が返ったことだけでは identity の証明になりません。

### claimed handle

`alsoKnownAs` のうち、余分な path/query/fragment を持たない最初の `at://<handle>` を claim として読みます。

```json
{
  "alsoKnownAs": ["at://alice.example.com"]
}
```

claim はまだ verified handle ではありません。handle を逆向きに解決して同じ DID になることを確認します。

### repository signing key

現在形式では `verificationMethod` から次を満たす最初の entry を使います。

- `id` が `#atproto` または `<DID>#atproto`
- `controller` が account DID と一致
- `type` が `Multikey`
- `publicKeyMultibase` が multibase `z` prefix

この章では key string の抽出までです。base58btc、multicodec、P-256/K-256 の decode と signature verification は repository 章で行います。

### PDS service

`service` から次を満たす entry を使います。

- `id` が `#atproto_pds` または `<DID>#atproto_pds`
- `type` が `AtprotoPersonalDataServer`
- endpoint が origin-level HTTPS URL
- userinfo、path prefix、query、fragment を持たない

local mode だけ localhost HTTP を許可します。URL が文法的に正しいことは、その PDS が現在応答し account を host していることまでは保証しません。

## 双方向 verification

attacker が自分の domain に次を置けるとします。

```dns
_atproto.attacker.example TXT "did=did:plc:victim"
```

handle -> DID だけなら `attacker.example` を victim account の別名にできます。しかし victim の DID document が `at://attacker.example` を claim していなければ、双方向 check で拒否できます。

```scala
for
  did <- resolveHandle(handle)
  document <- resolveDid(did)
  claimed = document.claimedHandle
  _ <- Either.cond(
    claimed.exists(_.normalized == handle.normalized),
    (),
    IdentityError.HandleMismatch(handle, did, claimed)
  )
yield ...
```

DID を直接入力した場合、DID document 自体は handle が壊れていても利用できます。表示用 handle だけを reverse resolution で確認し、失敗時は `None` にします。DID が primary identity だからです。

## network boundary

```scala
trait IdentityNetwork:
  def dnsTxt(name: String): Either[IdentityError, Vector[String]]
  def get(uri: URI, maxBytes: Int): Either[IdentityError, IdentityHttpResponse]
```

resolver の algorithm は interface だけを知ります。test では URI ごとの固定 response を返し、次を検証します。

- DNS success と HTTPS fallback
- conflicting TXT records
- bidirectional mismatch
- expected DID mismatch
- PDS と signing key の抽出
- localhost development policy
- invalid percent encoding

実行:

```console
$ nix develop --command sbt verify
```

## cache と再検証

identity resolution は時点付きの結果です。handle、DID document、PDS location、key は変更され得ます。

production service では次を別々に cache し、TTL と negative cache を持ちます。

- handle -> DID
- DID -> DID document
- verified handle pair

repository signature を検証するとき、どの時点の DID document/key が必要かも考慮します。migration や key rotation の最中に一回の失敗だけで account data を削除してはいけません。

## SSRF と redirect

identity resolution は user-controlled hostname へ HTTP request を行う機能です。internet-facing server に組み込む場合は、次を追加してください。

- private/link-local/cloud metadata address の block
- DNS rebinding 対策と接続先 IP の検査
- redirect hop ごとの scheme/host/IP 再検査
- connect/read timeout、response limit、concurrency limit
- trusted recursive resolver と observability

この参照実装の `JdkIdentityNetwork` は timeout、redirect 上限の JDK default、response limit を持ちますが、完全な SSRF sandbox ではありません。CLI 学習用途と production resolver service の境界です。

## 演習

1. fake DNS に同じ DID の duplicate TXT を追加し、ambiguous にならない理由を確認する。
2. DID document の `controller` を別 DID に変え、signing key が見つからないことを確認する。
3. PDS endpoint に `/prefix` を追加し、なぜ XRPC path と合成してはいけないか説明する。
4. DID を直接解決する case で handle reverse resolution を失敗させ、DID identity 自体は成功する test を追加する。
5. cache entry に `resolvedAt` と expiry を追加するとき、双方向 pair の expiry をどう決めるか設計する。

## 仕様

- [Handle](https://atproto.com/specs/handle)
- [DID](https://atproto.com/specs/did)

