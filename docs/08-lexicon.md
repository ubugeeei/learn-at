# 08: Parse Lexicons and validate data

## Goal

Turn an atproto schema document into explicit Scala data types, then validate a
repository value without confusing schema validation with application policy.

Implementation:

- `src/learnat/lexicon/Lexicon.scala`
- `src/learnat/lexicon/LexiconValidator.scala`
- `src/learnat/lexicon/Lexicon.test.scala`

## What a Lexicon is

Lexicon v1 is the schema language for three related surfaces:

- data stored as repository records;
- JSON or binary input/output and URL parameters of XRPC endpoints;
- messages carried by event streams.

It resembles JSON Schema and OpenAPI, but its types follow the atproto data
model: signed 64-bit integers, no floating-point values, native bytes and CID
links in DAG-CBOR, typed blobs, NSID references, and discriminated unions.

A file belongs to one NSID and contains one or more named definitions:

```json
{
  "lexicon": 1,
  "id": "com.example.note",
  "defs": {
    "main": {
      "type": "record",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["text", "createdAt"],
        "properties": {
          "text": {"type": "string", "maxLength": 3000},
          "createdAt": {"type": "string", "format": "datetime"}
        }
      }
    }
  }
}
```

`main` is the default referenced definition. A non-main definition uses a
fragment, such as `com.example.note#replyRef`. Within the same document it can
be written `#replyRef`. A `$type` value for `main` uses only the NSID; the value
`com.example.note#main` is invalid.

## The type system

Concrete values:

| Lexicon | IPLD / atproto value | Important constraints |
| --- | --- | --- |
| `boolean` | boolean | `const`, `default` |
| `integer` | signed 64-bit integer | min/max, enum, const |
| `string` | Unicode string | UTF-8 byte length, graphemes, format, enum |
| `bytes` | raw bytes | raw byte length |
| `cid-link` | CID link | atproto-supported CID form |
| `blob` | blob object | MIME pattern and declared size |

Containers and indirection:

- `array` validates every item and its item count;
- `object` distinguishes required, optional, and nullable properties;
- `ref` loads one local or global named definition;
- `union` selects a variant using `$type`;
- `unknown` permits an atproto data-model object but not a disguised compound
  value such as a blob.

Primary definitions describe protocol surfaces:

- `record` for repository values;
- `query` for XRPC GET;
- `procedure` for XRPC POST;
- `subscription` for WebSocket event streams;
- `permission-set` for OAuth permission bundles.

`params` is the restricted query-parameter object used by endpoints.
`permission` is restricted to permission sets. `token` is a named symbol with no
encoded representation. `ref`, `union`, `unknown`, `params`, and `permission`
cannot themselves be named top-level definitions.

The learning parser fully models data schemas and records. It recognizes other
primary kinds so a document can be classified, but does not yet compile their
parameters, body envelopes, errors, or permissions into Scala endpoint types.
That separation is intentional: the runtime validator should not pretend to be
a complete code generator.

## Parse before validating values

`LexiconDocument.parse` rejects malformed schemas before they can become
security policy. Among other checks, it enforces:

- language version exactly `1`, a valid NSID, and non-empty `defs`;
- one primary definition at most, named `main`;
- bounded schema depth, definition count, and property count;
- legal local/global references;
- `const` and `default` mutual exclusion;
- ordered length and numeric ranges;
- declared names in `required` and `nullable`;
- unique enum and property-list entries;
- valid string formats and blob MIME patterns;
- record-key strategies `tid`, `nsid`, `any`, or `literal:<record-key>`;
- no empty closed union.

Unknown object fields in a Lexicon document are ignored. This permits compatible
language evolution, but permission parsing requires a different rule: unknown
permission fields can attenuate authority, so an authorization engine must not
silently grant access after ignoring them.

## Validate an IPLD record

The validator uses the IPLD value from chapter 09 rather than plain JSON. That
keeps bytes and links distinct and ensures floats already cannot enter the data
model.

```scala
val document = LexiconDocument.parse(Json.parse(source).toOption.get).toOption.get
val registry = LexiconRegistry.from(Vector(document)).toOption.get
val validator = LexiconValidator(registry)

val record = Ipld.obj(
  "$type" -> Ipld.Text("com.example.note"),
  "text" -> Ipld.Text("hello"),
  "createdAt" -> Ipld.Text("2026-07-10T00:00:00.000Z")
)

val report = validator.validateRecord(document.id, record)
```

