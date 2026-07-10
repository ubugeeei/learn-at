# 11: Transport a block graph with CAR v1

## Goal

Put root CIDs and blocks into one binary archive and verify every content hash
while reading. Understand why a repository export is not a JSON dump.

Implementation: `src/main/scala/learnat/ipld/Car.scala`

## CAR's role

A Content Addressable aRchive transports roots and blocks. AT Protocol uses CAR
for:

- full repository export from `com.atproto.sync.getRepo`;
- account backup and migration;
- block slices in repository events;
- repository import.

CAR does not expand the graph. Its header declares roots and every section pairs
a CID with bytes. The consumer follows links.

## Wire format

```text
varint(header byte length)
DAG-CBOR header

repeat until EOF:
  varint(CID bytes + block bytes length)
  CID bytes
  block bytes
```

The header is this DAG-CBOR value:

```json
{"roots":["<CID link>"],"version":1}
```

That JSON is explanatory. The actual root is a DAG-CBOR CID link, not text.

## Write

```scala
val file = CarFile(
  roots = Vector(commitCid),
  blocks = Vector(CarBlock(commitCid, commitBytes), ...)
)

val bytes: Either[CarError, Array[Byte]] = Car.write(file)
```

Writers may choose block order. Repository exports often put the root commit
first, but consumers use CIDs rather than trusting sequence position.

## Read and verify

The reader checks in order:

1. file-size limit;
2. canonical varint and header length;
3. canonical DAG-CBOR header, version 1, and root links;
4. every section length;
5. CID structure;
6. `SHA-256(block bytes) == CID digest`;
7. absence of duplicate CIDs;
8. block-count limit.

Content is verified before entering a block store. Persisting first would leave
untrusted partial state behind after a later failure.

## Roots and reachability

A generic CAR can name a root whose block is absent. This reader validates the
container. The repository verifier adds semantic rules:

- exactly one root;
- root block present and decodable as a signed commit;
- every MST and record block reachable from the commit exists;
- unreachable extra blocks follow an explicit policy.

Keep container validation separate from repository semantics.

## Resource limits

```scala
Car.Limits(
  maxFileBytes = 64 * 1024 * 1024,
  maxHeaderBytes = 1024 * 1024,
  maxBlockBytes = 8 * 1024 * 1024,
  maxBlocks = 1_000_000
)
```

The learning implementation reads an in-memory `Array[Byte]`. A large
repository needs an incremental reader/writer with per-block verification,
backpressure, and transactional storage.

## Exercises

```console
$ nix develop --command sbt verify
```

1. Flip the final byte of a valid CAR and observe the CID mismatch.
2. Write the same block twice and observe duplicate rejection.
3. Remove the root block; confirm the generic reader succeeds, then design the
   repository-verifier test.
4. Make a section length larger than available bytes and inspect truncation.
5. For a streaming reader, choose the exact block-store commit boundary.

## Specifications

- [CAR v1](https://ipld.io/specs/transport/car/carv1/)
- [AT Protocol sync](https://atproto.com/specs/sync)
