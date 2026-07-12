# 05: Model AT Protocol identifiers as types

## Goal

Distinguish handles, DIDs, NSIDs, record keys, AT URIs, and TIDs, then convert
untrusted strings into validated types. Explain what each identifies, whether it
can change, and why validation is not resolution.

Implementation: `src/learnat/syntax/Identifiers.scala`

## Syntax validation is not resolution

`alice.example.com` satisfying handle grammar does not prove that it exists in
DNS or resolves to an account DID.

```text
syntax validation: inspect characters; pure and offline
resolution:        query DNS/HTTPS; effectful, fallible, and time-dependent
```

This chapter implements syntax only. Chapter 07 adds network resolution.

## Validated strings

With only `String`, the compiler cannot detect this mistake:

```scala
def loadRecord(did: String, collection: String, rkey: String) = ???

loadRecord("alice.example.com", "3jzfcijpj2z2a", "app.bsky.feed.post")
```

The implementation returns distinct types after parsing:

```scala
Did.parse(input)       // Either[SyntaxError, Did]
Nsid.parse(input)      // Either[SyntaxError, Nsid]
RecordKey.parse(input) // Either[SyntaxError, RecordKey]
```

Constructors are private, so a caller cannot bypass validation.

## DID

A DID is the durable account identity:

```text
did:<method>:<method-specific-identifier>
did:plc:ewvi7nxzyoun6zhxrhs64oiz
did:web:example.com
```

Generic Lexicon `did` validation accepts unknown methods. Current atproto
network interoperability supports `did:plc` and `did:web`:

```scala
val did = Did.parse("did:example:123") // syntactically valid
did.map(_.isSupportedByAtproto)        // Right(false)
```

Separating invalid syntax from an unsupported method allows future methods to
appear in stored data without being mislabeled as malformed.

The main constraints are a lower-case method, a non-empty restricted-ASCII
method-specific part, a 2048-character maximum, and no trailing `:` or `%`.

## Handle

A handle is a changeable, human-facing domain name:

```text
alice.bsky.social
Alice.Example.COM
xn--bcher-kva.example
```

Normalize to ASCII lower case for comparison and resolution. Internationalized
domains use punycode, not raw Unicode. The parser enforces total/label length,
at least two labels, ASCII letters/digits/hyphens, no edge hyphens, and a final
label beginning with a letter.

It intentionally does not apply resolution policy for suffixes such as `.local`
or `.onion`. Protocol syntax, account-registration policy, and DNS availability
belong to different layers.

## AtIdentifier

Many XRPC inputs accept either DID or handle:

```scala
enum AtIdentifier:
  case DidIdentifier(value: Did)
  case HandleIdentifier(value: Handle)
```

The forms are unambiguous: DIDs start with `did:`, while handles cannot contain
a colon.

## NSID

A Namespaced Identifier names a Lexicon record type or XRPC method:

```text
com.atproto.repo.getRecord
app.bsky.feed.post
com.example.bookmark
```

All but the last segment are reverse-domain authority; the final segment is the
name:

```scala
val value = Nsid.parse("com.Example.getThing").toOption.get
value.authority  // Example.com
value.name       // getThing
value.normalized // com.example.getThing
```

Authority normalization is case-insensitive; the final name remains
case-sensitive.

## Record key

A record key (`rkey`) names a record inside one collection. The unique tuple is:

```text
(DID, collection NSID, record key)
```

General syntax is a 1–512 character ASCII subset. `.` and `..` are forbidden.
A collection Lexicon adds a naming strategy:

- `tid`: a timestamp identifier;
- `nsid`: an NSID;
- `literal:self`: one fixed singleton key;
- `any`: any general record key.

Passing the general parser does not satisfy a collection-specific strategy.

## AT URI

An AT URI identifies a repository location:

```text
at://<DID-or-handle>[/<collection>[/<rkey>]][#<JSON-pointer>]
```

Examples:

```text
at://did:plc:asdf123/app.bsky.feed.post/3jzfcijpj2z2a
at://alice.example.com/app.bsky.actor.profile/self
at://did:plc:asdf123/app.bsky.feed.post/3jzfcijpj2z2a#/text
```

The strict parser delegates every part to its typed parser. It rejects query
components, empty/trailing segments, and paths deeper than collection/rkey.

An AT URI is not an HTTPS URL. Resolve its authority to a current PDS before
constructing an XRPC request.

## TID

A Timestamp Identifier encodes a 64-bit integer in 13 sortable base32
characters:

```text
top 1 bit:     always zero
next 53 bits: microseconds since Unix epoch
last 10 bits: random clock identifier
```

Its ASCII-sortable alphabet is:

```text
234567abcdefghijklmnopqrstuvwxyz
```

TIDs name records and repository revisions. The generator advances a logical
microsecond when the clock stalls or moves backward, and can also advance past
the previous repository revision.

A TID can be user-controlled. Do not treat its timestamp as trusted creation
time, use it as a globally unique database key, or base security decisions on
it.

## Official interoperability fixtures

Valid and invalid fixtures for all six syntax families are pinned from the
official `interop-test-files/syntax` directory:

```console
$ nix develop --command sbt verify
```

The source commit and license are recorded in
`src/test/resources/interop/README.md`.

## Exercises

1. Permit a digit-leading final handle label and observe the IP-address fixture
   failure.
2. Reject `did:example:123` in the DID parser and explain why that wrongly mixes
   syntax with method support.
3. Parse AT URI paths with a naive `split('/')` and inspect whether your
   language drops a trailing empty segment.
4. Remove `lastTimestamp + 1` from the TID generator and describe how duplicate
   values arise.

Restore every change and make all fixtures pass.

## Specifications

- [DID](https://atproto.com/specs/did)
- [Handle](https://atproto.com/specs/handle)
- [NSID](https://atproto.com/specs/nsid)
- [AT URI scheme](https://atproto.com/specs/at-uri-scheme)
- [Record key](https://atproto.com/specs/record-key)
- [TID](https://atproto.com/specs/tid)