Validation is recursive and bounded. Refs resolve through an immutable registry;
unresolved and cyclic refs fail closed. Record refs always require their
discriminator. A union requires `$type`, validates a known variant, rejects an
unknown closed variant, and preserves an unknown open variant with a warning.

## Forward compatibility

Lexicon objects are open to future optional fields. Therefore a valid value with
an unexpected property is accepted with `ValidationWarning`; application code
should retain that field when it rewrites the object. Deserializing into a Scala
case class and serializing only known fields can silently destroy future data.

This gives three different outcomes:

```text
known field, valid value       -> accept
unknown object field           -> accept, preserve, warn
known field, invalid value     -> reject the entire value
```

Open unions follow the same evolution rule. “Unknown” is not synonymous with
“invalid.” Closed unions explicitly opt out of future variants.

## Length is not `String.length`

Lexicon `minLength` and `maxLength` count UTF-8 bytes. Scala/Java `String.length`
counts UTF-16 code units and is wrong for this purpose:

```text
value       UTF-16 units    Unicode code points    UTF-8 bytes
é           1               1                      2
😀          2               1                      4
```

`LexiconValidator` re-encodes to UTF-8. Grapheme constraints use the JDK
character `BreakIterator`, which handles ordinary combining sequences but is not
a pinned implementation of the latest Unicode extended-grapheme algorithm.
Internet-facing interoperability code should use a tested Unicode segmentation
implementation with a declared Unicode version; this dependency-free learning
implementation makes that production gap explicit.

## String formats

The validator supports all Lexicon v1 format names:

```text
at-identifier  at-uri  cid  datetime  did  handle
nsid  tid  record-key  uri  language
```

Identifier formats reuse the strict parsers from chapter 05. `datetime`
requires a four-digit non-negative year, upper-case `T` and `Z`, whole seconds,
a timezone, a semantically valid calendar/time, and rejects `-00:00`. Fractional
seconds may have arbitrary precision and the original string is never
normalized, because normalization would change repository bytes and therefore
the record CID.

## PDS validation modes

The protocol distinguishes three choices for record creation:

1. Explicit validation: reject when the schema is unknown or the value fails.
2. Explicit no validation: skip the Lexicon, but still enforce the atproto data
   model and compound-value rules.
3. Optimistic validation: validate when the schema is available and otherwise
   allow the record. This is the default/fail-open mode.

These are API semantics, not reasons to accept malformed DAG-CBOR or blob
objects. The local PDS currently enforces the data model and `$type` matching but
does not yet expose schema loading or the tri-state `validate` input. Integrating
this registry is an exercise after adding a trusted Lexicon resolution/cache
policy.

## Lexicon evolution and authority

Once third parties use a Lexicon, compatible updates may add optional fields but
must not rename fields, change types, remove required fields, or add new required
fields. A breaking design gets a new NSID.

The NSID authority maps to a domain. Public Lexicon resolution uses a
`_lexicon.<authority>` DNS TXT record to find a DID, resolves that DID to a PDS,
and reads a `com.atproto.lexicon.schema` record whose key is the schema NSID.
Resolvers must set bounded DNS/HTTP/cache behavior and re-check authority; a
schema downloaded from an arbitrary URL is not authoritative merely because its
`id` field looks correct.

## Exercises

1. Change `maxLength` to 5 and compare ASCII, `é`, CJK, and emoji values.
2. Remove `text`, set it to `null`, set it to `""`, and explain all four states.
3. Add an unknown field, validate it, then prove a read-modify-write preserves it.
4. Turn the `subject` union in `LexiconTests` from open to closed.
5. Add two Lexicon documents and resolve a global cross-document ref.
6. Parse XRPC `params`, `input`, and `output` into endpoint types, then generate
   the query encoder and response decoder from chapter 06.
7. Add a schema cache keyed by NSID plus resolved authority, with expiry and
   negative-cache limits.

## Specifications

- [Lexicon](https://atproto.com/specs/lexicon)
- [Data Model](https://atproto.com/specs/data-model)
- [Record Key](https://atproto.com/specs/record-key)
- [Namespaced Identifier](https://atproto.com/specs/nsid)
- [HTTP API (XRPC)](https://atproto.com/specs/xrpc)
