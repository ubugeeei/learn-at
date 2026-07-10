# 04: 依存ゼロの JSON codec

## この章のゴール

JSON text を検証済みの tree に変換し、tree を JSON text に戻します。同時に、parser が protocol の最初の security boundary であることを理解します。

実装は `src/main/scala/learnat/json/Json.scala`、test は `src/test/scala/learnat/tests/JsonTests.scala` にあります。

## なぜ最初に JSON なのか

AT Protocol では次が JSON です。

- XRPC の多くの input / output / error
- DID document
- Lexicon schema
- OAuth server metadata と client metadata

後で扱う repository block は DAG-CBOR ですが、data model の多くは JSON と対応します。JSON の `null / boolean / number / string / array / object` を明示的な型にすることが、以後の decoder の土台です。

## data model

```scala
enum Json:
  case Null
  case Bool(value: Boolean)
  case Num(value: BigDecimal)
  case Str(value: String)
  case Arr(value: Vector[Json])
  case Obj(fields: Vector[(String, Json)])
```

### 数値を Double にしない

`Double` は binary floating point なので、多くの十進小数や大きな整数を正確に表せません。JSON parser は syntax を失わず `BigDecimal` にします。Lexicon decoder が integer range を確認するときは `asLong` を使います。

### object を Map にしない

JSON object の key 順は意味を持ちませんが、入力を観察しやすくするため `Vector` で保持します。parser は同じ key が二回現れたら拒否します。

```json
{"did":"trusted","did":"attacker-controlled"}
```

重複 key を許すと、署名検証側は最初、business logic 側は最後を採用する、といった parser differential が起きます。一般 JSON の全実装が重複を禁止するわけではありませんが、この実装は untrusted protocol input に対する安全側の policy として拒否します。

## recursive descent parser

parser は現在位置 `offset` を一つ持ち、先頭文字で処理を分けます。

```text
n -> null
t -> true
f -> false
" -> string
[ -> array
{ -> object
- or digit -> number
```

array と object は内部の value を再帰的に parse します。document の parse 後は whitespace 以外が残っていないことを確認します。これがないと `true malicious-data` の先頭だけを成功として扱ってしまいます。

## string と Unicode

JSON escape は `\" \\ \/ \b \f \n \r \t \uXXXX` です。Unicode code point が UTF-16 の二つの code unit で表される場合、high surrogate と low surrogate の組を検証します。

```json
"Scala \uD83D\uDE80"
```

不完全な組、単独の low surrogate、不正な hex digit は error です。error は offset、line、column を保持します。

## resource limit

小さい入力でも、極端に深い array は call stack を消費します。巨大な input は memory を消費します。

```scala
Json.parse(input, Json.Limits(
  maxDepth = 128,
  maxInputChars = 2 * 1024 * 1024
))
```

HTTP layer は endpoint ごとの body limit も持つべきです。parser limit は最後の防御であり、server 全体の rate limit の代わりではありません。

## unsafe cast を外へ漏らさない

field access も `Either` です。

```scala
val did: Either[Json.AccessError, String] =
  document.field("did").flatMap(_.asString)
```

field がない、object ではない、string ではない、のいずれも明示的な `Left` になります。`asInstanceOf` や `null` は不要です。

optional field は別 method にします。

```scala
document.optionalField("cursor") // Either[AccessError, Option[Json]]
```

「object ではない」と「field がない」を同じ `None` にしない点が重要です。

## 実行して観察する

```console
$ nix develop --command sbt verify
```

JSON section では正常な六種類の value に加えて、次を test しています。

- surrogate pair の round trip
- duplicate key
- leading zero、不完全な fraction / exponent
- trailing input
- nesting limit
- typed field access

## 壊して確かめる

### 演習 1: trailing input check

`parseDocument` の `atEnd` check を一時的に外し、`true false` の test がどう変化するか確認してください。確認後は必ず元に戻します。

### 演習 2: duplicate key policy

`keys` set による check を外し、二つの `did` が tree に残ることを観察します。その tree を `field("did")` で読むとどちらが返るか、別の Map へ変換するとどうなるかを説明してください。

### 演習 3: depth

`maxDepth = 2` で `[[]]` と `[[[]]]` を parse し、depth の数え方を図にしてください。

## production library との差

自作 codec の目的は data model と boundary の理解です。一般用途の production system では、十分に fuzzing され、streaming、byte input、performance tuning を持つ library を選ぶ価値があります。

一方で library を導入しても次の判断は application 側に残ります。

- duplicate key policy
- maximum body size と nesting
- number range
- unknown field policy
- error を client にどこまで返すか

この参照実装は後で同じ tree を DAG-CBOR value へ変換するため、限定された codec として維持します。

