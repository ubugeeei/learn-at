# 09: AT Protocol data model and deterministic DAG-CBOR

## Goal

Turn a repository record into one unique byte sequence. Explain why a
content-addressed repository needs a constrained data model and canonical
encoding instead of hashing ordinary JSON text.

Implementation: `Ipld` and `DagCbor` in
`src/main/scala/learnat/ipld/Ipld.scala`

## Requirement for content addressing

These are the same JSON value:

```json
{"a":1,"b":2}
{"b":2, "a":1}
```

Their UTF-8 bytes differ, so hashing the source text yields different results.
A content-addressed repository needs a rule under which the same logical value
always produces the same bytes.

DAG-CBOR represents the IPLD data model in canonical CBOR. AT Protocol further
constrains that model.

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

`Bytes` and `Link` have no direct ordinary-JSON primitive. Floating point is
excluded, avoiding numeric canonicalization and NaN ambiguity in repository
data.

`ByteString` defensively copies its input. Retaining a mutable `Array[Byte]`
would let a caller alter content after its CID was calculated.

## CBOR major types

The upper three bits of the initial byte identify a CBOR major type. The lower
five contain a small value or select an additional length:

| Major | Accepted meaning |
| --- | --- |
| 0 | non-negative integer |
| 1 | negative integer `-1 - n` |
| 2 | bytes |
| 3 | UTF-8 text |
| 4 | list |
| 5 | string-keyed map |
| 6 | CID tag 42 only |
| 7 | false, true, or null only |

For example, `1` is `01`, `-1` is `20`, and `true` is `f5`.

## Canonical rules

### Shortest integers and lengths

The value 1 fits in `01`. Generic CBOR may also decode `18 01`, but canonical
DAG-CBOR rejects that longer representation.

### Definite length

Strings and containers declare length up front. Indefinite-length chunks are
rejected because different chunking would produce different bytes.

### Map keys

Keys are UTF-8 strings ordered first by UTF-8 byte length and then by unsigned
byte order:

```text
"a"  -> one byte
"b"  -> one byte, after a
"aa" -> two bytes
```

```scala
Ipld.obj(
  "aa" -> Integer(2),
  "b"  -> Integer(1),
  "a"  -> Integer(0)
)
```

Canonical hexadecimal form:

```text
a3 61 61 00 61 62 01 62 61 61 02
```

The decoder rejects duplicate or incorrectly ordered keys.

### UTF-8

The JDK decoder uses `REPORT` for malformed and unmappable input. Silent
replacement would make received bytes differ from re-encoded bytes.

### CID links

An IPLD link is CBOR tag 42 containing a byte string whose first byte is `0x00`
followed by binary CID bytes. It is not encoded as CID text.

## Encoder and decoder responsibilities

The encoder canonicalizes any input field order. The decoder is deliberately
strict and rejects non-canonical generic CBOR:

```scala
val bytes: Either[IpldError, Array[Byte]] = DagCbor.encode(value)
val value: Either[IpldError, Ipld] = DagCbor.decode(bytes)
```

It also rejects trailing bytes and enforces depth/input limits. These are codec
resource boundaries in addition to, not instead of, HTTP body limits.

`decodeSequence` is the one explicit exception to “one value only”; chapter 16
uses it for event-stream header/body framing.

## Exercises

```console
$ nix develop --command sbt verify
```

1. Encode `{ "b": ..., "a": ... }` and observe canonical reordering.
2. Replace `01` with `18 01` and inspect the decoder error.
3. Construct invalid UTF-8 text and prove it is not replaced.
4. Remove the `ByteString` copy and mutate the source array after hashing.

## Specifications

- [AT Protocol data model](https://atproto.com/specs/data-model)
- [IPLD DAG-CBOR](https://ipld.io/specs/codecs/dag-cbor/spec/)
