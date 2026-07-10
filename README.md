# learn-at

AT Protocol を、Scala 3 でクライアントと Personal Data Server (PDS) の両方を実装しながら学ぶハンズオンです。

このリポジトリは完成品の SDK を先に見せる教材ではありません。文字列の検証、JSON、HTTP、DID 解決、DAG-CBOR、CID、CAR、Merkle Search Tree、署名付き repository、同期、認証を、依存関係の順番どおりに組み立てます。各章には次のものを置きます。

- なぜその仕組みが必要なのか
- 仕様上の不変条件
- Scala 3 による最小実装
- 動かして観察する手順
- 壊して確かめる演習
- 本番運用へ進むときの差分

## まず動かす

環境構築には Nix flake を使います。JDK、Scala、sbt を個別にインストールする必要はありません。アプリケーションの実行時依存ライブラリはありません。

```console
$ nix develop --command sbt verify
$ nix develop --command sbt run
```

Run the local client/server exercise:

```console
$ LEARN_AT_PASSWORD=local-secret nix develop --command sbt "runMain learnat.Main pds 2583"
$ LEARN_AT_PASSWORD=local-secret nix develop --command sbt \
    'runMain learnat.Main client post http://localhost:2583 alice.test com.example.note "hello"'
```

The local PDS persists its development key, records, and last revision under `data/local-pds` by default. Use a fixed port across restarts because it is part of the localhost `did:web` identifier.

入口は [学習ロードマップ](docs/00-learning-path.md) です。環境の詳細は [Nix で学習環境を作る](docs/02-environment.md)、用語に詰まったら [用語集](docs/glossary.md) を参照してください。

## 到達点

最終的には次を自力で説明・実装・デバッグできることを目標にします。

- handle、DID、DID document、PDS の関係を辿る
- Lexicon を読み、XRPC の request/response を組み立てる
- 任意の PDS に接続する CLI クライアントを使う
- レコードを DAG-CBOR にし、CID で内容を検証する
- repository の MST と署名付き commit を生成・検証する
- CAR による export/import と firehose の継続同期を行う
- ローカル PDS にアカウントを作り、別クライアントから読み書きする
- OAuth の discovery、PKCE、PAR、DPoP と legacy session の違いを説明する
- 公開運用に必要な永続化、rate limit、鍵管理、moderation、backup を判断する

「教材として実装済み」と「プロダクション PDS に必要」を混同しないよう、各章と [実装スコープ](docs/architecture/scope.md) に境界を明記します。

## 仕様の基準

プロトコルの正本は [AT Protocol specifications](https://atproto.com/specs/atp) です。本教材は 2026-07-10 時点の仕様と、公式参照実装 `bluesky-social/atproto` の commit `a9ff2da83b93e5211f45667e652f8a928f4dce29` を照合して作成しています。仕様と教材が食い違う場合は仕様を優先してください。

Scala は 3.8.4、ビルド定義だけに sbt 1.10.11 を使用します。
