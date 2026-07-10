# 11: CAR v1 で block graph を運ぶ

## この章のゴール

CID と block の集合を一つの binary file にし、読み込み時に全 block の content hash を検証します。repository export が単なる JSON dump ではない理由を理解します。

実装は `src/main/scala/learnat/ipld/Car.scala` です。

## CAR の役割

Content Addressable aRchive (CAR) は root CID と block を運ぶ container です。AT Protocol では主に次に使います。

- `com.atproto.sync.getRepo` の full repository export
- account backup / migration
- firehose event 内の block slice
- repository import

CAR 自体は tree 構造を展開しません。header に root を置き、各 block は CID と bytes の pair です。link を辿るのは consumer です。

## wire format

```text
varint(header byte length)
DAG-CBOR header

repeat until EOF:
  varint(CID bytes + block bytes length)
  CID bytes
  block bytes
```

header は次の DAG-CBOR value です。

```json
{
  "roots": ["<CID link>"],
  "version": 1
}
```

JSON は説明用です。実際の root は text string ではなく DAG-CBOR CID link です。

## write

```scala
val file = CarFile(
  roots = Vector(commitCid),
  blocks = Vector(CarBlock(commitCid, commitBytes), ...)
)

val bytes: Either[CarError, Array[Byte]] = Car.write(file)
```

block 順は用途により選べます。repository export では root commit から辿りやすい順に置けますが、consumer は順序だけを信頼せず CID で参照します。

## read と verification

reader は次を順に確認します。

1. file size limit
2. canonical varint と header length
3. DAG-CBOR header、version 1、CID root array
4. 各 section length
5. CID structure
6. `SHA-256(block bytes) == CID digest`
7. duplicate CID がないこと
8. block count limit

content mismatch を検出してから block store に入れます。先に保存して後で検証すると、失敗途中の untrusted block が cache に残ります。

## root と reachable block

generic CAR は root block 自体が file に含まれない場合も表現できます。この reader は container として parse します。repository verifier は次の上位 rule を追加します。

- root が一つ
- root block が存在
- root が signed commit
- commit から MST/record へ辿る全 block が存在
- 未到達 block の扱いを policy で決める

container validation と repository semantic validation を分離します。

## resource limits

```scala
Car.Limits(
  maxFileBytes = 64 * 1024 * 1024,
  maxHeaderBytes = 1024 * 1024,
  maxBlockBytes = 8 * 1024 * 1024,
  maxBlocks = 1_000_000
)
```

現在の実装は in-memory `Array[Byte]` を対象にした教材版です。大規模 repository では incremental stream reader/writer にし、block ごとに検証・backpressure・transaction を適用します。

## 実行と演習

```console
$ nix develop --command sbt verify
```

1. valid CAR の最後の byte を反転し、CID mismatch を確認する。
2. 同じ block を二回書き、duplicate rejection を確認する。
3. root block を blocks から外しても generic reader は成功することを確認し、repository verifier の test を設計する。
4. section length を実際より大きくし、truncation error の offset を読む。
5. streaming reader へ変える場合、どの時点で block store transaction を commit するか設計する。

## 仕様

- [CAR v1](https://ipld.io/specs/transport/car/carv1/)
- [AT Protocol sync](https://atproto.com/specs/sync)

