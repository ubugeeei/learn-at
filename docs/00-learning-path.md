# 00: Learning path

## How to use this guide

The chapters follow dependency order. You do not need to memorize unfamiliar
terms in advance. At each stage, ask: what is being identified, who asserted
the value, and which exact bytes are moving?

Use this loop in every chapter:

1. State the goal in one sentence.
2. Inspect the wire format or data structure by hand.
3. Write the smallest correct implementation.
4. Run the success path.
5. Corrupt one byte or field and prove it is rejected.
6. Separate specification MUST/SHOULD rules from implementation choices.

## Overview

| Phase | Chapters | Deliverable | What you can explain afterward |
| --- | --- | --- | --- |
| 0 | 00–03 | environment, mental model, Scala basics | protocol/application boundaries |
| 1 | 04–06 | JSON, identifiers, XRPC client | what `at://` and `/xrpc/` identify |
| 2 | 07–08 | identity resolver, Lexicon validator | independent PDS discovery and typed data |
| 3 | 09–12 | DAG-CBOR, CID, CAR, MST | how repositories detect tampering |
| 4 | 13 | signed repository | how a DID key authenticates a commit |
| 5 | 14–15 | CLI client, minimal PDS | complete client/server record writes |
| 6 | 16 | export and firehose consumer | batch versus streaming synchronization |
| 7 | 17 | legacy session and OAuth | authentication, authorization, token binding |
| 8 | 18–19 | federation lab, production checklist | AppView/Relay and operational boundaries |

## Recommended order

### Phase 0: Build a map

- [01: The AT Protocol mental model](01-mental-model.md)
- [02: Build the environment with Nix](02-environment.md)
- [03: Express invariants with Scala 3](03-scala-foundations.md)

Treat identity, hosting, data, and indexing as separate movable and verifiable
parts instead of hiding them behind the vague word “decentralized.”

### Phase 1: Use ordinary HTTP

- [04: Dependency-free JSON codec](04-json.md)
- [05: DID, handle, NSID, AT URI, record key, and TID](05-identifiers.md)
- [06: XRPC queries and procedures](06-xrpc.md)

The first artifact is a CLI that calls public endpoints. Authentication and
CBOR are not needed yet.

### Phase 2: Discover an account server

- [07: Bidirectional handle and DID verification](07-identity.md)
- [08: Lexicon and schema-driven validation](08-lexicon.md)

Implement why a user-supplied handle is not a trusted URL, and how a DID
document yields a PDS endpoint and repository signing key.

### Phase 3: Understand repository bytes

- [09: Deterministic DAG-CBOR](09-dag-cbor.md)
- [10: Multihash, CID, and multibase](10-cid.md)
- [11: CAR v1](11-car.md)
- [12: Merkle Search Tree](12-mst.md)

This is the protocol core. We implement a narrow correct codec instead of
hiding the byte-level rules behind a general CBOR/IPLD library.

### Phase 4: Authenticate a repository

- [13: P-256, signatures, commits, and revisions](13-signed-repository.md)

Verify why the account signs a repository root rather than each isolated
record.

### Phase 5: Connect client and PDS

- [14: Practical CLI client](14-client.md)
- [15: Minimal PDS](15-local-pds.md)

Connect `createRecord`, commits, block storage, `getRecord`, and `getRepo` in
one end-to-end path.

### Phase 6: Transfer data

- [16: CAR mirror and event stream](16-sync.md)

Build the state machine: repository export for bootstrap, firehose events for
incremental updates, and full resynchronization after a gap.

### Phase 7: Delegate authority safely

- [17: From legacy sessions to OAuth](17-oauth.md)

The legacy `createSession` endpoint comes first for teaching, but the final
user-facing client target is the [AT Protocol OAuth profile](https://atproto.com/specs/oauth).

### Phase 8: Evaluate production readiness

- [18: Federation with AppView, Relay, and Labeler](18-federation.md)
- [19: Production-readiness review](19-production-readiness.md)

These chapters turn the difference between “a PDS responds” and “a PDS is safe
to expose to the internet” into an explicit checklist.

## Completion test

You are done when you can draw these without notes and diagnose real failures
from logs:

1. `alice.example.com` to DID, DID document, and PDS;
2. JSON record to DAG-CBOR block, CID, MST root, signed commit, and CAR;
3. client/PDS/Relay/AppView request and data flows;
4. OAuth discovery, PAR, PKCE, callback state, and DPoP nonce handling;
5. what must be revalidated after PDS downtime, migration, key rotation, or a
   stream gap.
