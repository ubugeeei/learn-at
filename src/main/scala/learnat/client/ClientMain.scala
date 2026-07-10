package learnat.client

import java.io.PrintStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import learnat.ipld.DagJson
import learnat.ipld.Ipld
import learnat.json.Json
import learnat.syntax.AtIdentifier
import learnat.syntax.Did
import learnat.syntax.Nsid
import learnat.syntax.RecordKey

/** Command-line adapter around [[AtpClient]] with dependency-injected output for tests. */
object ClientMain:
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
      case Vector("list", service, repo, collection) =>
        for
          client <- client(service)
          identifier <- identifier(repo)
          nsid <- collectionId(collection)
          page <- client.listRecords(identifier, nsid)
        yield Json.obj(
          "records" -> Json.Arr(page.records.map(record => Json.obj(
            "uri" -> Json.Str(record.uri.toString),
            "cid" -> Json.Str(record.cid.toString),
            "value" -> DagJson.encode(record.value)
          ))),
          "cursor" -> page.cursor.fold[Json](Json.Null)(Json.Str.apply)
        ).render
      case Vector("post", service, account, collection, text) =>
        for
          password <- environment.get("LEARN_AT_PASSWORD").toRight(ClientError("LEARN_AT_PASSWORD is required for post"))
          client <- client(service)
          identifier <- identifier(account)
          nsid <- collectionId(collection)
          authenticated <- client.login(identifier, password.toCharArray)
          created <- authenticated.createRecord(nsid, Ipld.obj(
            "$type" -> Ipld.Text(nsid.value),
            "text" -> Ipld.Text(text),
            "createdAt" -> Ipld.Text(Instant.now().toString)
          ))
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
            catch case exception: Exception => Left(ClientError(s"failed to write $output: ${exception.getMessage}"))
        yield Json.obj("path" -> Json.Str(Path.of(output).toAbsolutePath.toString), "bytes" -> Json.Num(bytes.length)).render
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
    catch case exception: IllegalArgumentException => Left(ClientError(s"invalid service URI: ${exception.getMessage}"))

  private def identifier(value: String): Either[ClientError, AtIdentifier] =
    AtIdentifier.parse(value).left.map(error => ClientError(error.toString))

  private def collectionId(value: String): Either[ClientError, Nsid] =
    Nsid.parse(value).left.map(error => ClientError(error.toString))

  private def rkey(value: String): Either[ClientError, RecordKey] =
    RecordKey.parse(value).left.map(error => ClientError(error.toString))

  private val usage =
    """usage:
      |  learn-at client get <service> <repo> <collection> <rkey>
      |  learn-at client list <service> <repo> <collection>
      |  LEARN_AT_PASSWORD=... learn-at client post <service> <account> <collection> <text>
      |  learn-at client export <service> <did> <output.car>""".stripMargin

