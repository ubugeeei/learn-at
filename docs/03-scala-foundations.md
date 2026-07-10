# 03: Express protocol invariants with Scala 3

## Goal

Learn enough Scala to read the remaining implementation. This is not a language
tour; each feature is introduced because it helps represent protocol rules.

## Values and types

Use `val` for an immutable value and `var` for a variable:

```scala
val method = "com.atproto.repo.getRecord"
var requestCount = 0
```

This project defaults to `val`. If network input changes after validation, the
used value may differ from the validated value. Mutation is confined to narrow
boundaries such as parser cursors and server state.

A type follows `:`:

```scala
val port: Int = 2583
val did: String = "did:web:localhost"
```

The annotation may be omitted when the compiler can infer it.

## Methods and objects

`def` introduces a method. Scala 3 indentation defines the block:

```scala
def xrpcPath(nsid: String): String =
  s"/xrpc/$nsid"
```

An `object` is a single value in the process. It is useful for entry points and
for companion constructors associated with a class:

```scala
object Main:
  def main(args: Array[String]): Unit =
    println("learn-at")
```

`Unit` means there is no meaningful return value.

## Case classes

A `case class` is a good representation for immutable data:

```scala
final case class XrpcError(error: String, message: Option[String])
```

`final` prevents a subclass from changing its meaning. A case class supplies a
constructor, value equality, `toString`, and pattern matching.

Using `String` everywhere lets a caller accidentally pass a handle or CID to a
method that requires a DID. Later chapters return different validated types
from different parsers.

## Enums and pattern matching

Use an `enum` when the possible shapes are finite. JSON has six:

```scala
enum Json:
  case Null
  case Bool(value: Boolean)
  case Num(value: BigDecimal)
  case Str(value: String)
  case Arr(value: Vector[Json])
  case Obj(fields: Vector[(String, Json)])
```

`match` branches on shape:

```scala
def kind(value: Json): String = value match
  case Json.Null    => "null"
  case Json.Bool(_) => "boolean"
  case Json.Num(_)  => "number"
  case Json.Str(_)  => "string"
  case Json.Arr(_)  => "array"
  case Json.Obj(_)  => "object"
```

The compiler can report an omitted case. Enumerating protocol states is safer
than representing unknown input with `null` and casts.

## Option: a value may be absent

`Option[A]` is either `Some(value)` or `None`:

```scala
val cursor: Option[String] = None

cursor match
  case Some(value) => println(s"resume from $value")
  case None        => println("start from the beginning")
```

Unlike Java `null`, the type forces callers to handle absence. Examples include
optional Lexicon fields, pagination cursors, and previous commits.

## Either: success or an explained failure

Network input is expected to be invalid sometimes. `Either[E, A]` is
`Left(error)` or `Right(value)`:

```scala
def parsePort(input: String): Either[String, Int] =
  input.toIntOption.toRight(s"invalid port: $input")
```

Compose several parsers with `flatMap` or `for`:

```scala
val result: Either[String, String] =
  for
    port <- parsePort("2583")
    url = s"http://localhost:$port"
  yield url
```

A `Left` stops the remaining steps and propagates the error. Unlike an
unchecked exception, failure is visible in the method signature.

## Vector and Map

`Vector[A]` is an ordered immutable collection used for JSON arrays, CAR block
sequences, and MST entries.

`Map[K, V]` is a key/value collection. The JSON codec instead keeps objects as
`Vector[(String, Json)]` so input order remains observable and duplicate keys
can be rejected explicitly during parsing.

## Effects at the boundary

HTTP, the filesystem, clocks, and secure randomness are effects: the same call
may produce a different result. JSON rendering and CID calculation are pure:
the same input produces the same output.

```text
effectful boundary: HTTP -> bytes
pure core:          bytes -> JSON -> validated value
```

A larger pure core makes tests independent of networks and clocks. The client
and PDS parse effectful input immediately and pass only validated types inward.

## Test runner

The project uses no external test library. Pass a by-name block to
`TestKit.test`:

```scala
test("description") {
  equal(actual, expected)
}
```

The body type is `=> Unit`, so it is evaluated inside the runner's `try/catch`,
which can print the failing test name.

## Exercises

1. Define `enum HttpMethod` with only `Get` and `Post`.
2. Render it as a string with pattern matching.
3. Write `parseHttpMethod` as `Either[String, HttpMethod]`.
4. Add `Delete` and let the compiler find the now-incomplete match.

Run everything with:

```console
$ nix develop --command sbt verify
```

The design objective is not terse Scala. It is making invalid protocol states
difficult or impossible to represent.
