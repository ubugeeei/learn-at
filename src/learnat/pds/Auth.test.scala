package learnat.tests

import learnat.pds.PasswordHash
import learnat.pds.SessionStore
import learnat.syntax.Did
import learnat.tests.TestKit.*

object AuthTests:
  private val did = Did.parse("did:web:localhost%3A2583").toOption.get

  def run(): Unit =
    println("Local PDS authentication")

    test("hashes, verifies, and restores passwords") {
      val password = "correct horse battery staple".toCharArray
      val hash = PasswordHash.create(password).toOption.get
      assert(hash.verify(password))
      assert(!hash.verify("wrong".toCharArray))
      val restored = PasswordHash.parse(hash.encoded).toOption.get
      assert(restored.verify(password))
      java.util.Arrays.fill(password, '\u0000')
    }

    test("rejects weak or malformed password hash parameters") {
      isLeft(PasswordHash.create("password".toCharArray, iterations = 10))
      isLeft(PasswordHash.parse("not-a-password-hash"))
      isLeft(PasswordHash.parse("pbkdf2-sha256$100000$%%%$%%%"))
    }

    test("issues scoped access and refresh tokens") {
      val store = SessionStore.testing(Array.fill[Byte](32)(7), () => 1000L).toOption.get
      val tokens = store.issue(did)
      equal(store.verifyAccess(tokens.accessJwt), Right(did))
      isLeft(store.verifyAccess(tokens.refreshJwt))
    }

    test("rotates and revokes refresh tokens") {
      val store = SessionStore.testing(Array.fill[Byte](32)(8), () => 1000L).toOption.get
      val original = store.issue(did)
      val rotated = store.refresh(original.refreshJwt)
      assert(rotated.isRight)
      isLeft(store.refresh(original.refreshJwt))
      assert(store.verifyAccess(rotated.toOption.get.accessJwt).isRight)
    }

    test("rejects expired, tampered, and explicitly revoked access tokens") {
      var now = 1000L
      val store = SessionStore
        .testing(Array.fill[Byte](32)(9), () => now, accessLifetimeSeconds = 10).toOption.get
      val token = store.issue(did).accessJwt
      assert(store.verifyAccess(token).isRight)
      val signatureStart = token.lastIndexOf('.') + 1
      val tampered = token
        .updated(signatureStart, if token.charAt(signatureStart) == 'a' then 'b' else 'a')
      isLeft(store.verifyAccess(tampered))
      assert(store.revoke(token).isRight)
      isLeft(store.verifyAccess(token))

      val expiring = store.issue(did).accessJwt
      now = 1010L
      isLeft(store.verifyAccess(expiring))
    }

    test("requires a 256-bit session signing secret") {
      isLeft(SessionStore.testing(Array.fill[Byte](31)(1), () => 0L))
    }
