# 設計判断記録

## ADR-001: 一つの codebase を層で分離する

client と server を別 repository にせず、一つの Scala project 内で `syntax`、`json`、`identity`、`xrpc`、`ipld`、`repo`、`client`、`pds` package に分けます。

理由は、同じ codec や identifier 型を共有しつつ、依存方向を code review で確認できるためです。依存方向は次だけを許します。

```text
client ─┐
        ├─> xrpc -> identity -> syntax
pds ────┘          │
  │                 └-> json
  └-> repo -> ipld -> bytes
```

`repo` は HTTP を知りません。`identity` は repository storage を知りません。これにより、protocol の層を単独でテストできます。

## ADR-002: Either で untrusted input を扱う

外部入力の parse failure は通常の制御フローです。公開 parser は `Either[ProtocolError, A]` を返し、内部で検証済みの型だけを受け渡します。programmer error と回復不能な I/O failure 以外に例外を乱用しません。

## ADR-003: mutable state を境界へ閉じ込める

codec、identifier、MST node は immutable にします。clock、random、network、filesystem、account session の mutation は interface の背後へ置き、test で差し替えます。

## ADR-004: 暗号 primitive は自作しない

SHA-256、ECDSA、PBKDF2、secure random は JCA を使います。自作するのは protocol が要求する canonical input、multicodec key、DER/raw signature 変換、low-S normalization です。

## ADR-005: repository は record map から再構築できる実装から始める

最初の MST は key/value 集合から deterministic tree を構築します。これは insertion-order independence を明確にし、correctness oracle になります。その後、同じ CID を返す incremental update を追加します。少数 account の教材 PDS は最初の方式でも正しく動作します。

## ADR-006: auth の学習順と推奨方式を分ける

legacy `createSession` は一つの POST と bearer token で request authentication を観察しやすいため先に実装します。しかし新しい user-facing client の推奨方式とはしません。OAuth profile の discovery、PKCE、PAR、DPoP を最終 client の基準とします。

