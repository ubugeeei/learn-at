package learnat.tests

object AllTests:
  def main(args: Array[String]): Unit =
    val _ = args
    JsonTests.run()
    SyntaxTests.run()
    InteropSyntaxTests.run()
    XrpcTests.run()
    IdentityTests.run()
    LexiconTests.run()
    IpldTests.run()
    DagJsonTests.run()
    CarTests.run()
    MstTests.run()
    CryptoTests.run()
    InteropCryptoTests.run()
    RepositoryTests.run()
    AuthTests.run()
    OAuthTests.run()
    LocalPdsE2eTests.run()
    LocalPdsPersistenceTests.run()
    SyncTests.run()
    println(s"${TestKit.count} tests passed")
