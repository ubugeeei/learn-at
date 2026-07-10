# 15: Run a minimal client-compatible PDS

## Goal

Host one account, authenticate a client, commit public records, serve repository reads and CAR exports, and publish a localhost DID document. Understand exactly why this is a learning PDS rather than an internet-ready service.

Implementation:

- `src/main/scala/learnat/pds/Auth.scala`
- `src/main/scala/learnat/pds/LocalPds.scala`

## Start it

```console
$ LEARN_AT_PASSWORD=local-secret \
  LEARN_AT_HANDLE=alice.test \
  nix develop --command sbt "runMain learnat.Main pds 2583"
```

The server binds to loopback and prints:

```text
Local PDS listening at http://localhost:2583
DID: did:web:localhost%3A2583
```

Use a real password in the environment. The built-in fallback exists only to keep the learning command discoverable and must not be exposed.

## Identity endpoints

The PDS publishes:

```text
GET /.well-known/did.json
GET /.well-known/atproto-did
GET /xrpc/com.atproto.identity.resolveHandle
```

The DID document includes:

- `alsoKnownAs: at://alice.test`;
- a P-256 `#atproto` Multikey;
- an `#atproto_pds` service at the loopback origin.

HTTP PDS service endpoints are accepted only because the resolver is explicitly configured for localhost development.

## Supported XRPC endpoints

Server/identity:

```text
com.atproto.server.describeServer
com.atproto.identity.resolveHandle
com.atproto.server.createSession
com.atproto.server.getSession
com.atproto.server.refreshSession
com.atproto.server.deleteSession
```

Repository/sync:

```text
com.atproto.repo.getRecord
com.atproto.repo.listRecords
com.atproto.repo.createRecord
com.atproto.repo.putRecord
com.atproto.repo.deleteRecord
com.atproto.repo.describeRepo
com.atproto.sync.getRepo
com.atproto.sync.getLatestCommit
```

Public reads do not require a token. Mutations require an access-scoped bearer token for the hosted DID.

## Passwords and sessions

Passwords are stored as PBKDF2-HMAC-SHA-256 with:

- a random 128-bit salt;
- at least 100,000 iterations (210,000 by default);
- a 256-bit derived value;
- constant-time verification.

The encoded format is versioned and round-trip tested.

Legacy access and refresh values are HS256 JWT-shaped tokens with DID, scope, issue/expiry time, and a random JTI. Access and refresh scopes are not interchangeable. Refresh rotates and revokes the previous JTI; explicit revocation and expiration are tested.

This is not the atproto OAuth profile. It is the deliberately earlier legacy-auth learning step.

## Request boundaries

The HTTP adapter enforces:

- exact method per route;
- `application/json` for JSON procedures;
- a configurable JSON body byte limit;
- typed JSON/identifier/IPLD decoding;
- repository ownership on every read/write parameter;
- bounded worker threads;
- structured XRPC error bodies;
- no-store response caching.

Repository state changes only after an immutable atomic write returns a signed commit.

## What the E2E test proves

A passing test proves that, in one process on loopback:

```text
client JSON
  -> XRPC HTTP
  -> auth and record validation
  -> DAG-CBOR record
  -> MST rebuild
  -> P-256 signed commit
  -> public read
  -> CAR export
  -> independent signature/tree/record verification
```

It does not prove internet-safe deployment.

## Production gaps

Do not expose this server publicly without replacing or adding:

- durable transactional storage and stable key persistence;
- HTTPS termination with trusted proxy/header policy;
- OAuth discovery, PAR, PKCE, DPoP, nonce handling, and permissions;
- account creation/recovery/migration and PLC rotation-key custody;
- blob storage and upload validation;
- WebSocket firehose, cursor persistence, and backpressure;
- rate limits, abuse/spam controls, moderation, and takedowns;
- SSRF-hardened identity resolution;
- metrics, structured logs, tracing, backup, restore, and disaster drills;
- fuzzing and an external security review.

These are explicit later steps, not hidden behind the phrase "minimal PDS."

## Exercises

1. Send a mutation with no token, an access token, and a refresh token; compare errors.
2. Lower `maxJsonBodyBytes` and verify a large record is rejected before JSON parsing.
3. Kill and restart the current server and observe why volatile keys/records make its DID state inconsistent. This motivates persistence.
4. Add conditional write CIDs (`swapCommit`/`swapRecord`) before concurrent clients.
5. Add a second account and identify every single-account assumption that must move into storage keys.

## Specifications

- [Accounts](https://atproto.com/specs/account)
- [HTTP API (XRPC)](https://atproto.com/specs/xrpc)
- [Repository](https://atproto.com/specs/repository)
- [Sync](https://atproto.com/specs/sync)

