# 10: multihash と CID

## この章のゴール

byte 列から CIDv1 を作り、文字列表現を parse し、CID と受信 content の対応を検証します。CID が database ID ではなく self-describing content address であることを理解します。

実装は `src/main/scala/learnat/ipld/Ipld.scala` の `Cid`、`Varint`、`Base32` です。

## CIDv1 の bytes

この教材の DAG-CBOR block CID は次を連結します。

```text
varint(1)       CID version 1
varint(0x71)    dag-cbor multicodec
varint(0x12)    sha2-256 multihash code
varint(32)      digest length
32 bytes        SHA-256 digest
```

raw blob では codec が `0x55` です。hash algorithm だけでなく、content をどう解釈するかも CID に含まれます。

## varint

小さい整数を 7 bit ごとの little-endian group にし、続きがある byte の high bit を 1 にします。

decoder は次を拒否します。

- 終端 byte のない input
- 64 bit overflow
- 不要な zero group を持つ non-minimal encoding

同じ整数に複数の byte 表現を許さないためです。

## multibase

CID text は binary CID を base32 lower-case にし、multibase prefix `b` を付けます。

empty DAG-CBOR map `a0` の例:

```text
SHA-256: c19a797fa1fd590cd2e5b42d1cf5f246e29b91684e2f87404b81dc345c7a56a0
CID:     bafyreigbtj4x7ip5legnfznufuopl4sg4knzc2cof6duas4b3q2fy6swua
```

base32 padding `=` は付けません。decoder は upper-case、未知 character、non-zero padding bit を拒否します。

## 作成と検証

```scala
val bytes = DagCbor.encode(value).toOption.get
val cid = Cid.forDagCbor(bytes)

cid.verifies(bytes)        // true
Cid.parse(cid.toString)    // Right(cid)
```

`verifies` は受信 content を再 hash し、multihash digest と constant-time comparison します。CID text を parse できたことだけでは、続く block bytes が正しい証拠になりません。

## CID の性質

- content の 1 bit が変わると別 CID になる
- 同じ canonical bytes はどこで保存しても同じ CID
- CID から元 content を復元することはできない
- CID は content の author や trustworthiness を保証しない
- record が repository に含まれることや commit signature は別に検証する

malicious content にも正しい CID は作れます。CID が保証するのは「この名前と bytes が一致する」ことです。

## 演習

1. 同じ JSON logical value を field 順違いで DAG-CBOR encode し、CID が同じことを確認する。
2. DAG-CBOR block の最後の bit を反転し、`verifies` を失敗させる。
3. codec だけ raw に変え、同じ digest でも CID が変わることを確認する。
4. non-minimal varint を parser が受理すると、同じ metadata に複数 CID bytes ができる問題を説明する。

## 仕様

- [AT Protocol data model: CID links](https://atproto.com/specs/data-model)
- [CID specification](https://github.com/multiformats/cid)
- [Multihash](https://multiformats.io/multihash/)

