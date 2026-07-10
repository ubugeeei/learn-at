# 10: Multihash and CID

## Goal

Create CIDv1 values from bytes, parse their text form, and verify that received
content matches a CID. Understand a CID as a self-describing content address,
not a database identifier.

Implementation: `Cid`, `Varint`, and `Base32` in
`src/main/scala/learnat/ipld/Ipld.scala`

## CIDv1 bytes

A DAG-CBOR block CID in this project concatenates:

```text
varint(1)       CID version 1
varint(0x71)    dag-cbor multicodec
varint(0x12)    sha2-256 multihash code
varint(32)      digest length
32 bytes        SHA-256 digest
```

A raw blob uses codec `0x55`. The CID identifies both the digest algorithm and
how content bytes should be interpreted.

## Varint

A varint stores seven little-endian value bits per byte; the high bit indicates
another byte. The decoder rejects truncation, 64-bit overflow, and non-minimal
zero groups so one integer cannot have several wire representations.

## Multibase

CID text is lower-case base32 with multibase prefix `b`. For the empty
DAG-CBOR map `a0`:

```text
SHA-256: c19a797fa1fd590cd2e5b42d1cf5f246e29b91684e2f87404b81dc345c7a56a0
CID:     bafyreigbtj4x7ip5legnfznufuopl4sg4knzc2cof6duas4b3q2fy6swua
```

There is no `=` padding. The decoder rejects upper case, unknown characters,
and non-zero trailing padding bits.

## Create and verify

```scala
val bytes = DagCbor.encode(value).toOption.get
val cid = Cid.forDagCbor(bytes)

cid.verifies(bytes)        // true
Cid.parse(cid.toString)    // Right(cid)
```

`verifies` hashes the received content and constant-time compares its digest.
Parsing a CID string alone does not prove that the following block bytes match.

## Properties

- changing one content bit changes the CID;
- the same canonical bytes have the same CID at every storage location;
- a CID cannot reconstruct its original content;
- a CID does not identify the author or assert trustworthiness;
- MST inclusion and the commit signature are separate checks.

Malicious content can have a perfectly valid CID. The guarantee is only: “this
name matches these exact bytes.”

## Exercises

1. DAG-CBOR encode the same logical value with different source field order and
   confirm equal CIDs.
2. Flip the final block bit and make `verifies` fail.
3. Change only the codec to raw and observe a different CID with the same
   digest.
4. Explain how accepting non-minimal varints permits multiple CIDs for the same
   metadata.

## Specifications

- [AT Protocol data model](https://atproto.com/specs/data-model)
- [CID](https://github.com/multiformats/cid)
- [Multihash](https://multiformats.io/multihash/)
