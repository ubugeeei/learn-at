# 用語集

| 用語 | この教材での意味 |
| --- | --- |
| account | 一つの DID と repository を持つ protocol 上の主体 |
| AppView | repository を index し application-specific な view を返す service |
| AT URI | `at://` で repository、collection、record を指す URI |
| blob | repository 外に保存し CID で record から参照する大きな binary |
| CAR | CID と block の集合を運ぶ Content Addressable aRchive |
| CID | codec と multihash を含む content identifier |
| collection | 同じ NSID を `$type` に持つ record の集合 |
| commit | repository の MST root、DID、revision、signature を持つ object |
| DAG-CBOR | IPLD data model を canonical CBOR で表す codec |
| DID | account の永続的・解決可能な decentralized identifier |
| DID document | service endpoint、公開鍵、handle などを宣言する document |
| firehose | repository event の WebSocket stream の通称 |
| handle | DNS name 形式の人間向け account name |
| Lexicon | AT Protocol の schema definition language |
| MST | record path から CID への deterministic Merkle Search Tree |
| NSID | reverse-domain 形式の namespaced identifier |
| PDS | account と repository を host する Personal Data Server |
| record | repository に保存される typed DAG-CBOR object |
| record key / rkey | collection 内で record を識別する文字列 |
| Relay | 複数 PDS の event stream を集約・再配信する service |
| revision / rev | repository commit ごとに単調増加する TID |
| TID | 時刻順に sort 可能な 13 文字の identifier |
| XRPC | Lexicon schema に基づく HTTP API 規約 |

