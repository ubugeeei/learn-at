# 14: Build a typed client and CLI

## Goal

Use the identifier, identity, XRPC, JSON/IPLD, and CAR layers through a client API. Read public records, authenticate in the learning-mode legacy flow, mutate records, paginate collections, and export a repository.

Implementation:

- `src/learnat/client/AtpClient.scala`
- `src/learnat/client/ClientMain.scala`

## Known service versus discovery

For local development, construct a client from a known origin:

```scala
AtpClient.create(URI.create("http://localhost:2583"))
```

For a network account, resolve the identity first:

```scala
AtpClient.discover(identifier, identityResolver)
```

Discovery verifies the handle/DID relationship and binds the client to the PDS origin declared in the DID document.

## Public reads

```scala
client.getRecord(repo, collection, rkey)
client.listRecords(repo, collection, limit = 50, cursor = None)
client.getRepo(did)
```

`RecordView` keeps the three identifiers separate:

```text
uri    stable record location
cid    content version
value  decoded IPLD record
```

The repository export remains raw CAR bytes until the caller supplies an independently resolved DID signing key to `RepositoryVerifier`.

## Legacy login

```scala
client.login(identifier, passwordChars)
```

The returned `AuthenticatedAtpClient` owns a `LegacySession` and adds create, put, delete, and get-session calls. The password character array is cleared after request construction. A JVM `String` still exists while JSON is encoded, so this is not secure hardware handling.

Legacy sessions are immutable values. `refreshSession` consumes the current
refresh token server-side and returns a new `AuthenticatedAtpClient`; continuing
to use the old value is an error because token rotation revoked it. `revokeSession`
revokes the access session but does not delete records or the account. The E2E
suite proves replacement tokens work, reused refresh tokens fail, and revoked
access tokens can no longer call `getSession`.

Legacy sessions are included because their single request makes the first auth boundary observable. User-facing network clients should finish at the OAuth chapter instead.

## CLI

Start the local PDS in one terminal:

```console
$ LEARN_AT_PASSWORD=local-secret nix develop --command sbt "run pds 2583"
```

Read and list records:

```console
$ nix develop --command sbt \
    "run client list http://localhost:2583 did:web:localhost%3A2583 com.example.note"
```

Create a record. The password comes from the environment, not argv:

```console
$ LEARN_AT_PASSWORD=local-secret nix develop --command sbt \
    'run client post http://localhost:2583 alice.test com.example.note "hello from Scala"'
```

`post` is a convenience command for the first exercise. For real record work,
put the complete DAG-JSON record in a file so shell quoting cannot alter it:

```json
{
  "$type": "com.example.note",
  "text": "a complete record",
  "createdAt": "2026-07-12T12:00:00.000Z"
}
```

Create with a server-generated TID record key, or provide a stable key as the
last argument:

```console
$ LEARN_AT_PASSWORD=local-secret nix develop --command sbt \
    "run client create http://localhost:2583 alice.test com.example.note record.json"
$ LEARN_AT_PASSWORD=local-secret nix develop --command sbt \
    "run client create http://localhost:2583 alice.test com.example.note record.json settings"
```

Replace and delete the stable record:

```console
$ LEARN_AT_PASSWORD=local-secret nix develop --command sbt \
    "run client put http://localhost:2583 alice.test com.example.note settings record.json"
$ LEARN_AT_PASSWORD=local-secret nix develop --command sbt \
    "run client delete http://localhost:2583 alice.test com.example.note settings"
```

The input must be a DAG-JSON object and is limited to 1 MiB before parsing.
The PDS still performs collection/type and Lexicon validation; accepting JSON
at the CLI boundary does not bypass repository invariants.

## Traverse a collection

`list` exposes the protocol's bounded page and opaque cursor instead of hiding
pagination in an unbounded client loop:

```console
$ nix develop --command sbt \
    "run client list http://localhost:2583 did:web:localhost%3A2583 com.example.note --limit 25 --reverse"
$ nix develop --command sbt \
    "run client list http://localhost:2583 did:web:localhost%3A2583 com.example.note --limit 25 --cursor '<cursor>'"
```

Limits outside 1–100 and incomplete or unknown options fail before a network
request. Cursors remain opaque: clients persist and return them unchanged.

Export the complete repository:

```console
$ nix develop --command sbt \
    "run client export http://localhost:2583 did:web:localhost%3A2583 backup.car"
```

Do not treat a downloaded CAR as trusted merely because the HTTP request
succeeded. Resolve the DID document again, obtain its current `#atproto`
Multikey, verify the commit signature, verify every CID, reconstruct the MST,
and reject missing or unreachable blocks:

```console
$ nix develop --command sbt \
    "run client verify did:plc:example backup.car"
```

The verifier returns the authenticated DID, commit CID, revision, and record
count. CAR input is capped at 64 MiB before allocation. Local HTTP DID documents
are disabled by default; the explicit `--allow-http-local` switch exists only
for the two-terminal localhost lab.

The dynamic port in a `did:web:localhost%3A...` DID must match the server. Port 2583 is used above to make commands stable.

## Error model

Raw XRPC errors become structured `ClientError` values instead of flattened
strings. Callers retain:

- `kind`: local, configuration, transport, protocol, or remote;
- HTTP `status` when a response exists;
- the stable remote XRPC `code` such as `RateLimitExceeded`;
- a conservative `retryable` decision.

Transport failures, HTTP 429, and HTTP 5xx are retryable candidates. Validation,
authentication, and malformed successful responses are not. `retryable` does
not perform a retry or promise success; callers must still use bounded attempts,
backoff, jitter, cancellation, and endpoint idempotency rules.

`RetryExecutor` supplies that policy for operations the caller knows are
idempotent. Its validated `RetryPolicy` caps total attempts and delay, doubles
the delay between failures, adds bounded jitter, stops on non-retryable errors,
and preserves thread interruption. It intentionally does not inspect an HTTP
method: wrapping `createRecord` would be unsafe unless the application provides
its own idempotency key or stable record key.

Typed decoders reject malformed DID, handle, AT URI, CID, JSON, and DAG-JSON
values. A 2xx response with the wrong schema is a protocol error, not success.

The CLI returns status 2 for usage, validation, network, remote, and file-write errors. Library callers should inspect the typed lower-level errors when they need retry policy.

## Tests

The real-socket E2E suite starts the PDS on an ephemeral loopback port and exercises:

- bad and good credentials;
- server-generated TID record keys;
- create/get/list/put/delete;
- cursor pagination;
- CAR export and independent verification;
- `did:web:localhost` resolution;
- CLI post/list without password argv.

```console
$ nix develop --command sbt verify
```

## Exercises

1. Add a `reverse` option to the CLI list command.
2. Add an export command that immediately verifies the CAR before writing it.
3. Add `Retry-After` parsing and choose whether the server hint or local backoff
   wins when both are present.
4. Add an OAuth-backed authenticated client without changing public read methods.
