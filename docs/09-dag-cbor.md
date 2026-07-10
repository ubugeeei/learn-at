# 09: AT Protocol data model と deterministic DAG-CBOR

## この章のゴール

repository record を一意な byte 列へ変換します。なぜ普通の JSON text を hash せず、制約された data model と canonical encoding が必要なのかを説明できるようにします。

実装は `src/main/scala/learnat/ipld/Ipld.scala` の `Ipld` と `DagCbor` です。

## content address の前提

次の二つは同じ JSON value に見えます。

```json
{"a":1,"b":2}
{"b":2, "a":1}
```

しかし UTF-8 bytes は違うため、そのまま SHA-256 を計算すると別 hash になります。content-addressed repository では、同じ logical value が必ず同じ bytes になる rule が必要です。

DAG-CBOR は IPLD data model を canonical CBOR で表します。AT Protocol はさらに data model を限定します。

## Scala data model

```scala
enum Ipld:
  case Null
  case Bool(value: Boolean)
  case Integer(value: Long)
  case Text(value: String)
  case Bytes(value: ByteString)
  case List(value: Vector[Ipld])
  case Map(fields: Vector[(String, Ipld)])
  case Link(value: Cid)
```

JSON にない `Bytes` と `Link` があります。一方、floating-point number は AT Protocol data model では使いません。binary floating point の canonicalization や NaN 表現の曖昧さを repository へ持ち込まないためです。

`ByteString` は input array を copy します。mutable な `Array[Byte]` をそのまま保存すると、CID 計算後に caller が内容を変更できます。

## CBOR の major type

CBOR item の先頭 byte は上位 3 bit が major type、下位 5 bit が短い値または追加 length の種類です。

| major | この実装での意味 |
| --- | --- |
| 0 | non-negative integer |
| 1 | negative integer `-1 - n` |
| 2 | bytes |
| 3 | UTF-8 text |
| 4 | list |
| 5 | string-keyed map |
| 6 | CID tag 42 だけ |
| 7 | false / true / null だけ |

たとえば `1` は `01`、`-1` は `20`、`true` は `f5` です。

## canonical rule

### 最短の integer / length

値 1 は一 byte `01` で表せます。`18 01` のように追加 byte を使う表現も generic CBOR decoder では同じ値ですが、canonical では拒否します。

### definite length

終端 marker まで読む indefinite-length list/string は拒否し、最初に length を書きます。異なる chunk 分割で bytes が変わるのを防ぎます。

### map key

key は UTF-8 string だけです。UTF-8 byte length の短い順、同じ length なら unsigned byte order で並べます。

```text
"a"  -> length 1
"b"  -> length 1, a の後
"aa" -> length 2
```

```scala
Ipld.obj(
  "aa" -> Integer(2),
  "b"  -> Integer(1),
  "a"  -> Integer(0)
)
```

canonical hex:

```text
a3 61 61 00 61 62 01 62 61 61 02
```

duplicate key と順序違反は decoder でも拒否します。

### UTF-8

JDK decoder を malformed/unmappable input の `REPORT` mode にします。replacement character へ黙って置換すると、受信 bytes と再 encode bytes が変わります。

### CID link

IPLD link は CBOR tag 42、その中に先頭 `0x00` と CID bytes を持つ byte string です。CID text を普通の string として encode しません。

## encoder と decoder の責務

encoder はどんな field 順で入力されても canonical 順にします。decoder は generic CBOR を寛容に読むのではなく、canonical 違反を拒否します。

```scala
val bytes: Either[IpldError, Array[Byte]] = DagCbor.encode(value)
val value: Either[IpldError, Ipld] = DagCbor.decode(bytes)
```

decoder は trailing bytes、depth、input size も確認します。network body limit の代わりではなく、codec 自身の resource boundary です。

## 実行と演習

```console
$ nix develop --command sbt verify
```

1. `{ "b": ..., "a": ... }` を encode して key 順が変わることを見る。
2. `01` を `18 01` に変えて decoder error を確認する。
3. invalid UTF-8 の text item を作り、replacement されないことを test する。
4. `ByteString` の defensive copy を外し、hash 前後に source array を変える危険を再現する。

## 仕様

- [AT Protocol data model](https://atproto.com/specs/data-model)
- [IPLD DAG-CBOR](https://ipld.io/specs/codecs/dag-cbor/spec/)

