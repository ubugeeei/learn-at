package learnat.tests

import java.util.Base64
import learnat.crypto.Base58Btc
import learnat.crypto.P256
import learnat.json.Json
import learnat.tests.TestKit.*
import scala.io.Source

object InteropCryptoTests:
  def run(): Unit =
    println("Official cryptography interoperability fixtures")

    test("matches every official ES256 signature verdict") {
      val fixtures = Json.parse(resource("signature-fixtures.json")).toOption.get.asArray.toOption
        .get
      val p256Fixtures = fixtures.filter(_.field("algorithm").flatMap(_.asString).contains("ES256"))
      equal(p256Fixtures.length, 3)
      p256Fixtures.foreach { fixture =>
        val message = decodeBase64(fixture.field("messageBase64").flatMap(_.asString).toOption.get)
        val signature =
          decodeBase64(fixture.field("signatureBase64").flatMap(_.asString).toOption.get)
        val didKey = fixture.field("publicKeyDid").flatMap(_.asString).toOption.get
        val expected = fixture.field("validSignature").flatMap(_.asBoolean).toOption.get
        val actual = P256.publicKeyFromDidKey(didKey).exists(_.verify(message, signature))
        equal(actual, expected)
      }
    }

    test("parses the official W3C-derived P-256 did:key fixture") {
      val fixtures = Json.parse(resource("w3c_didkey_P256.json")).toOption.get.asArray.toOption.get
      equal(fixtures.length, 1)
      val fixture = fixtures.head
      val didKey = fixture.field("publicDidKey").flatMap(_.asString).toOption.get
      val privateBytes = Base58Btc
        .decode(fixture.field("privateKeyBytesBase58").flatMap(_.asString).toOption.get)
      equal(privateBytes.map(_.length), Right(32))
      equal(P256.publicKeyFromDidKey(didKey).map(_.didKey), Right(didKey))
    }

  private def decodeBase64(value: String): Array[Byte] = Base64.getDecoder.decode(value)

  private def resource(name: String): String =
    val stream = Option(getClass.getResourceAsStream(s"/interop/crypto/$name"))
      .getOrElse(throw IllegalStateException(s"missing crypto fixture: $name"))
    val source = Source.fromInputStream(stream, "UTF-8")
    try source.mkString
    finally source.close()
