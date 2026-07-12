# 06: Implement XRPC as ordinary HTTP

## Goal

Translate Lexicon queries and procedures into HTTP requests while distinguishing
success, protocol errors, malformed responses, and transport failures. Build a
client core usable by both public and authenticated endpoints.

Implementation: `src/learnat/xrpc/Xrpc.scala`

## What XRPC defines

XRPC (Lexicon RPC) is AT Protocol's HTTP convention. Endpoint names are NSIDs:

```text
GET  /xrpc/com.atproto.repo.getRecord
POST /xrpc/com.atproto.repo.createRecord
```

| Lexicon type | HTTP | Input | Meaning |
| --- | --- | --- | --- |
| query | GET | URL query parameters | cacheable, does not change state |
| procedure | POST | request body | may change state |

Do not infer the verb from an English endpoint name. Read the Lexicon
definition's `type`.

## Separate transport from protocol

Instead of invoking JDK `HttpClient` in every API method, define one boundary:

```scala
trait HttpTransport:
  def send(request: HttpRequestData): Either[XrpcError, HttpResponseData]
```

Runtime code uses `JdkHttpTransport`; tests use a recording transport with fixed
responses. Without a network, tests can inspect the verb, URI, headers, exact
body bytes, status handling, and error decoding.

## Service URL invariant

XRPC always lives at top-level `/xrpc/`. Validate the service URL once:

```text
valid:   https://pds.example
valid:   http://localhost:2583
invalid: ftp://pds.example
invalid: https://pds.example/prefix
```

Query and fragment components are also forbidden. A DID-document URL is still
untrusted input and passes through the same check.

## Encode query parameters

Convert each value to UTF-8 and percent-encode bytes outside the RFC 3986
unreserved set:

```text
input: alice+test.example
wire:  alice%2Btest.example

input: café
wire:  caf%C3%A9
```

HTML form encoders may turn spaces into `+`; the URI builder deliberately uses
percent encoding. Parameters remain `Vector[(String, String)]`, not a Map,
because arrays can repeat the same parameter name.

## Query

```scala
val method = Nsid.parse("com.atproto.identity.resolveHandle").toOption.get

client.query(method, Vector("handle" -> "alice.example.com"))
```

```http
GET /xrpc/com.atproto.identity.resolveHandle?handle=alice.example.com HTTP/1.1
Host: pds.example
Accept: application/json
```

## JSON procedure

```scala
client.procedure(
  Nsid.parse("com.atproto.repo.createRecord").toOption.get,
  Json.obj(
    "repo" -> Json.Str("did:plc:example"),
    "collection" -> Json.Str("com.example.note")
  ),
  bearerToken = Some(token)
)
```

The body is UTF-8 JSON, `Content-Type` is `application/json`, and a legacy token
uses `Authorization: Bearer ...`. OAuth later uses DPoP-bound authorization.

## Binary bodies

Not every XRPC body is JSON:

- `com.atproto.sync.getRepo`: CAR response;
- `com.atproto.repo.importRepo`: CAR input;
- `com.atproto.repo.uploadBlob`: binary input;
- `com.atproto.sync.subscribeRepos`: WebSocket event stream.

`queryBytes` and `procedureBytes` return status, headers, and bytes before
parsing. The caller chooses a codec from the content type. WebSocket transport
is implemented separately in chapter 16.

## Responses and errors

Any 2xx status is successful. A JSON endpoint still fails with
`InvalidResponse` when its successful body is not JSON.

A standard non-2xx body is:

```json
{"error":"InvalidRequest","message":"repo is required"}
```

The client preserves important failure categories:

```scala
enum XrpcError:
  case InvalidService(...)
  case Transport(...)
  case ResponseTooLarge(...)
  case InvalidResponse(...)
  case Remote(status, error, message)
```

Retry policy belongs above this classification. Do not retry a DNS timeout and
an `InvalidRequest` identically. A higher layer may refresh on 401, back off on
429/5xx, and correct input on 400.

## Size limits

The client bounds response bodies. The current JDK transport checks after
receiving the body, so it is not a complete streaming defense. A production
client should use a limited `BodySubscriber`, endpoint-specific size limits,
and stricter timeout policy. Large CAR exports and small metadata documents
should not share one indiscriminate limit.

## Run and observe

```console
$ nix develop --command sbt verify
```

XRPC tests inspect `HttpRequestData` captured by the fake transport, so protocol
tests do not depend on public-server availability or rate limits.

## Exercises

1. Replace the query encoder with `java.net.URLEncoder`; compare spaces and plus
   signs.
2. Return a 400 body as if it were 2xx and explain how the caller loses failure
   semantics.
3. Test `queryBytes` with `Accept: application/vnd.ipld.car`.
4. Make the fake transport return `XrpcError.Transport` and render it
   differently from a remote protocol error.

## Remaining layers

- Lexicon-derived endpoint code generation;
- retry, exponential backoff, and rate-limit policy;
- DPoP proof and nonce retry;
- service-proxy header policy.

These should compose above the transport instead of becoming hidden transport
side effects.

## Specifications

- [HTTP API (XRPC)](https://atproto.com/specs/xrpc)
- [Lexicon](https://atproto.com/specs/lexicon)
