# 02: Nix で学習環境を作る

## この章のゴール

OS に JDK、Scala、sbt を個別インストールせず、この repository が固定した同じ環境で compile と test を実行します。

## なぜ Nix を使うのか

Scala source だけを固定しても、JDK と build tool が人ごとに違えば再現できません。この教材では次の二段階で固定します。

- `flake.lock`: Nixpkgs revision、JDK 21、sbt package
- `project/build.properties` と `build.sbt`: sbt と Scala 3 の version

sbt が Scala compiler を取得するため、global な `scala` command は使いません。

## 環境に入る

flake を利用できる Nix が必要です。repository root で次を実行します。

```console
$ nix develop
$ java -version
$ sbt --version
$ sbt verify
```

一回だけ command を実行する場合は shell に入らなくても構いません。

```console
$ nix develop --command sbt verify
```

direnv と nix-direnv を既に使っている場合は、同梱の `.envrc` を許可できます。

```console
$ direnv allow
```

direnv は任意です。教材の command は常に `nix develop --command ...` でも実行できます。

## project を読む

```text
.
├── flake.nix                 JDK と sbt を含む開発 shell
├── flake.lock                Nixpkgs の固定
├── build.sbt                 Scala version、compiler option、task
├── project/build.properties  sbt version
├── src/main/scala            client / PDS / protocol 実装
├── src/test/scala            dependency-free test runner
└── docs                      通読するハンズオン
```

この教材は test library を追加せず、`learnat.tests.AllTests` を `verify` task から実行します。失敗した assertion は process の exit code を非ゼロにします。CI も同じ `nix develop --command sbt verify` を実行します。

## 最初の観察

`src/main/scala/learnat/Main.scala` の help message を次で表示します。

```console
$ nix develop --command sbt run
```

次に compiler が未使用 import を拒否することを確かめます。

1. `Main.scala` に `import java.time.Instant` を追加する。
2. `sbt verify` を実行して `-Wunused:all` の error を読む。
3. import を元に戻し、再度成功させる。

小さな warning を error にするのは、教材中の code と説明が drift するのを早く見つけるためです。

## トラブルシュート

### `experimental Nix feature 'flakes' is disabled`

使用中の Nix で `nix-command` と `flakes` を有効にしてください。組織管理の Nix では設定を勝手に変更せず、管理者の手順を優先します。

### flake に追加した file が見えない

Nix は Git repository 内の flake を評価するとき、Git に認識されている file を source として扱います。新規 file を参照する変更では `git status` を確認してください。

### dependency download が失敗する

この project の application dependency はゼロですが、初回の sbt 実行は Scala compiler artifact を取得します。offline 実行は一度取得した後に行ってください。

