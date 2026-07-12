# Architecture decision records

## ADR-001: One codebase, explicit layers

Keep client and server in one Scala project, separated into `syntax`, `json`,
`identity`, `lexicon`, `xrpc`, `ipld`, `repo`, `oauth`, `sync`, `client`, and
`pds` packages. Shared codecs and identifier types remain reusable while code
review can inspect dependency direction:

```text
client ─┐
        ├─> xrpc -> identity -> syntax
pds ────┘          │
  │                 └-> json
  └-> repo -> ipld -> bytes
```

`repo` does not know HTTP. `identity` does not know repository storage. Each
protocol layer can therefore be tested independently.

## ADR-002: Either for untrusted input

Parse failure is normal control flow. Public parsers return
`Either[ProtocolError, A]`, and internal code receives validated types. Reserve
exceptions for programmer errors and genuinely unrecoverable I/O boundaries.

## ADR-003: Confine mutable state

Codecs, identifiers, schemas, and MST nodes are immutable. Clock, randomness,
network, filesystem, cursor, and account-session mutation live behind narrow
interfaces that tests can replace.

## ADR-004: Do not implement cryptographic primitives

Use JCA for SHA-256, ECDSA, PBKDF2, and secure randomness. Implement only the
protocol-specific canonical input, multicodec key representation, DER/compact
signature conversion, and low-S normalization.

## ADR-005: Rebuild the first MST from a record map

Build the initial MST deterministically from a complete key/value set. This
makes insertion-order independence explicit and provides a correctness oracle.
An incremental updater must later return exactly the same CID. Rebuilding is
still correct for a small learning PDS.

## ADR-006: Separate teaching auth from recommended auth

Implement legacy `createSession` first because one POST and bearer token expose
request authentication clearly. Do not recommend it for new user-facing
clients. OAuth discovery, PKCE, PAR, and DPoP define the final client target.

## ADR-007: English is the repository language

Documentation, public help, comments, and test descriptions are English. Tests
may still use non-ASCII data when Unicode behavior itself is under test, but the
example must not depend on one natural language.

## ADR-008: Keep protocol routing independent of the HTTP engine

`LocalPdsHandler` consumes a small `PdsHttpExchange` boundary rather than using
JDK `HttpExchange` throughout its routing and endpoint logic. The current JDK
21 `HttpServer` adapter remains sufficient for ordinary XRPC, but that JDK
version closes streams on a `101 Switching Protocols` response and cannot host
the required `subscribeRepos` WebSocket at the same origin. OpenJDK tracks
[native upgrade support](https://bugs.openjdk.org/browse/JDK-8368695) for a
later release.

Do not work around this with a second firehose port or long polling: both would
teach a wire contract different from atproto. The narrow exchange boundary
allows replacement by a WebSocket-capable engine while retaining the already
tested identity, authentication, repository, blob, and error behavior.
