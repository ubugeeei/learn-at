# Implementation scope and completion criteria

## Policy

Optimize first for learnability, then interoperability and explicit security
boundaries. Brevity is not itself a goal. Use the JDK for plumbing outside the
protocol core; keep central wire formats visible in this repository.

The evidence for each completed reference-scope capability is indexed in the
[coverage matrix](coverage.md).

## Dependency policy

Runtime and test dependencies are zero by default.

| Area | Choice | Reason |
| --- | --- | --- |
| HTTP client | `java.net.http.HttpClient` | reimplementing HTTP is out of scope |
| HTTP server | JDK `HttpServer` | keep routing/XRPC visible |
| JSON | custom | expose Lexicon values and error handling |
| DNS | JNDI DNS provider | wire-level DNS is not the identity lesson |
| cryptographic primitives | JCA | custom primitives are unsafe |
| DAG-CBOR, CID, CAR | custom | central to repository interoperability |
| database | file-backed state | make persistence boundaries observable |
| tests | small custom runner | no framework dependency in production code |

## Protocol core versus application

Treat `com.atproto.*` identity, server, repository, and sync behavior as core.
Use `app.bsky.*` in client exercises without making timeline algorithms or
moderation UI a PDS responsibility.

## Milestone criteria

### Learning PDS

- single process and account;
- file-backed records, signing key, and revision;
- P-256 repository signing;
- create, put, delete, get, and list records;
- complete repository CAR export;
- bounded content-addressed blob upload, persistence, retrieval, and verification;
- full-CAR polling mirror implemented;
- legacy session implemented;
- resumable server firehose, production media processing, OAuth server, and multi-account storage
  remain explicit future slices.

### Interoperability client

- bidirectional handle/DID resolution;
- public XRPC queries and binary responses;
- explicit legacy-session development mode;
- Lexicon data parsing and validation;
- CAR and repository-signature verification;
- full resynchronization and event-frame/WebSocket consumer;
- OAuth metadata, PKCE/PAR/callback/token models, and DPoP proof primitives;
- live OAuth HTTP/browser orchestration, durable cursor recovery, and automatic
  reconnect tracked separately as they are implemented.

### Why this reference alone is not production-ready

These require an operating organization, policy, and continuous security work
beyond protocol comprehension:

- hardened internet-facing OAuth authorization UI;
- distributed storage, multi-process transactions, and large blob storage;
- email verification, recovery, migration, and PLC rotation-key custody;
- abuse prevention, rate limits, spam detection, and moderation operations;
- internet-scale Relay/AppView indexing;
- SLOs, on-call, disaster recovery, privacy, and legal compliance.

A local endpoint returning 200 is not evidence that these conditions are met.

## Specification tracking

Tests are grouped by contract:

- `syntax`: official valid/invalid interoperability fixtures;
- `codec`: canonical bytes, limits, and round trips;
- `crypto`: official signature fixtures;
- `repo`: insertion independence, reachability, and tamper rejection;
- `xrpc`: exact request/response wire behavior;
- `e2e`: client to PDS to CAR to independent verifier.

When updating the guide, record the official implementation commit and review
date in the README.
