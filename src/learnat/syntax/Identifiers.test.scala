package learnat.tests

import learnat.syntax.*
import learnat.tests.TestKit.*

object SyntaxTests:
  def run(): Unit =
    println("Syntax")

    test("distinguishes DIDs from handles") {
      val did = AtIdentifier.parse("did:plc:7iza6de2dwap2sbkpav7c6c6")
      val handle = AtIdentifier.parse("alice.bsky.social")
      assert(did.exists(_.isInstanceOf[AtIdentifier.DidIdentifier]))
      assert(handle.exists(_.isInstanceOf[AtIdentifier.HandleIdentifier]))
    }

    test("validates DID syntax separately from supported methods") {
      val generic = Did.parse("did:example:123")
      assert(generic.isRight)
      equal(generic.map(_.isSupportedByAtproto), Right(false))
      isLeft(Did.parse("DID:plc:123"))
      isLeft(Did.parse("did:plc:"))
    }

    test("validates and normalizes handles") {
      equal(Handle.parse("Alice.BSKY.Social").map(_.normalized), Right("alice.bsky.social"))
    }

    cases("rejects invalid handles")(
      "single label" -> "john",
      "trailing label dash" -> "john-.test",
      "IPv4 address" -> "127.0.0.1"
    )(input => isLeft(Handle.parse(input)))

    test("splits NSID authority and name") {
      val nsid = Nsid.parse("com.Example.getThing")
      equal(nsid.map(_.authority), Right("Example.com"))
      equal(nsid.map(_.name), Right("getThing"))
      equal(nsid.map(_.normalized), Right("com.example.getThing"))
      isLeft(Nsid.parse("com.example"))
      isLeft(Nsid.parse("com.example.get-thing"))
    }

    cases("accepts general record keys")("colon" -> "literal:self", "safe symbols" -> "~1.2-3_")(
      input => assert(RecordKey.parse(input).isRight)
    )

    cases("rejects unsafe record keys")(
      "current directory" -> ".",
      "parent directory" -> "..",
      "path separator" -> "alpha/beta"
    )(input => isLeft(RecordKey.parse(input)))

    test("parses strict AT URIs into typed parts") {
      val input = "at://did:plc:asdf123/com.atproto.feed.post/3jzfcijpj2z2a#/text"
      val parsed = AtUri.parse(input)
      equal(parsed.map(_.toString), Right(input))
      equal(
        parsed.flatMap(_.collection.toRight(SyntaxError("test", input, "missing"))).map(_.value),
        Right("com.atproto.feed.post")
      )
      isLeft(AtUri.parse("at://did:plc:asdf123/com.atproto.feed.post/"))
      isLeft(AtUri.parse("at://did:plc:asdf123?query=true"))
    }

    test("encodes and decodes the 64-bit TID layout") {
      val tid = Tid.fromParts(1_688_656_168_744_000L, 8)
      assert(tid.isRight)
      equal(
        tid.flatMap(value => Tid.parse(value.value)).map(_.timestampMicros),
        Right(1_688_656_168_744_000L)
      )
      equal(tid.flatMap(value => Tid.parse(value.value)).map(_.clockId), Right(8))
      isLeft(Tid.parse("3JZFCIJPJ2Z2A"))
    }

    test("generates strictly increasing TIDs across clock stalls") {
      val generator = TidGenerator.deterministic(() => 1_700_000_000_000_000L, 7).toOption.get
      val first = generator.next()
      val second = generator.next()
      assert(second.newerThan(first))
      equal(second.timestampMicros, first.timestampMicros + 1)
    }
