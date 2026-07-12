# 19: Perform a production-readiness review

## Goal

Turn “the demo works” into an evidence-based go/no-go review. Identify which
properties are proven by this repository and which require new engineering,
operations, security review, and organizational policy.

## Start with a threat model

List assets before choosing infrastructure:

- account signing and PLC rotation/recovery keys;
- passwords, OAuth sessions, DPoP private keys, and refresh tokens;
- public repository records and private account data;
- blobs and upload-processing workers;
- handles, email addresses, recovery factors, and audit history;
- moderation reports and administrative authority;
- availability of writes, reads, exports, and migration.

List actors and failures:

- malicious unauthenticated client;
- compromised account/client/PDS administrator;
- hostile PDS, Relay, AppView, Labeler, DNS answer, or metadata endpoint;
- dependency or build compromise;
- disk corruption, partial write, clock skew, network partition, and region loss;
- operator mistake and accidental deletion;
- spam campaigns, abusive uploads, and legal requests.

Every checklist item below should connect an asset and failure to a prevention,
detection, response, and recovery mechanism.

## Gate 1: protocol correctness

- Run official syntax and cryptographic interoperability fixtures.
- Verify canonical DAG-CBOR, CIDs, CAR block hashes, MST reachability, and commit
  signatures independently of the HTTP server.
- Reject revision rollback and unexpected future revisions.
- Preserve unknown fields/open-union variants during read-modify-write.
- Test malformed, truncated, duplicate, oversized, deeply nested, and
  non-canonical inputs.
- Pin the specification/reference commit used for compatibility testing.
- Add differential and fuzz tests before accepting arbitrary internet input.

This repository provides deterministic unit/E2E tests for the first several
items. It is not fuzzed or independently audited.

## Gate 2: network and deployment boundary

- Terminate TLS with automated issuance, renewal, and expiry alerting.
- Bind the application only behind a trusted reverse proxy and define exactly
  which forwarded headers are honored.
- Use separate registrable domains for an application and its PDS/blob surface
  where required by the production security model; do not casually mix
  untrusted media with credential pages.
- Apply strict CORS per OAuth/client requirements rather than `*` by default.
- Validate every redirect and service endpoint after DNS resolution and at
  connection time.
- Block private/link-local/metadata destinations for resolver/proxy fetches.
- Bound connection, TLS, first-byte, read, total, and idle time.
- Bound request and response bytes before buffering them.
- Configure graceful shutdown, readiness, liveness, and connection draining.

The local PDS deliberately binds loopback HTTP. That is a development feature,
not a deployment configuration.

## Gate 3: keys and secrets

Separate key purposes:

| Key/secret | Purpose | Production expectation |
| --- | --- | --- |
| repository signing key | sign account commits | encrypted, access-controlled, rotatable |
| PLC rotation/recovery key | change identity document | stronger isolated custody, recovery ceremony |
| OAuth client key | confidential-client assertions | published JWKS, rotation, rapid removal |
| DPoP session key | bind one client session | unique per session/device, encrypted local storage |
| session/cookie keys | authenticate server sessions | versioned rotation and revocation |
| password verifier | authenticate legacy/local users | memory-hard policy where appropriate, migration plan |

- Never put secrets in argv, logs, metrics, crash reports, or repository files.
- Use a secret manager or KMS/HSM according to risk.
- Define key version identifiers and overlapping rotation windows.
- Practice repository-key, OAuth-key, cookie-key, and catastrophic recovery-key
  rotation separately.
- Audit every administrative key use.

The learning PDS stores plaintext PKCS#8 protected only by filesystem mode. It
must be replaced before internet exposure.

## Gate 4: transactional storage

A repository write has several coupled effects:

```text
validate record
  -> store record block
  -> build/update MST blocks
  -> sign and store commit
  -> update account head/revision
  -> append durable event
  -> acknowledge client
```

Define one durable transaction or a recoverable write-ahead protocol. A crash
must not expose a head that references missing blocks, publish an event for an
uncommitted write, or acknowledge data that cannot be restored.

Production storage also needs:

- multi-process concurrency control and conditional writes;
- per-account quotas and global capacity controls;
- database migrations with rollback/forward-recovery plans;
- integrity scrubbing and orphan/block garbage-collection policy;
- encryption at rest and access separation;
- blob metadata/content consistency and malware/media processing isolation.

The local whole-file atomic rename demonstrates one boundary for one account.
It is not a multi-process database.

## Gate 5: backup, restore, and migration

Define RPO and RTO first. Then prove them.

- Back up repository/account databases, blobs, PDS-wide identity/account state,
  and required key material using separate retention/access policies.
- Encrypt backups and test that deletion/retention policy applies to copies.
- Record commit/revision checkpoints so replay is deterministic.
- Continuously verify backup block CIDs and signed repository structure.
- Restore into an isolated environment on a schedule.
- Measure data loss and time to service, not merely backup-job success.
- Exercise one-account restore, full-service restore, and PDS migration.
- Protect backups from the same credentials/ransomware path as primary storage.

An exported CAR is valuable but incomplete without blobs, private account
state, identity rotation authority, and a rehearsed restore procedure.

## Gate 6: OAuth and account security

- Publish correct protected-resource, authorization-server, and client metadata.
- Harden metadata fetching against SSRF and redirects.
- Bind account DID, PDS resource, authorization issuer, callback `iss`, state,
  token `sub`, client ID, and DPoP key.
