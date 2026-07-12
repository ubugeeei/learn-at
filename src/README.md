# Source tour

This directory is the executable half of the book. Do not read it alphabetically.
Follow the dependency chain below and keep the matching chapter open.

| Step | Read | Then run or inspect | Why it comes here |
| --- | --- | --- | --- |
| 1 | `learnat/json/Json.scala` | `Json.test.scala` | Every public API crosses JSON first. |
| 2 | `learnat/syntax/Identifiers.scala` | its two adjacent tests | Parse identities before using them as URLs or keys. |
| 3 | `learnat/xrpc/Xrpc.scala` | `Xrpc.test.scala` | XRPC is HTTP with naming and error conventions. |
| 4 | `learnat/identity/Identity.scala` | `Identity.test.scala` | A handle must resolve to a mutually verified DID. |
| 5 | `learnat/lexicon/` | `Lexicon.test.scala` | Lexicons validate the values transported by XRPC. |
| 6 | `learnat/ipld/` | each adjacent test | Deterministic bytes make content addressing possible. |
| 7 | `learnat/repo/Mst.scala` | `Mst.test.scala` | The tree commits record paths to CIDs. |
| 8 | `learnat/crypto/P256.scala` and `repo/Repository.scala` | their tests | A signed commit authenticates the tree root. |
| 9 | `learnat/pds/` and `learnat/client/` | PDS tests | The earlier pieces become a client/server write path. |
| 10 | `learnat/sync/` and `learnat/oauth/` | adjacent tests | Synchronization and delegated authorization add state machines. |

## How a record moves

For a concrete trace, follow one `createRecord` request:

```text
LearnAt.scala
  -> ClientMain.scala
  -> AtpClient.scala
  -> Xrpc.scala
  -> LocalPds.scala
  -> LexiconValidator.scala
  -> Repository.scala
  -> DagCbor / CID / MST / P-256
  -> LocalPdsStore.scala
```

Each arrow is an intentional boundary. HTTP parsing does not decide repository
semantics; schema validation does not write storage; storage does not decide
identity. The matching tests exercise these boundaries independently before
the PDS end-to-end test composes them.

## Why tests are beside the implementation

The book treats a test as the next paragraph of an implementation. A successful
case shows the smallest usable contract. The neighboring corruption or invalid
input case explains the invariant that the implementation protects. The build
uses filenames to retain the normal application/test classpath separation:

- `*.scala`, except `*.test.scala`: application code;
- `*.test.scala`: test code, compiled with application code on its classpath;
- `src/test/resources`: larger upstream interoperability fixtures.

Run the complete executable narrative with `nix develop --command sbt verify`.
Start the program with `nix develop --command sbt run`; its primary entry point
is `learnat/LearnAt.scala`.
