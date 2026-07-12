package learnat.tests

import java.nio.charset.StandardCharsets
import java.util.Base64
import learnat.crypto.Base58Btc
import learnat.crypto.P256
import learnat.crypto.P256KeyPair
import learnat.tests.TestKit.*

object CryptoTests:
  private val fixtureMessage = Base64.getDecoder.decode("oWVoZWxsb2V3b3JsZA")
  private val fixtureDidKey = "did:key:zDnaembgSGUhZULN2Caob4HLJPaxBh92N7rtH21TErzqf8HQo"
  private val validFixture = Base64.getDecoder.decode(
    "2vZNsG3UKvvO/CDlrdvyZRISOFylinBh0Jupc6KcWoJWExHptCfduPleDbG3rko3YZnn9Lw0IjpixVmexJDegg"
  )
  private val highSFixture = Base64.getDecoder.decode(
    "2vZNsG3UKvvO/CDlrdvyZRISOFylinBh0Jupc6KcWoKp7O4VS9giSAah8k5IUbXIW00SuOrjfEqQ9HEkN9JGzw"
  )
  private val derFixture = Base64.getDecoder.decode(
    "MEQCIFxYelWJ9lNcAVt+jK0y/T+DC/X4ohFZ+m8f9SEItkY1AiACX7eXz5sgtaRrz/SdPR8kprnbHMQVde0T2R8yOTBweA"
  )

  def run(): Unit =
    println("P-256 cryptography")

    test("round trips base58btc including leading zero bytes") {
      val bytes = Array[Byte](0, 0, 1, 2, 3, -1)
      equal(Base58Btc.decode(Base58Btc.encode(bytes)).map(_.toVector), Right(bytes.toVector))
      isLeft(Base58Btc.decode("0OIl"))
    }

    test("verifies the official P-256 low-S fixture") {
      val publicKey = P256.publicKeyFromDidKey(fixtureDidKey).toOption.get
      assert(publicKey.verify(fixtureMessage, validFixture))
      equal(publicKey.didKey, fixtureDidKey)
    }

    test("rejects official high-S and DER signature fixtures") {
      val publicKey = P256.publicKeyFromDidKey(fixtureDidKey).toOption.get
      assert(!publicKey.verify(fixtureMessage, highSFixture))
      assert(!publicKey.verify(fixtureMessage, derFixture))
    }

    test("generates compact low-S signatures and detects tampering") {
      val pair = P256KeyPair.generate().toOption.get
      val message = "signed repository commit".getBytes(StandardCharsets.UTF_8)
      val signature = pair.sign(message).toOption.get.toArray
      equal(signature.length, 64)
      assert(pair.publicKey.verify(message, signature))
      message(0) = (message(0) ^ 1).toByte
      assert(!pair.publicKey.verify(message, signature))
    }

    test("restores PKCS#8 keys with their public multikey") {
      val generated = P256KeyPair.generate().toOption.get
      val restored = P256KeyPair
        .restore(generated.privateKeyPkcs8.toArray, generated.publicKey.multikey).toOption.get
      val message = Array[Byte](1, 2, 3)
      val signature = restored.sign(message).toOption.get.toArray
      assert(generated.publicKey.verify(message, signature))
    }

    test("rejects wrong multicodec and malformed compressed points") {
      val key = P256KeyPair.generate().toOption.get.publicKey.multikey
      val decoded = Base58Btc.decode(key.drop(1)).toOption.get
      decoded(0) = 0
      isLeft(P256.publicKeyFromMultikey(s"z${Base58Btc.encode(decoded)}"))
      isLeft(P256.publicKeyFromMultikey("z1"))
    }