- Store state/PKCE/DPoP data server-side or in encrypted platform storage;
  consume state once.
- Require PAR and PKCE S256; never accept implicit flow or `plain` PKCE.
- Rotate mandatory DPoP nonces within five minutes and reject proof replay.
- Serialize single-use refresh, atomically replacing tokens.
- Scope permissions minimally and display untrusted client metadata carefully.
- Add secure cookies, CSRF protection, session fixation defense, MFA/passkeys as
  appropriate, and tested account recovery.
- Rate-limit login, PAR, token, refresh, recovery, and administrative paths.

This repository implements and tests the core value/cryptographic primitives,
not the authorization UI, durable session store, or complete HTTP server flow.

## Gate 7: synchronization and federation

- Persist a cursor only after durable event application.
- Make event processing idempotent and ordered per repository.
- Detect sequence/revision gaps and fall back to a verified full export.
- Bound WebSocket frames, queues, concurrent repository fetches, and retries.
- Use exponential backoff with jitter and a circuit breaker for failing hosts.
- Re-resolve DID keys/location at defined checkpoints and during rotation.
- Quarantine invalid repositories without blocking the entire stream.
- Measure lag from source revision to indexed view.
- Define Relay crawl, retention, takedown, and reintroduction policy.

The current mirror proves a transactional full-CAR recovery path. Durable
cursors, incremental commit application, and the local firehose producer remain
unfinished.

## Gate 8: rate limits and resource isolation

Limit by more than source IP:

- account DID, session/client ID, endpoint NSID, PDS host, and administrative
  principal;
- request bytes, decoded complexity, records/operations, blob bytes, and CPU;
- concurrent connections, WebSockets, queued work, and outbound destinations;
- signup, login, write, upload, export, and sync rates separately.

Return stable errors and appropriate retry hints without revealing sensitive
policy. Share counters across processes. Load test normal traffic and adversarial
shapes, including small deeply nested values and valid expensive signatures.

## Gate 9: abuse, moderation, and privacy

Operating open signup is an abuse-management commitment.

- Use staged/invite signup, velocity limits, and bot-cost controls.
- Provide report, appeal, takedown, suspension, and reinstatement workflows.
- Define PDS, Relay, AppView, and Labeler actions separately.
- Authenticate labels and retain their source and subject version.
- Restrict moderator access to private account data and audit every access.
- Avoid placing PII or record content in metrics/log labels.
- Define retention/deletion behavior for primary data, derived indices, events,
  backups, caches, and moderation evidence.
- Staff response channels and escalation before launch.

Cryptographic validity answers who signed data, not whether hosting or promoting
it is safe, legal, or consistent with service policy.

## Gate 10: observability and incident response

Create structured, redacted events with correlation IDs for:

- XRPC method/status/latency and bounded error category;
- account commit CID/revision transitions (without secret material);
- OAuth issuer/client/session lifecycle and nonce/replay failures;
- sync cursor, lag, gaps, full resync, and invalid repositories;
- storage latency, transaction conflicts, capacity, and backup checkpoints;
- moderation/admin actions.

Define service-level indicators and objectives for authenticated writes, public
reads, authorization completion, repository export, stream lag, and restore.
Alert on symptoms tied to user impact, not every isolated exception.

Maintain runbooks for key compromise, credential leak, corrupt repository,
stalled firehose, database saturation, expired TLS, broken DNS/DID resolution,
abuse surge, and failed restore. Run game days.

## Gate 11: supply chain and release

- Keep `flake.lock`, Scala, sbt, and action revisions reviewable and updated.
- Generate an SBOM and scan source, artifacts, containers, and Nix closure.
- Protect the release branch, require reviewed CI, and sign release artifacts.
- Build from clean source and retain provenance.
- Separate development/test credentials and data from production.
- Use canary rollout with automatic health/lag/error rollback criteria.
- Make storage/schema changes backward-compatible across the rollout window.

Zero application dependencies reduce exposure but do not remove risk from the
JDK, build tooling, OS/Nix closure, CI actions, or custom parser code.

## Release decision template

For every gate, record:

```text
owner:
status: pass | fail | accepted risk
evidence: test, dashboard, drill, review, or artifact link
last verified:
next review:
rollback/response:
```

“Implemented” is not evidence. A passing restore drill, externally reviewed
threat model, measured load test, or verified artifact is evidence.

## This repository's honest status

Safe learning use:

- Nix-reproducible loopback environment;
- dependency-light typed client and single-account local PDS;
- persistent development key/records/revision;
- strict codecs, official fixtures, signed repository, CAR export/verification;
- full-snapshot synchronization and event consumer;
- legacy-session exercise and OAuth/DPoP primitives;
- more than 120 focused tests at the time this chapter was written.

Not approved for internet production:

- plaintext local signing-key file;
- no HTTPS, multi-account database, blobs, durable firehose, or account
  creation/recovery/migration;
- no complete OAuth server/client orchestration;
- no distributed rate limit, abuse/moderation operation, backup service, SLO,
  on-call, or external security audit.

Do not remove this boundary merely because a feature demo succeeds.

## References

- [Self-hosting](https://atproto.com/guides/self-hosting)
- [Going to production](https://atproto.com/guides/going-to-production)
- [Accounts](https://atproto.com/specs/account)
- [OAuth](https://atproto.com/specs/oauth)
- [Media blobs](https://atproto.com/specs/blob)
- [Labels](https://atproto.com/specs/label)
- [Sync](https://atproto.com/specs/sync)
