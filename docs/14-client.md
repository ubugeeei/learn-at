# 14: Build a typed client and CLI

## Goal

Use the identifier, identity, XRPC, JSON/IPLD, and CAR layers through a client API. Read public records, authenticate in the learning-mode legacy flow, mutate records, paginate collections, and export a repository.

Implementation:

- `src/main/scala/learnat/client/AtpClient.scala`
- `src/main/scala/learnat/client/ClientMain.scala`

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

Legacy sessions are included because their single request makes the first auth boundary observable. User-facing network clients should finish at the OAuth chapter instead.

## CLI

Start the local PDS in one terminal:

```console
$ LEARN_AT_PASSWORD=local-secret nix develop --command sbt "runMain learnat.Main pds 2583"
```

Read and list records:

```console
$ nix develop --command sbt \
    "runMain learnat.Main client list http://localhost:2583 did:web:localhost%3A2583 com.example.note"
```

Create a record. The password comes from the environment, not argv:

```console
$ LEARN_AT_PASSWORD=local-secret nix develop --command sbt \
    'runMain learnat.Main client post http://localhost:2583 alice.test com.example.note "hello from Scala"'
```

Export the complete repository:

```console
$ nix develop --command sbt \
    "runMain learnat.Main client export http://localhost:2583 did:web:localhost%3A2583 backup.car"
```

The dynamic port in a `did:web:localhost%3A...` DID must match the server. Port 2583 is used above to make commands stable.

## Error model

Raw XRPC errors are converted to `ClientError`, while typed decoders still reject malformed DID, handle, AT URI, CID, JSON, and DAG-JSON values. A 2xx response with the wrong schema is not success.

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
3. Preserve `XrpcError.Remote` fields in `ClientError` so retry policy can distinguish 400, 401, 429, and 5xx.
4. Add a session refresh method and test that an access token cannot be used as a refresh token.
5. Add an OAuth-backed authenticated client without changing public read methods.

