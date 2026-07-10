# 12: Build the repository Merkle Search Tree

## Goal

Build the same root CID as the official implementation for the same set of repository paths and record CIDs. Then decode an untrusted tree and prove that its paths, layers, prefix compression, child links, and block hashes are valid.

The implementation is in `src/main/scala/learnat/repo/Mst.scala`.

## Repository keys

An atproto repository is a map from one normalized path to one record CID.

```text
<collection NSID>/<record key>

com.example.note/3jqfcqzm3fo2j
app.bsky.actor.profile/self
```

The slash is structural. A collection and record key are validated independently before they become an MST key. The current path limit is 1024 characters.

## Why a Merkle Search Tree

The structure must have three properties at once:

1. sorted lookup and range scans;
2. content-addressed subtrees, so a change alters every CID on its path to the root;
3. insertion-order independence, so the same final map has the same root.

A conventional balanced tree normally depends on mutation order. The MST derives each key's layer from its SHA-256 hash, making the shape deterministic.

## Derive the layer

Hash the UTF-8 repository path with SHA-256 and count pairs of leading zero bits.

```text
leading bits  first non-zero base-4 digit  layer
1...          immediately                     0
00 1...       after one pair                   1
00 00 1...    after two pairs                  2
```

Two bits per layer produce an expected fanout of four. `Mst.leadingZeros` implements the reference algorithm byte by byte.

## Build from the complete map

This project starts with a correctness-first builder:

```scala
val snapshot: Either[MstError, MstSnapshot] = Mst.build(entries)
```

It sorts all paths, rejects duplicates, computes every layer, and recursively partitions the key range around keys at the current layer. A range with no key on the expected child layer receives a structural one-child node. This is the same shape produced by incremental mutations.

Rebuilding is not the most efficient approach for millions of records, but it provides a compact correctness oracle for a later incremental implementation.

## Node encoding

An MST node is DAG-CBOR with two fields:

```text
l: left-most child CID or null
e: ordered leaf entries
```

Each entry contains:

```text
p: byte/ASCII prefix length shared with the previous key
k: remaining ASCII key bytes
v: record CID
t: child CID to the right, or null
```

For example:

```text
previous: com.example.note/3jqfcqzm3fo2j
current:  com.example.note/3jqfcqzm3fp2j
prefix:   com.example.note/3jqfcqzm3f
suffix:                                  p2j
```

There can never be two neighboring child pointers. `l` and `t` encode an in-order sequence of child, leaf, child, leaf.

## Verification

`MstVerifier.verify` does not trust a root CID merely because it parses. It:

- requires every reachable node block;
- recomputes every block hash;
- rejects cycles and shared child nodes;
- accepts only exact `l`/`e` and `p`/`k`/`v`/`t` shapes;
- reconstructs compressed keys and validates repository path syntax;
- recomputes each key's SHA-256 layer;
- requires every child to be exactly one layer below its parent;
- requires the final in-order leaves to be strictly sorted;
- enforces node and leaf limits.

The verifier returns reconstructed leaves rather than trusting an index supplied by the PDS.

## Official interoperability roots

The tests pin the official `@atproto/repo` known-map vectors:

```text
empty tree:
bafyreie5737gdxlw5i64vzichcalba3z2v5n6icifvx5xytvske7mr3hpm

single level-0 leaf:
bafyreibj4lsc3aqnrvphp5xmrnfoorvru4wynt6lwidqbm2623a6tatzdu

single level-2 leaf:
bafyreih7wfei65pxzhauoibu3ls7jgmkju4bspy4t2ha2qdjnzqvoy33ai
```

A multi-node five-record vector is also checked. This catches encoding differences that insertion-order-only tests cannot.

## Run and break it

```console
$ nix develop --command sbt verify
```

Exercises:

1. Change the layer function to consume one bit per layer and inspect every known-root failure.
2. Encode full keys instead of prefix-compressed keys and compare the root CID.
3. Remove one non-root node from `snapshot.blocks` and follow the verifier error.
4. Pass the same entries in ten shuffled orders and assert one root.
5. Design an incremental `put` operation and use `Mst.build` as the oracle after every random mutation.

## Specification

- [Repository](https://atproto.com/specs/repository)

