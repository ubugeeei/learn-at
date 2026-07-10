# Glossary

| Term | Meaning in this guide |
| --- | --- |
| account | protocol actor with one DID and repository |
| AppView | service that indexes repositories and returns application-specific views |
| AT URI | `at://` URI identifying a repository, collection, or record |
| blob | large binary stored outside the repository and referenced by CID |
| CAR | Content Addressable aRchive transporting CIDs and blocks |
| CID | content identifier containing codec and multihash information |
| collection | records sharing one NSID as `$type` |
| commit | object containing repository MST root, DID, revision, and signature |
| DAG-CBOR | canonical CBOR encoding of the IPLD data model |
| DID | durable, resolvable decentralized account identifier |
| DID document | document declaring services, public keys, and handle claims |
| firehose | common name for a repository-event WebSocket stream |
| handle | human-facing account name in DNS-name form |
| Lexicon | AT Protocol schema definition language |
| MST | deterministic Merkle Search Tree mapping record paths to CIDs |
| NSID | reverse-domain namespaced identifier |
| PDS | Personal Data Server hosting accounts and repositories |
| record | typed DAG-CBOR object stored in a repository |
| record key / rkey | string identifying a record inside one collection |
| Relay | service aggregating and redistributing event streams from PDS instances |
| revision / rev | monotonically increasing TID for a repository commit |
| TID | 13-character time-sortable identifier |
| XRPC | HTTP API convention described by Lexicon schemas |
