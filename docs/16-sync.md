# 16: Mirror a repository and decode the firehose

## Goal

Keep a local, authenticated view of an account repository. Learn why a full
CAR resync and a resumable event stream are complementary rather than competing
sync mechanisms.

Implementation:

- `src/learnat/sync/Sync.scala`
- `src/learnat/sync/CursorStore.scala`
- `src/learnat/sync/EventLog.scala`
- `src/learnat/ipld/Ipld.scala`
- `src/learnat/client/AtpClient.scala`

## Two sync paths

An atproto consumer needs both paths:

```text
bootstrap or cursor gap                    steady state
-----------------------                    ------------
getLatestCommit                           subscribeRepos(cursor)
        |                                         |
        v                                         v
getRepo -> complete CAR                    framed commit events
        |                                         |
        v                                         v
verify commit + signature + MST            apply and verify new blocks
        |                                         |
        +----------------> local view <------------+
```

The full export is deliberately the first implementation. It is less efficient,
but it establishes the recovery operation that remains necessary when events
were missed, a cursor is no longer available, or incremental state is suspect.

## A transactional mirror

`RepositoryMirror.syncOnce` performs this sequence:

1. Call `com.atproto.sync.getLatestCommit`.
2. Return `Unchanged` when both advertised CID and revision match the snapshot.
3. Download `com.atproto.sync.getRepo` as raw CAR bytes.
4. Verify every block CID, the signed commit, the DID, the P-256 signature, the
   reachable MST, record paths, and record blocks.
5. Reject a repository revision too far in the future.
6. Replace the snapshot only after every check succeeds.

The last rule is the important state boundary. A network error, malformed CAR,
missing block, wrong signing key, or invalid tree returns an error without
partially changing the materialized view.

The verifier currently receives a trusted public key explicitly. A production
consumer obtains the current `#atproto` verification method from the DID
document and must handle DID/key rotation policy between checkpoints.

## Try polling against the local PDS

The end-to-end test starts the PDS, synchronizes an empty repository, writes a
record through the authenticated client, and synchronizes again:

```console
$ nix develop --command sbt 'verify'
```

Look for `Repository synchronization` in the output. Then read
`SyncTests.scala` beside `RepositoryMirror`: the test using a wrong key proves
that failed verification leaves the mirror empty.

The local PDS implements `getLatestCommit` and `getRepo`, so this polling path is
fully executable. It also publishes repository writes from the same origin over
the WebSocket stream described below.

## Event-stream framing

`com.atproto.sync.subscribeRepos` uses a WebSocket binary message containing
two concatenated DAG-CBOR values, without an outer array:

```text
CBOR header map || CBOR body
```

A normal message header has:

```json
{"op": 1, "t": "#commit"}
```

An error frame has `op = -1`; its body contains an `error` string and an
optional `message`. `DagCbor.decodeSequence` decodes concatenated values while
preserving the canonical-CBOR checks already used for repository data.

`EventStreamCodec` requires exactly two values, an integer operation, and a
`#`-prefixed message type. `FirehoseClient`:

- converts an HTTP(S) PDS origin into WS(S);
- calls `/xrpc/com.atproto.sync.subscribeRepos` with an optional cursor;
- assembles fragmented binary WebSocket messages;
- rejects frames over 5 MiB;
- rejects unexpected text frames;
- decodes before invoking application code.

The codec is bidirectional. The producer uses `EventStreamCodec.encode` to emit
the same canonical `header || body` representation that the consumer decodes.

## Server-side retention and cursor errors

`RetainedEventLog` is the transport-independent producer core:

```mermaid
flowchart LR
  W["repository write"] --> E["assign increasing seq"]
  E --> C["canonical event frame"]
  C --> Q["bounded retained window"]
  U["subscriber cursor"] --> Q
  Q -->|"inside window"| B["bounded event batch"]
  Q -->|"too old"| OLD["ConsumerTooSlow"]
  Q -->|"ahead of head"| FUTURE["FutureCursor"]
```

Sequences start at one and are never reused. When capacity is reached, only the
oldest event is discarded. A cursor outside the retained window produces an
explicit error instead of silently skipping changes. Batches are capped at
1,000 events and return defensive byte copies so a subscriber cannot mutate the
producer log.

The local PDS now constructs a `#commit` event inside the same synchronized
state transition as each repository create, update, or delete. The event
contains the DID, commit CID, revision, previous revision, operation path/CID,
and a complete CAR block slice. Encoding/retention happens before persistence,
but publication happens only after persistence succeeds. If persistence fails,
only the unpublished tail event is rolled back; the current in-memory head is
not advanced and subscribers never see the failed write. A non-tail rollback
is rejected so concurrent publication cannot erase another write.

