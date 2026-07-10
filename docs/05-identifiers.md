# 05: AT Protocol の識別子を型にする

## この章のゴール

handle、DID、NSID、record key、AT URI、TID を見分け、文字列から検証済みの型へ変換します。それぞれの識別対象、変更可能性、validation と resolution の違いを説明できるようにします。

実装は `src/main/scala/learnat/syntax/Identifiers.scala` にあります。

## syntax validation と resolution は別

`alice.example.com` が handle の文法を満たすことと、DNS 上で存在して account DID を指すことは別です。

```text
syntax validation: 文字の並びだけを見る。pure、offline
resolution:         DNS / HTTPS を使って現在の値を得る。effectful、失敗・変化する
```

この章では syntax だけを扱います。次章以降で network resolution を追加します。

## 検証済み文字列

すべてを `String` で渡すと、次の誤りを compiler は見つけられません。

```scala
def loadRecord(did: String, collection: String, rkey: String) = ???

loadRecord("alice.example.com", "3jzfcijpj2z2a", "app.bsky.feed.post")
```

引数の順序と意味が壊れています。実装では parse 後に別の型を返します。

```scala
Did.parse(input)       // Either[SyntaxError, Did]
Nsid.parse(input)      // Either[SyntaxError, Nsid]
RecordKey.parse(input) // Either[SyntaxError, RecordKey]
```

constructor は private なので、validation を通らずに作れません。共通の `ValidatedString` は文字列表示と値による等価比較だけを提供します。

## DID

DID は永続的な account identity です。

```text
did:<method>:<method-specific-identifier>
did:plc:ewvi7nxzyoun6zhxrhs64oiz
did:web:example.com
```

atproto の generic `did` format validation は未知の DID method も受け入れます。一方、network で相互運用対象の method は現在 `did:plc` と `did:web` です。

```scala
val did = Did.parse("did:example:123") // syntax としては成功
did.map(_.isSupportedByAtproto)        // Right(false)
```

「文法が不正」と「正しい文法だが未対応」を分けることで、将来 method が追加されたときに data 自体を壊れたものとして扱わずに済みます。

主な syntax constraint は次です。

- lower-case の `did:` prefix と method
- method-specific part を空にしない
- ASCII の限定された文字
- atproto では最大 2048 文字
- `:`, `%` で終わらない

## handle

handle は人間向けの変更可能な名前です。domain name 形式を使います。

```text
alice.bsky.social
Alice.Example.COM
xn--bcher-kva.example
```

比較・解決時には ASCII lower-case へ normalize します。Unicode を直接入れず、internationalized domain は punycode 表現を使います。

constraint は次です。

- 全体で最大 253 文字
- `.` で区切られた二つ以上の label
- label は 1–63 文字
- ASCII letter、digit、hyphen のみ
- label の先頭・末尾に hyphen を置かない
- 最後の label は ASCII letter で始める

この low-level parser は `.local` や `.onion` のような policy 上の制限を行いません。protocol syntax、account registration policy、実際の DNS 解決は別の層です。

## AtIdentifier

XRPC input には DID または handle のどちらかを取る `at-identifier` がよく現れます。

```scala
enum AtIdentifier:
  case DidIdentifier(value: Did)
  case HandleIdentifier(value: Handle)
```

DID は必ず `did:` で始まり、handle に colon は使えないため曖昧になりません。

## NSID

Namespaced Identifier は Lexicon record type や XRPC method を識別します。

```text
com.atproto.repo.getRecord
app.bsky.feed.post
com.example.bookmark
```

最後以外は reverse domain authority、最後が name です。

```scala
val value = Nsid.parse("com.Example.getThing").toOption.get
value.authority  // Example.com
value.name       // getThing
value.normalized // com.example.getThing
```

authority の lowercase normalization と、case-sensitive な final name を混ぜないことが重要です。

## record key

record key (`rkey`) は collection 内の record 名です。repository 全体で一意ではありません。

一意になる tuple は次です。

```text
(DID, collection NSID, record key)
```

一般 syntax は 1–512 文字の ASCII subset です。`.` と `..` は path traversal と曖昧になるため禁止です。

Lexicon の collection schema はさらに命名方式を指定できます。

- `tid`: 時刻順の TID
- `nsid`: NSID
- `literal:self`: singleton record
- `any`: general record key

general parser が成功しても、collection 固有の key rule は別途検証します。

## AT URI

AT URI は repository 内の対象を指します。

```text
at://<DID-or-handle>[/<collection>[/<rkey>]][#<JSON-pointer>]
```

例:

```text
at://did:plc:asdf123/app.bsky.feed.post/3jzfcijpj2z2a
at://alice.example.com/app.bsky.actor.profile/self
at://did:plc:asdf123/app.bsky.feed.post/3jzfcijpj2z2a#/text
```

strict parser は authority、collection、record key をそれぞれ対応する parser に渡します。query、empty segment、trailing slash、三つ以上の path segment は拒否します。

AT URI は HTTPS URL ではありません。handle または DID から現在の PDS を解決した後、XRPC request に変換します。

## TID

Timestamp Identifier は 64-bit integer を sortable base32 で 13 文字にしたものです。

```text
top 1 bit    : 常に 0
next 53 bits : UNIX epoch からの microseconds
last 10 bits : random clock identifier
```

alphabet は通常の base32 と違い、ASCII sort 順になる次の文字です。

```text
234567abcdefghijklmnopqrstuvwxyz
```

TID は record key と repository revision に使われます。generator は時刻が停止・逆行しても、直前より 1 microsecond 大きい logical timestamp を選びます。前の repository revision を渡した場合も、それより必ず大きくします。

TID timestamp は user-controlled data になり得ます。record の本当の作成時刻、database の globally unique key、security decision に使ってはいけません。

## 公式 interoperability fixture

参照実装の `interop-test-files/syntax` から、実装した六形式の valid / invalid fixture を test resource に固定しています。

```console
$ nix develop --command sbt verify
```

test は curated case に加え、fixture の全行を対応する parser に通します。2026-07-10 時点の固定 commit と license は `src/test/resources/interop/README.md` に記録しています。

## 壊して確かめる

1. handle regex の final label を digit 始まりでも許し、IP address fixture が失敗することを確認する。
2. DID parser で `did:example:123` を拒否し、syntax validation と supported-method check が混ざる問題を説明する。
3. AT URI の path をただ `split('/')` し、trailing empty segment が消える言語/library の挙動を調べる。
4. TID generator の `lastTimestamp + 1` を外し、同じ millisecond 内で duplicate が起きる条件を書く。

確認後は変更を元に戻し、fixture 全件を成功させてください。

## 仕様

- [DID](https://atproto.com/specs/did)
- [Handle](https://atproto.com/specs/handle)
- [NSID](https://atproto.com/specs/nsid)
- [AT URI scheme](https://atproto.com/specs/at-uri-scheme)
- [Record key](https://atproto.com/specs/record-key)
- [TID](https://atproto.com/specs/tid)

