package learnat.tests

import java.net.URI
import java.nio.charset.StandardCharsets
import learnat.crypto.P256KeyPair
import learnat.json.Json
import learnat.oauth.AuthorizationCallback
import learnat.oauth.AuthorizationRequest
import learnat.oauth.DpopNonceManager
import learnat.oauth.DpopProof
import learnat.oauth.DpopReplayCache
import learnat.oauth.DpopVerifier
import learnat.oauth.OAuthMetadata
import learnat.oauth.OAuthState
import learnat.oauth.OAuthTokenResponse
import learnat.oauth.P256Jwk
import learnat.oauth.PkcePair
import learnat.oauth.PushedAuthorizationResponse
import learnat.oauth.TokenRequest
import learnat.syntax.AtIdentifier
import learnat.tests.TestKit.*

object OAuthTests:
  private val now = 1_750_000_000L

  def run(): Unit =
    println("OAuth and DPoP")

    test("matches the RFC 7636 S256 PKCE example") {
      val pair = PkcePair.fromVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk").toOption.get
      equal(pair.challenge, "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
      isLeft(PkcePair.fromVerifier("too-short"))
      equal(PkcePair.generate().verifier.length, 43)
    }

    test("generates opaque state instead of serializing session data") {
      val first = OAuthState.generate().value
      val second = OAuthState.generate().value
      equal(first.length, 43)
      assert(first != second)
      assert(first.forall(character => character.isLetterOrDigit || character == '-' || character == '_'))
    }

    test("binds protected-resource metadata to the fetched PDS origin") {
      val json = Json.obj(
        "resource" -> Json.Str("https://pds.example"),
        "authorization_servers" -> Json.arr(Json.Str("https://auth.example"))
      )
      val metadata = OAuthMetadata.parseProtectedResource(json, URI("https://pds.example")).toOption.get
      equal(metadata.authorizationServer, URI("https://auth.example"))
      isLeft(OAuthMetadata.parseProtectedResource(json, URI("https://attacker.example")))
      isLeft(OAuthMetadata.parseProtectedResource(json, URI("http://localhost:2583")))
      assert(OAuthMetadata.parseProtectedResource(
        Json.obj(
          "resource" -> Json.Str("http://localhost:2583"),
          "authorization_servers" -> Json.arr(Json.Str("http://localhost:2584"))
        ),
        URI("http://localhost:2583"),
        allowLoopbackDevelopment = true
      ).isRight)
    }

    test("requires every atproto authorization-server capability") {
      val metadata = OAuthMetadata.parseAuthorizationServer(serverMetadata, URI("https://auth.example"))
      assert(metadata.isRight, metadata)
      equal(metadata.map(_.issuer), Right(URI("https://auth.example")))

      val noPar = replaceField(serverMetadata, "require_pushed_authorization_requests", Json.Bool(false))
      isLeft(OAuthMetadata.parseAuthorizationServer(noPar, URI("https://auth.example")))
      isLeft(OAuthMetadata.parseAuthorizationServer(serverMetadata, URI("https://other.example")))
    }

    test("parses DPoP token responses and binds the account subject") {
      val expected = learnat.syntax.Did.parse("did:plc:expected").toOption.get
      val json = Json.obj(
        "sub" -> Json.Str(expected.value),
        "access_token" -> Json.Str("opaque-access"),
        "refresh_token" -> Json.Str("opaque-refresh"),
        "token_type" -> Json.Str("DPoP"),
        "expires_in" -> Json.Num(300),
        "scope" -> Json.Str("atproto transition:generic")
      )
      val response = OAuthTokenResponse.parse(json, Some(expected)).toOption.get
      assert(response.scopes.contains("atproto"))
      val other = learnat.syntax.Did.parse("did:plc:other").toOption.get
      isLeft(OAuthTokenResponse.parse(json, Some(other)))
      isLeft(OAuthTokenResponse.parse(replaceField(json, "scope", Json.Str("transition:generic")), Some(expected)))
    }

    test("builds a complete pushed authorization request") {
      val clientId = URI("https://client.example/oauth-client-metadata.json")
      val redirect = URI("https://client.example/callback")
      val state = OAuthState.generate()
      val pkce = PkcePair.fromVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk").toOption.get
      val login = AtIdentifier.parse("alice.example.com").toOption.get
      val request = AuthorizationRequest.create(
        clientId,
        redirect,
        Vector("atproto", "transition:generic"),
        state,
        pkce,
        Some(login)
      ).toOption.get
      val body = String(request.parBody, StandardCharsets.UTF_8)
      assert(body.contains("response_type=code"))
      assert(body.contains("code_challenge_method=S256"))
      assert(body.contains("scope=atproto+transition%3Ageneric"))
      assert(body.contains("login_hint=alice.example.com"))
      isLeft(AuthorizationRequest.create(clientId, redirect, Vector("transition:generic")))
      isLeft(AuthorizationRequest.create(URI("https://client.example:444/metadata"), redirect, Vector("atproto")))
    }

    test("reduces the browser redirect to client_id and PAR request_uri") {
      val response = PushedAuthorizationResponse.parse(
        Json.obj("request_uri" -> Json.Str("urn:ietf:params:oauth:request_uri:abc"), "expires_in" -> Json.Num(90))
      ).toOption.get
      val metadata = OAuthMetadata.parseAuthorizationServer(serverMetadata, URI("https://auth.example")).toOption.get
      val browser = response.browserUri(metadata, URI("https://client.example/metadata"))
      assert(browser.toString.startsWith("https://auth.example/oauth/authorize?client_id="))
      assert(browser.toString.contains("request_uri=urn%3Aietf%3Aparams%3Aoauth%3Arequest_uri%3Aabc"))
    }

    test("verifies callback state and issuer before accepting a code") {
      val state = OAuthState.generate()
      val issuer = URI("https://auth.example")
      val callback = URI(s"https://client.example/callback?code=abc&state=${state.value}&iss=https%3A%2F%2Fauth.example")
      equal(AuthorizationCallback.parse(callback, state, issuer), Right(AuthorizationCallback.Authorized("abc")))
      val wrongIssuer = URI(s"https://client.example/callback?code=abc&state=${state.value}&iss=https%3A%2F%2Fevil.example")
      isLeft(AuthorizationCallback.parse(wrongIssuer, state, issuer))
      val duplicate = URI(s"https://client.example/callback?code=abc&state=${state.value}&state=${state.value}&iss=https%3A%2F%2Fauth.example")
      isLeft(AuthorizationCallback.parse(duplicate, state, issuer))
    }

    test("completes PKCE in the code token request") {
      val request = AuthorizationRequest.create(
        URI("https://client.example/metadata"),
        URI("https://client.example/callback"),
        Vector("atproto")
      ).toOption.get
      val body = String(TokenRequest.authorizationCode("code", request).toOption.get.body, StandardCharsets.UTF_8)
      assert(body.contains("grant_type=authorization_code"))
      assert(body.contains(s"code_verifier=${request.pkce.verifier}"))
      val refresh = String(TokenRequest.refresh("single-use", request.clientId).toOption.get.body, StandardCharsets.UTF_8)
      assert(refresh.contains("grant_type=refresh_token"))
    }

    test("round trips public P-256 JWK coordinates and thumbprints") {
      val key = P256KeyPair.generate().toOption.get.publicKey
      val jwk = P256Jwk(key)
      val parsed = P256Jwk.parse(jwk.json).toOption.get
      equal(parsed.publicKey.multikey, key.multikey)
      equal(parsed.thumbprint, jwk.thumbprint)
      val privateJwk = jwk.json match
        case Json.Obj(fields) => Json.Obj(fields :+ ("d" -> Json.Str("secret")))
        case other => other
      isLeft(P256Jwk.parse(privateJwk))
    }

    test("creates and verifies a request-bound DPoP proof") {
      val key = P256KeyPair.generate().toOption.get
      val target = URI("https://pds.example/xrpc/com.example.write?ignored=for-htu")
      val proof = DpopProof.create(key, "post", target, Some("server-nonce"), Some("opaque-token"), now).toOption.get
      val verified = DpopVerifier.verify(
        proof,
        "POST",
        target,
        Some("server-nonce"),
        Some("opaque-token"),
        now
      ).toOption.get
      equal(verified.keyThumbprint, P256Jwk(key.publicKey).thumbprint)
      equal(verified.nonce, Some("server-nonce"))

      val initial = DpopProof.create(key, "POST", URI("https://auth.example/oauth/par"), None, None, now).toOption.get
      assert(DpopVerifier.verify(initial, "POST", URI("https://auth.example/oauth/par"), None, None, now).isRight)
    }

    test("rejects altered DPoP request, nonce, token, time, and signature") {
      val key = P256KeyPair.generate().toOption.get
      val target = URI("https://auth.example/oauth/token")
      val proof = DpopProof.create(key, "POST", target, Some("nonce"), None, now).toOption.get
      isLeft(DpopVerifier.verify(proof, "GET", target, Some("nonce"), None, now))
      isLeft(DpopVerifier.verify(proof, "POST", target, Some("different"), None, now))
      isLeft(DpopVerifier.verify(proof, "POST", URI("https://auth.example/oauth/par"), Some("nonce"), None, now))
      isLeft(DpopVerifier.verify(proof, "POST", target, Some("nonce"), Some("token"), now))
      isLeft(DpopVerifier.verify(proof, "POST", target, Some("nonce"), None, now + 1000))

      val segments = proof.split("\\.", -1)
      val changed = if segments(2).head == 'A' then 'B' else 'A'
      val tampered = s"${segments(0)}.${segments(1)}.$changed${segments(2).drop(1)}"
      isLeft(DpopVerifier.verify(tampered, "POST", target, Some("nonce"), None, now))
    }

    test("rejects a replay after all proof checks succeed") {
      val key = P256KeyPair.generate().toOption.get
      val target = URI("https://pds.example/xrpc/com.example.read")
      val proof = DpopProof.create(key, "GET", target, Some("nonce"), Some("token"), now).toOption.get
      val cache = DpopReplayCache(10)
      assert(DpopVerifier.verify(proof, "GET", target, Some("nonce"), Some("token"), now, Some(cache)).isRight)
      isLeft(DpopVerifier.verify(proof, "GET", target, Some("nonce"), Some("token"), now, Some(cache)))
    }

    test("rotates server nonces while briefly accepting the previous value") {
      val manager = DpopNonceManager.testing(Iterator("one", "two", "three"), now, 10, 3).toOption.get
      equal(manager.issue(now), "one")
      assert(manager.accepts("one", now + 9))
      equal(manager.issue(now + 10), "two")
      assert(manager.accepts("one", now + 12))
      assert(!manager.accepts("one", now + 13))
      assert(manager.accepts("two", now + 13))
      isLeft(DpopNonceManager.testing(Iterator("bad"), now, 301, 0))
    }

  private def serverMetadata: Json = Json.obj(
    "issuer" -> Json.Str("https://auth.example"),
    "authorization_endpoint" -> Json.Str("https://auth.example/oauth/authorize"),
    "token_endpoint" -> Json.Str("https://auth.example/oauth/token"),
    "pushed_authorization_request_endpoint" -> Json.Str("https://auth.example/oauth/par"),
    "response_types_supported" -> Json.arr(Json.Str("code")),
    "grant_types_supported" -> Json.arr(Json.Str("authorization_code"), Json.Str("refresh_token")),
    "code_challenge_methods_supported" -> Json.arr(Json.Str("S256")),
    "token_endpoint_auth_methods_supported" -> Json.arr(Json.Str("none"), Json.Str("private_key_jwt")),
    "token_endpoint_auth_signing_alg_values_supported" -> Json.arr(Json.Str("ES256")),
    "scopes_supported" -> Json.arr(Json.Str("atproto"), Json.Str("transition:generic")),
    "authorization_response_iss_parameter_supported" -> Json.Bool(true),
    "require_pushed_authorization_requests" -> Json.Bool(true),
    "dpop_signing_alg_values_supported" -> Json.arr(Json.Str("ES256")),
    "client_id_metadata_document_supported" -> Json.Bool(true)
  )

  private def replaceField(json: Json, name: String, value: Json): Json = json match
    case Json.Obj(fields) => Json.Obj(fields.map { case (key, old) => if key == name then key -> value else key -> old })
    case other => other
