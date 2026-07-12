package learnat.client

import java.io.PrintStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import learnat.crypto.P256
import learnat.identity.IdentityResolver
import learnat.identity.IdentityResolverConfig
import learnat.identity.JdkIdentityNetwork
import learnat.ipld.DagJson
import learnat.ipld.Ipld
import learnat.json.Json
import learnat.repo.RepositoryVerifier
import learnat.syntax.AtIdentifier
import learnat.syntax.Did
import learnat.syntax.Nsid
import learnat.syntax.RecordKey

/** Command-line adapter around [[AtpClient]] with dependency-injected output for tests. */
object ClientMain:
  private val MaxRecordFileBytes = 1024 * 1024
  private val MaxCarFileBytes = 64 * 1024 * 1024

  def main(args: Array[String]): Unit =
    val status = run(args, sys.env, System.out, System.err)
    if status != 0 then sys.exit(status)

  /** Executes one CLI command and returns a process-style status code. */
  def run(
      args: Array[String],
      environment: Map[String, String],
      out: PrintStream,
      errorOut: PrintStream
  ): Int =
    val result = args.toVector match
      case Vector("get", service, repo, collection, recordKey) =>
        for
          client <- client(service)
          identifier <- identifier(repo)
          nsid <- collectionId(collection)
          key <- rkey(recordKey)
          record <- client.getRecord(identifier, nsid, key)
        yield Json.obj(
          "uri" -> Json.Str(record.uri.toString),
          "cid" -> Json.Str(record.cid.toString),
          "value" -> DagJson.encode(record.value)
        ).render
      case Vector("list", service, repo, collection, options*) =>
        for
          client <- client(service)
          identifier <- identifier(repo)
          nsid <- collectionId(collection)
          listOptions <- parseListOptions(options)
          page <- client.listRecords(
            identifier,
            nsid,
            listOptions.limit,
            listOptions.cursor,
            listOptions.reverse
          )
        yield Json.obj(
          "records" -> Json.Arr(page.records.map(record =>
            Json.obj(
              "uri" -> Json.Str(record.uri.toString),
              "cid" -> Json.Str(record.cid.toString),
              "value" -> DagJson.encode(record.value)
            )
          )),
          "cursor" -> page.cursor.fold[Json](Json.Null)(Json.Str.apply)
        ).render
      case Vector("create", service, account, collection, input) =>
        createFromFile(service, account, collection, input, None, environment)
      case Vector("create", service, account, collection, input, recordKey) =>
        createFromFile(service, account, collection, input, Some(recordKey), environment)
      case Vector("put", service, account, collection, recordKey, input) =>
        for
          authenticated <- login(service, account, environment)
          nsid <- collectionId(collection)
          key <- rkey(recordKey)
          record <- readRecord(input)
          written <- authenticated.putRecord(nsid, key, record)
        yield renderWrite(written)
      case Vector("delete", service, account, collection, recordKey) =>
        for
          authenticated <- login(service, account, environment)
          nsid <- collectionId(collection)
          key <- rkey(recordKey)
          _ <- authenticated.deleteRecord(nsid, key)
        yield Json.obj(
          "deleted" -> Json.Bool(true),
          "uri" -> Json.Str(s"at://${authenticated.session.did.value}/${nsid.value}/${key.value}")
        ).render
      case Vector("post", service, account, collection, text) =>
        for
          password <- environment.get("LEARN_AT_PASSWORD")
            .toRight(ClientError("LEARN_AT_PASSWORD is required for post"))
          client <- client(service)
          identifier <- identifier(account)
          nsid <- collectionId(collection)
          authenticated <- client.login(identifier, password.toCharArray)
          created <- authenticated.createRecord(
            nsid,
            Ipld.obj(
              "$type" -> Ipld.Text(nsid.value),
              "text" -> Ipld.Text(text),
              "createdAt" -> Ipld.Text(Instant.now().toString)
            )
          )
        yield Json.obj(
          "uri" -> Json.Str(created.uri.toString),
          "cid" -> Json.Str(created.cid.toString),
          "commit" -> Json.obj(
            "cid" -> created.commitCid.fold[Json](Json.Null)(cid => Json.Str(cid.toString)),
            "rev" -> created.revision.fold[Json](Json.Null)(Json.Str.apply)
          )
        ).render
      case Vector("export", service, didText, output) =>
        for
          client <- client(service)
          did <- Did.parse(didText).left.map(value => ClientError(value.toString))
          bytes <- client.getRepo(did)
          _ <-
            try
              Files.write(Path.of(output), bytes)
              Right(())
            catch
              case exception: Exception =>
                Left(ClientError(s"failed to write $output: ${exception.getMessage}"))
        yield Json.obj(
          "path" -> Json.Str(Path.of(output).toAbsolutePath.toString),
          "bytes" -> Json.Num(bytes.length)
        ).render
      case Vector("verify", didText, input) => verifyExport(didText, input, allowHttpLocal = false)
      case Vector("verify", didText, input, "--allow-http-local") =>
        verifyExport(didText, input, allowHttpLocal = true)
      case _ => Left(ClientError(usage))

    result match
      case Right(value) =>
        out.println(value)
        0
      case Left(error) =>
        errorOut.println(error.message)
        2

  private def client(value: String): Either[ClientError, AtpClient] =
    try AtpClient.create(URI.create(value))
    catch
      case exception: IllegalArgumentException =>
        Left(ClientError(s"invalid service URI: ${exception.getMessage}"))

  private def identifier(value: String): Either[ClientError, AtIdentifier] = AtIdentifier
    .parse(value).left.map(error => ClientError(error.toString))

  private def collectionId(value: String): Either[ClientError, Nsid] = Nsid.parse(value).left
    .map(error => ClientError(error.toString))

  private def rkey(value: String): Either[ClientError, RecordKey] = RecordKey.parse(value).left
    .map(error => ClientError(error.toString))

  final private case class ListOptions(limit: Int, cursor: Option[String], reverse: Boolean)

  private def parseListOptions(values: Seq[String]): Either[ClientError, ListOptions] =
    def loop(remaining: List[String], result: ListOptions): Either[ClientError, ListOptions] =
      remaining match
        case Nil                        => Right(result)
        case "--limit" :: value :: tail => value.toIntOption
            .filter(number => number >= 1 && number <= 100) match
            case Some(number) => loop(tail, result.copy(limit = number))
            case None         => Left(ClientError("--limit must be an integer from 1 to 100"))
        case "--cursor" :: value :: tail if value.nonEmpty =>
          loop(tail, result.copy(cursor = Some(value)))
        case "--reverse" :: tail => loop(tail, result.copy(reverse = true))
        case option :: _         => Left(ClientError(s"unknown or incomplete list option: $option"))
    loop(values.toList, ListOptions(50, None, false))

  private def login(
      service: String,
      account: String,
      environment: Map[String, String]
  ): Either[ClientError, AuthenticatedAtpClient] =
    for
      password <- environment.get("LEARN_AT_PASSWORD").filter(_.nonEmpty)
        .toRight(ClientError("LEARN_AT_PASSWORD is required"))
      client <- client(service)
      identifier <- identifier(account)
      authenticated <- client.login(identifier, password.toCharArray)
    yield authenticated

  private def createFromFile(
      service: String,
      account: String,
      collection: String,
      input: String,
      recordKey: Option[String],
      environment: Map[String, String]
  ): Either[ClientError, String] =
    for
      authenticated <- login(service, account, environment)
      nsid <- collectionId(collection)
      key <- recordKey match
        case Some(value) => rkey(value).map(Some.apply)
        case None        => Right(None)
      record <- readRecord(input)
      written <- authenticated.createRecord(nsid, record, key)
    yield renderWrite(written)

  private def readRecord(value: String): Either[ClientError, Ipld] =
    val path = Path.of(value)
    try
      val size = Files.size(path)
      if size > MaxRecordFileBytes then
        Left(ClientError(s"record file exceeds $MaxRecordFileBytes bytes: $value"))
      else
        Json.parse(Files.readString(path)).left
          .map(error => ClientError(s"invalid JSON in $value: ${error.toString}")).flatMap(json =>
            DagJson.decode(json).left
              .map(error => ClientError(s"invalid DAG-JSON in $value: ${error.toString}"))
          ).flatMap {
            case record @ Ipld.Map(_) => Right(record)
            case _ => Left(ClientError(s"record in $value must be a JSON object"))
          }
    catch
      case exception: Exception =>
        Left(ClientError(s"failed to read $value: ${exception.getMessage}"))

  private def renderWrite(written: RecordWriteResult): String = Json.obj(
    "uri" -> Json.Str(written.uri.toString),
    "cid" -> Json.Str(written.cid.toString),
    "commit" -> Json.obj(
      "cid" -> written.commitCid.fold[Json](Json.Null)(cid => Json.Str(cid.toString)),
      "rev" -> written.revision.fold[Json](Json.Null)(Json.Str.apply)
    )
  ).render

  private def verifyExport(
      didText: String,
      input: String,
      allowHttpLocal: Boolean
  ): Either[ClientError, String] =
    for
      did <- Did.parse(didText).left.map(error => ClientError(error.toString))
      bytes <- readBoundedFile(Path.of(input), MaxCarFileBytes, "CAR")
      identity <- IdentityResolver(
        JdkIdentityNetwork.default,
        IdentityResolverConfig(allowHttpLocal = allowHttpLocal, allowTestTld = allowHttpLocal)
      ).resolve(AtIdentifier.DidIdentifier(did)).left.map(error => ClientError(error.description))
      publicKey <- P256.publicKeyFromMultikey(identity.signingKeyMultibase).left
        .map(error => ClientError(error.message))
      repository <- RepositoryVerifier.verifyCar(bytes, did, publicKey).left
        .map(error => ClientError(error.message))
    yield Json.obj(
      "verified" -> Json.Bool(true),
      "did" -> Json.Str(did.value),
      "commit" -> Json.Str(repository.commitCid.toString),
      "revision" -> Json.Str(repository.commit.rev.value),
      "records" -> Json.Num(repository.records.length)
    ).render

  private def readBoundedFile(
      path: Path,
      maximumBytes: Int,
      kind: String
  ): Either[ClientError, Array[Byte]] =
    try
      val size = Files.size(path)
      if size > maximumBytes then
        Left(ClientError(s"$kind file exceeds $maximumBytes bytes: $path"))
      else Right(Files.readAllBytes(path))
    catch
      case exception: Exception =>
        Left(ClientError(s"failed to read $path: ${exception.getMessage}"))

  private val usage = """usage:
      |  learn-at client get <service> <repo> <collection> <rkey>
      |  learn-at client list <service> <repo> <collection> [--limit 1..100] [--cursor value] [--reverse]
      |  LEARN_AT_PASSWORD=... learn-at client post <service> <account> <collection> <text>
      |  LEARN_AT_PASSWORD=... learn-at client create <service> <account> <collection> <record.json> [rkey]
      |  LEARN_AT_PASSWORD=... learn-at client put <service> <account> <collection> <rkey> <record.json>
      |  LEARN_AT_PASSWORD=... learn-at client delete <service> <account> <collection> <rkey>
      |  learn-at client export <service> <did> <output.car>
      |  learn-at client verify <did> <input.car> [--allow-http-local]""".stripMargin
