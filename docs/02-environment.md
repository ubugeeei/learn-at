# 02: Build the environment with Nix

## Goal

Compile and test in the repository-pinned environment without installing the
JDK, Scala, or sbt independently on the host OS.

## Why Nix

Pinning only Scala source is not reproducible when developers use different
JDKs and build tools. This guide pins two layers:

- `flake.lock`: the Nixpkgs revision, JDK 21, and sbt package;
- `project/build.properties` and `build.sbt`: sbt and Scala 3 versions.

sbt obtains the Scala compiler, so the project does not use a global `scala`
command.

## Enter the environment

Install a Nix version with flakes, then run from the repository root:

```console
$ nix develop
$ java -version
$ sbt --version
$ sbt verify
```

For one command, entering an interactive shell is unnecessary:

```console
$ nix develop --command sbt verify
```

If you already use direnv and nix-direnv, allow the included `.envrc`:

```console
$ direnv allow
```

direnv is optional. Every guide command also works through
`nix develop --command ...`.

## Repository layout

```text
.
├── flake.nix                 development shell with JDK and sbt
├── flake.lock                pinned Nixpkgs input
├── build.sbt                 Scala version, compiler options, tasks
├── project/build.properties  sbt version
├── src/main/scala            client, PDS, and protocol implementation
├── src/test/scala            dependency-free test runner
└── docs                      sequential hands-on guide
```

The project adds no test framework. The `verify` task runs
`learnat.tests.AllTests`; a failed assertion produces a non-zero process exit.
CI runs the same `nix develop --command sbt verify` command.

## First observation

Display `Main.scala` help:

```console
$ nix develop --command sbt run
```

Then prove that the compiler rejects unused imports:

1. Add `import java.time.Instant` to `Main.scala`.
2. Run `sbt verify` and inspect the `-Wunused:all` error.
3. Remove the import and make the suite pass again.

Warnings are errors so stale code and stale explanations are detected early.

## Troubleshooting

### `experimental Nix feature 'flakes' is disabled`

Enable `nix-command` and `flakes` for your Nix installation. On an
organization-managed installation, follow the administrator's procedure rather
than changing global settings yourself.

### A new file is invisible to the flake

When evaluating a flake inside a Git repository, Nix uses files recognized by
Git as source input. Check `git status` whenever a flake change references a new
file.

### Dependency download fails

Application dependencies are zero, but the first sbt run still downloads Scala
compiler artifacts. Perform offline work only after that initial fetch.
