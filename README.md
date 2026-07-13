# learn-at

A hands-on guide to AT Protocol that builds both a Scala 3 client and a Personal
Data Server (PDS).

This repository does not begin with a finished SDK. It constructs string
validation, JSON, HTTP, DID resolution, DAG-CBOR, CIDs, CAR, Merkle Search
Trees, signed repositories, synchronization, and authentication in dependency
order. Every chapter covers:

- why the mechanism exists;
- the invariants required by the specification;
- a minimal Scala 3 implementation;
- commands that let you observe it;
- exercises that deliberately break it;
- the remaining gap before production use.

## Start here

There are three useful entry points. They serve different jobs:

1. [`README.md`](README.md) is the map of the repository.
2. [`docs/00-prerequisites.md`](docs/00-prerequisites.md) explains the web,
   identity, hash, signature, JSON, and schema ideas assumed by the book.
3. [`docs/00-learning-path.md`](docs/00-learning-path.md) is the table of
   contents. Read the chapters in number order.
4. [`src/learnat/LearnAt.scala`](src/learnat/LearnAt.scala) is the executable
   entry point. Run it when you want to observe the implementation.

If you want to read code before prose, use the
[`src/README.md`](src/README.md) source tour. It traces one record through the
client, validation, repository, cryptography, and storage boundaries.

The source tree is organized by concept, not by build phase. Production code
and its executable test sit beside each other:

```text
src/learnat/
├── LearnAt.scala                 one obvious executable entry point
├── json/
│   ├── Json.scala                implementation
│   └── Json.test.scala           examples, edge cases, and rejection paths
├── syntax/
│   ├── Identifiers.scala
│   ├── Identifiers.test.scala
│   └── IdentifiersInterop.test.scala
└── ...                           one directory per protocol concept
```

The `.test.scala` suffix is the boundary: sbt compiles ordinary `.scala` files
as the application and adjacent `.test.scala` files as tests. This keeps the
code you are learning and the evidence for its behavior on the same screen
without shipping test code in the application.

## Run it

The development environment is a Nix flake. You do not need to install the JDK,
Scala, or sbt separately. Protocol codecs remain implemented in the repository;
the local server uses Undertow Core for standards-compliant HTTP/WebSocket
transport instead of reimplementing a network server.

```console
$ nix develop --command sbt verify
$ nix develop --command sbt run
```

Format all Scala and sbt sources with the repository-pinned formatter:

```console
$ nix develop --command sbt scalafmtAll
```

`verify` runs `scalafmtCheckAll`, so CI rejects formatting drift.

Run the local client/server exercise:

```console
$ LEARN_AT_PASSWORD=local-secret nix develop --command sbt "run pds 2583"
$ LEARN_AT_PASSWORD=local-secret nix develop --command sbt \
    'run client post http://localhost:2583 alice.test com.example.note "hello"'
```

The local PDS persists its development key, records, last revision, and bounded
firehose history under `data/local-pds` by default. Keep the same port across
restarts because it is part of the localhost `did:web` identifier.

Start with the [learning path](docs/00-learning-path.md). Implementation
chapters point to the matching source and explain why it has its current shape,
which invariants it enforces, how tests break those invariants, and what is
still intentionally missing. See
[the Nix environment chapter](docs/02-environment.md) for setup details and the
[glossary](docs/glossary.md) whenever a protocol term is unfamiliar.

## Target skills

By the end, you should be able to explain, implement, and debug all of these:

- follow the relationship among a handle, DID, DID document, and PDS;
- read a Lexicon and construct XRPC requests and responses;
- use a CLI client with an arbitrary PDS;
- encode records as DAG-CBOR and verify their content with CIDs;
- build and verify an MST and signed repository commit;
- use CAR exports and a firehose for continuous synchronization;
- host a local account and read or write it from a separate client;
- explain OAuth discovery, PKCE, PAR, DPoP, and legacy-session differences;
- evaluate persistence, rate limits, key custody, moderation, and backup needs.

Every chapter and the [implementation scope](docs/architecture/scope.md) keeps
“implemented for learning” distinct from “required for a production PDS.”
The [coverage matrix](docs/architecture/coverage.md) links every claimed topic
to its explanation, runnable implementation, executable specification, and
explicit exclusions.

## Specification baseline

The normative source is the [AT Protocol specifications](https://atproto.com/specs/atp).
This guide was checked on 2026-07-10 against the official
`bluesky-social/atproto` implementation at commit
`a9ff2da83b93e5211f45667e652f8a928f4dce29`. When the guide and specification
disagree, follow the specification.

The project uses Scala 3.8.4 and sbt 1.10.11 for the build definition only.
