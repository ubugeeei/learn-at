# 07: Verify handles and DIDs bidirectionally

## Goal

Starting from a user-supplied handle, discover the DID, DID document, PDS, and
repository signing key. Explain with code and an attack example why one-way name
resolution is not account authentication.

Implementation: `src/learnat/identity/Identity.scala`

## The resolution graph

```text
handle --DNS/HTTPS--> DID --PLC or did:web--> DID document
   ^                                             |
   |                                             +--> PDS service
   +--------------- alsoKnownAs ----------------+
                                                 +--> #atproto signing key
```

Do not call all four values “the profile.” Each edge has a different authority
and freshness policy.

## Handle to DID

### DNS TXT

The preferred route is `_atproto.<handle>` TXT:

```text
_atproto.alice.example.com. TXT "did=did:plc:..."
```

Ignore records without `did=`. If valid records claim different DIDs, fail as
ambiguous instead of choosing the resolver's first result. The JDK adapter uses
the JNDI DNS provider and removes quoted TXT chunks before checking the prefix.

### HTTPS fallback

If DNS produces no valid DID, fetch:

```text
https://<handle>/.well-known/atproto-did
```

Trim a successful text body and run the DID parser. Real-world resolution
requires HTTPS on the default port. HTTP is allowed only by explicit local
development policy for `.test` or `.localhost`.

When DNS and HTTPS disagree, the resolver follows the specification preference:
a valid unambiguous DNS result wins and the fallback is not fetched.

## Handle resolution policy

Some syntactically valid suffixes must not be resolved in normal mode, including
`.local`, `.arpa`, `.invalid`, and `.onion`. `.test` is allowed only in explicit
development mode. `Handle.parse` does not enforce this network policy because
Lexicon syntax validation and outbound-resolution policy are different layers.

## Resolve the DID document

### `did:plc`

```text
https://plc.directory/<did>
```

Parse the returned JSON as a DID document. The PLC directory base URL is
configuration so tests never require the public service.

### `did:web`

A hostname DID maps to:

```text
did:web:example.com
https://example.com/.well-known/did.json
```

Path-based `did:web` is outside the current atproto profile. A port-encoded
localhost DID such as `did:web:localhost%3A2583` is permitted only in development
mode. Invalid percent encoding becomes a typed resolution error, not an escaped
exception.

### Unknown method

`Did.parse("did:example:123")` succeeds as generic syntax; the resolver returns
`UnsupportedDidMethod("example")`. Preserve these states:

```text
malformed DID != valid but unsupported DID != supported and unresolved DID
```

That distinction is necessary for protocol evolution.

## Validate the DID document

### Document ID

The top-level `id` must exactly equal the requested DID. Receiving JSON from an
expected URL is not itself identity proof.

### Handle claims

Read `alsoKnownAs` entries that are exactly `at://<handle>` without extra path,
query, or fragment. A claim is not yet a verified handle. Resolve it in the
reverse direction and require the original DID.

### Repository signing key

Select a `verificationMethod` entry with:

- ID `#atproto` or `<DID>#atproto`;
- controller equal to the account DID;
- type `Multikey`;
- a `publicKeyMultibase` beginning with multibase `z`.

This chapter extracts the key string. Chapter 13 decodes base58btc,
multicodec, and P-256 and verifies signatures.

### PDS service

Select a service with:

- ID `#atproto_pds` or `<DID>#atproto_pds`;
- type `AtprotoPersonalDataServer`;
- an origin-level HTTPS endpoint;
- no user info, path prefix, query, or fragment.

Only local mode permits localhost HTTP. A syntactically valid URL does not prove
the PDS currently responds or still hosts the account.

## Why bidirectional verification matters

Assume an attacker controls this DNS value:

```text
attacker.example -> did:plc:victim
```

With only `handle -> DID`, the attacker can present their domain as a victim
alias. The victim's DID document does not claim `at://attacker.example`, so the
reverse check rejects the association.

The resolver's high-level algorithm is:

```text
resolve(handle):
  claimedDid = resolveHandle(handle)
  document = resolveDid(claimedDid)
  require document.id == claimedDid
  require document.alsoKnownAs contains at://handle
  extract PDS and signing key
```

If the caller supplies a DID directly, the account remains usable even when its
handle is broken. Reverse-resolve a claimed handle only for display; return no
verified handle when that check fails. The DID is primary identity.

## Deterministic tests

The algorithm depends on an interface, not a concrete network. Tests return
fixed DNS and URI responses and verify:

- DNS success and HTTPS fallback;
- conflicting DNS claims;
- document-ID and reverse-handle mismatch;
- PDS and signing-key extraction;
- explicit localhost policy;
- malformed percent encoding.

```console
$ nix develop --command sbt verify
```

## Cache and revalidation

Resolution is a timestamped observation. Handles, DID documents, PDS locations,
and keys can change. A production resolver caches DNS/HTTPS handle responses,
PLC documents, and `did:web` documents separately, with bounded positive and
negative TTLs.

Signature verification may require the key valid at a particular revision.
During migration or rotation, do not delete account data after one transient
resolution failure.

## SSRF and redirects

Identity resolution sends requests toward user-controlled hostnames. An
internet-facing resolver also needs:

- private, link-local, and cloud-metadata address blocking;
- DNS-rebinding defenses and connected-IP checks;
- scheme/host/IP validation after every redirect;
- strict connect/read/deadline and byte limits;
- a trusted recursive resolver and observable failure metrics.

`JdkIdentityNetwork` has timeouts and response limits but is not a complete SSRF
sandbox. It defines the boundary between this CLI learning implementation and a
production resolution service.

## Exercises

1. Add duplicate TXT records containing the same DID and explain why the result
   is not ambiguous.
2. Change the signing-key controller to a different DID and observe extraction
   failure.
3. Add `/prefix` to the PDS endpoint and explain why it cannot safely compose
   with the fixed XRPC path.
4. Resolve a DID whose claimed handle fails reverse resolution and prove the DID
   identity still succeeds.
5. Add `resolvedAt` and expiry to cache entries; define the expiry for a verified
   handle/DID pair.

## Specifications

- [Handle](https://atproto.com/specs/handle)
- [DID](https://atproto.com/specs/did)
- [Identity](https://atproto.com/guides/identity)
- [Accounts](https://atproto.com/specs/account)