## From an HTTP request to a live WebSocket event

The PDS uses one origin for normal XRPC and streaming XRPC. Undertow routes only
the exact subscription path to its WebSocket handshake; all other paths keep
using the ordinary request/response router:

```mermaid
sequenceDiagram
  participant C as "FirehoseClient"
  participant W as "WebSocket route"
  participant L as "RetainedEventLog"
  participant P as "Repository transaction"
  C->>W: "GET subscribeRepos?cursor=41 + Upgrade"
  W->>L: "validate cursor and subscribe"
  L-->>C: "replay seq 42..head"
  Note over L,C: "replay and registration share one lock"
  P->>L: "retain unpublished commit"
  P->>P: "persist repository"
  P->>L: "publish durable commit"
  L-->>C: "live next sequence"
  C-xW: "normal close"
  W->>L: "remove subscription"
```

Cursor validation, retained replay, and live registration are one atomic event
log operation. Therefore a write cannot land in the small interval between
"finished replay" and "registered for live events". Replay and live frames are
enqueued in increasing sequence order. Closing the socket removes the callback;
closing the returned registration twice is safe.

Malformed cursors produce an `InvalidRequest` event-error frame. A cursor ahead
of the producer produces `FutureCursor`; one older than the bounded window
produces `ConsumerTooSlow`. In the last case a consumer must run the verified
full-CAR recovery path and establish a new checkpoint.

The declarative tests cover the protocol at two levels:

- `EventLogTests` specifies unpublished visibility, replay-before-live order,
  cursor rejection, defensive copies, retention, rollback, and idempotent close.
- `SyncTests` opens a real JDK WebSocket against the real Undertow PDS, checks a
  retained event, reconnects from its cursor, performs a repository write, and
  checks the next live event plus a canonical future-cursor error.

The callback still receives generic IPLD. A later application layer should
dispatch on the event type and validate its body against the corresponding
Lexicon schema before applying it.

## Cursor state machine

A practical consumer should persist the event cursor only after its durable
state update succeeds:

```text
connect(saved cursor)
        |
        v
decode -> verify -> durable apply -> save cursor
  |          |             |
  +--error---+-------------+----> reconnect with backoff
                              \
                               +-> full resync if cursor cannot resume
```

Do not save the cursor immediately after receiving a frame. A crash between the
cursor write and data write would permanently skip that event. Either commit
both in one storage transaction or make event application idempotent and write
the cursor last.

## Durable at-least-once checkpoint

`CheckpointedFrameHandler` implements the safe ordering used by a consumer:

```mermaid
flowchart LR
  F["decoded frame"] --> S["validate non-negative seq"]
  S --> O["require seq newer than saved cursor"]
  O --> A["apply message"]
  A --> C["atomically replace cursor file"]
  A -->|"failure"| OLD["leave old cursor"]
  C -->|"failure"| REPLAY["reconnect from old cursor<br/>message may replay"]
```

`FileCursorStore` writes one non-negative base-10 sequence to a temporary file
and then renames it over the checkpoint. It rejects corrupt and oversized state.
`ATOMIC_MOVE` is used when the filesystem supports it.

The ordering chooses at-least-once delivery. It avoids event loss: failed
application never advances the cursor. If application succeeds but the cursor
write fails, the event can be delivered again after restart. Therefore the
application callback must be idempotent, or its materialized state and cursor
must share a stronger external transaction. Tests cover both crash boundaries.

## Exercises

1. Change one byte in an exported CAR and confirm the mirror keeps its previous
   snapshot.
2. Return an advertised revision newer than the exported commit and observe the
   consistency failure.
3. Feed `EventStreamCodec` one, two, and three concatenated CBOR values.
4. Add exponential backoff with jitter and a cancellation handle around
   `FirehoseClient`.
5. Replace the file checkpoint with a database transaction shared by an
   idempotent materialized view.
6. Set event retention to two, publish three writes, then reconnect from cursor
   zero and explain why full resynchronization is required.

## What is still missing

This chapter provides a verified full-repository mirror, canonical producer and
consumer framing, bounded server retention, same-origin WebSocket publication,
cursor resumption, and durable at-least-once consumer checkpoints. It does not
yet implement incremental commit application, local Relay behavior, or a
bounded per-connection backpressure queue. File-backed PDS state retains the
bounded server event suffix across restarts; an in-memory PDS intentionally does
not. Blob HTTP upload/download exists in the local PDS, while
automatic fetching of blobs referenced by streamed records remains application
work.

## Specifications

- [Sync](https://atproto.com/specs/sync)
- [Event Stream](https://atproto.com/specs/event-stream)
- [Repository](https://atproto.com/specs/repository)
- [HTTP API (XRPC)](https://atproto.com/specs/xrpc)
