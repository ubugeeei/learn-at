# 03: Scala 3 で protocol の不変条件を表す

## この章のゴール

Scala を初めて使う人が、以後の実装に現れる syntax を読めるようにします。言語機能を網羅せず、protocol implementation で使う理由と一緒に覚えます。

## 値と型

Scala では変更しない値を `val`、変更する変数を `var` で定義します。

```scala
val method = "com.atproto.repo.getRecord"
var requestCount = 0
```

この教材では default を `val` にします。network input を parse した後に値が変わると、検証した値と使用した値が別になる危険があるためです。`var` は parser cursor や server state のような狭い境界に閉じ込めます。

型は `:` の後ろです。

```scala
val port: Int = 2583
val did: String = "did:web:localhost"
```

compiler が推論できるときは省略できます。

## method と object

`def` は method です。Scala 3 では indentation が block を表します。

```scala
def xrpcPath(nsid: String): String =
  s"/xrpc/$nsid"
```

`object` は process 内に一つだけ存在する値です。entry point や、関連する constructor をまとめる companion object に使います。

```scala
object Main:
  def main(args: Array[String]): Unit =
    println("learn-at")
```

`Unit` は意味のある戻り値がないことを表します。

## case class

`case class` は immutable data を表すのに向いています。

```scala
final case class XrpcError(error: String, message: Option[String])
```

`final` は継承して別の意味に変えられないことを表します。`case class` は constructor、値による等価比較、`toString`、pattern matching を自動で提供します。

ただの `String` を everywhere で使うと、DID を受け取る method に handle や CID を誤って渡せます。後の章では validation 済みの値を別の型にします。

## enum と pattern matching

取り得る形が有限なら `enum` を使います。JSON は六種類です。

```scala
enum Json:
  case Null
  case Bool(value: Boolean)
  case Num(value: BigDecimal)
  case Str(value: String)
  case Arr(value: Vector[Json])
  case Obj(fields: Vector[(String, Json)])
```

値の形によって処理を分けるのが `match` です。

```scala
def kind(value: Json): String = value match
  case Json.Null    => "null"
  case Json.Bool(_) => "boolean"
  case Json.Num(_)  => "number"
  case Json.Str(_)  => "string"
  case Json.Arr(_)  => "array"
  case Json.Obj(_)  => "object"
```

全 case を書き忘れると compiler が指摘できます。unknown input を `null` や cast で処理するより、protocol state を列挙するほうが安全です。

## Option: 値がない可能性

`Option[A]` は `Some(value)` または `None` です。

```scala
val cursor: Option[String] = None
```

Java の `null` と違い、使う側が「ない場合」を処理しなければなりません。Lexicon の optional field、query cursor、previous commit などに使います。

```scala
cursor match
  case Some(value) => println(s"resume from $value")
  case None        => println("start from the beginning")
```

## Either: 成功または説明可能な失敗

network input は失敗して当然です。`Either[E, A]` は `Left(error)` または `Right(value)` で表します。

```scala
def parsePort(input: String): Either[String, Int] =
  input.toIntOption.toRight(s"invalid port: $input")
```

複数の parse を順番に行うときは `flatMap` または `for` を使います。

```scala
val result: Either[String, String] =
  for
    port <- parsePort("2583")
    url = s"http://localhost:$port"
  yield url
```

途中が `Left` なら残りを実行せず、その error を返します。例外と違い、method signature だけで失敗を見つけられます。

## Vector と Map

`Vector[A]` は順序を持つ immutable collection です。JSON array、CAR block の列、MST entry に使います。

`Map[K, V]` は key/value collection です。ただし JSON object codec では `Vector[(String, Json)]` を使っています。理由は入力順を観察でき、重複 key を parse 時に明示的に拒否できるためです。

## boundary で effect を扱う

HTTP、filesystem、clock、secure random は同じ引数でも結果が変わる effect です。一方、JSON render や CID calculation は同じ入力なら同じ結果を返す pure function です。

```text
effectful boundary: HTTP -> bytes
pure core:          bytes -> JSON -> validated value
```

pure core を増やすと test が network や時刻に依存しません。client と PDS の設計では、effectful boundary から得た値をすぐ parse し、検証済みの型だけを core に渡します。

## test runner

外部 test library を使わず、`TestKit.test` に code block を渡します。

```scala
test("description") {
  equal(actual, expected)
}
```

body は `=> Unit`、つまり呼び出し前には評価しない引数です。runner が `try/catch` の内側で実行し、失敗した test 名を表示できます。

## 演習

1. `enum HttpMethod` を作り、`Get` と `Post` だけを表す。
2. `HttpMethod` から文字列を返す `render` を pattern matching で書く。
3. `parseHttpMethod` を `Either[String, HttpMethod]` で書く。
4. `Delete` を enum に追加し、未対応の pattern match を compiler に見つけさせる。

実行は次です。

```console
$ nix develop --command sbt verify
```

この章の目的は Scala の短い書き方ではありません。「無効な protocol state を型で表せないようにする」という設計方向を掴むことです。

