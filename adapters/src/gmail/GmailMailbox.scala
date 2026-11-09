package composer.adapters.gmail

import composer.adapters.facade.{GmailClient, MessagePart, NodeBuffer, OAuth2Client, gmail}
import composer.application.{Email, Mailbox}
import composer.domain.BankZone

import java.time.{Instant, LocalDate}
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

final case class GmailCredentials(clientId: String, clientSecret: String, refreshToken: String)

final class GmailMailbox private (auth: OAuth2Client, client: GmailClient) extends Mailbox {
  def setRefreshToken(token: String): Unit = auth.setCredentials(js.Dynamic.literal(refresh_token = token))

  def list(query: String): js.Promise[List[String]] = js.async {
    val ids = List.newBuilder[String]
    var pageToken: Option[String] = None
    var hasNextPage = true
    while hasNextPage do {
      val page = js.await(client.users.messages.list(js.Dynamic.literal(userId = "me", q = query, maxResults = 50, pageToken = pageToken.orUndefined))).data
      ids ++= page.messages.getOrElse(js.Array()).flatMap(_.id.toOption.flatMap(Option(_)))
      pageToken = page.nextPageToken.toOption.flatMap(Option(_))
      hasNextPage = pageToken.isDefined
    }
    ids.result()
  }

  def get(id: String): js.Promise[Email] = js.async {
    val payload = js.await(client.users.messages.get(js.Dynamic.literal(userId = "me", id = id, format = "full"))).data.payload.toOption
    val headers = payload
      .flatMap(_.headers.toOption)
      .getOrElse(js.Array())
      .toList
      .map(header => header.name.toOption.flatMap(Option(_)).getOrElse("").toLowerCase -> header.value.toOption.flatMap(Option(_)).getOrElse(""))
      .toMap
    val date = GmailMessages.headerDate(headers.get("date"), js.Date.now().toLong)
    Email(id, GmailMessages.extractAddress(headers.get("from")), headers.getOrElse("subject", ""), date, payload.fold("")(GmailMessages.extractHtmlBody))
  }
}

object GmailMailbox {
  def apply(credentials: GmailCredentials): GmailMailbox = {
    val auth = OAuth2Client(credentials.clientId, credentials.clientSecret)
    auth.setCredentials(js.Dynamic.literal(refresh_token = credentials.refreshToken))
    new GmailMailbox(auth, gmail(js.Dynamic.literal(version = "v1", auth = auth)))
  }
}

object GmailMessages {
  def extractAddress(fromHeader: Option[String]): String = {
    val bracketed = """<([^>]+@[^>]+)>""".r
    val bare = """([^\s<]+@[^\s>]+)""".r
    fromHeader.flatMap(header => bracketed.findFirstMatchIn(header).orElse(bare.findFirstMatchIn(header))).map(_.group(1).toLowerCase).getOrElse("")
  }

  def extractHtmlBody(part: MessagePart): String = {
    val ownHtml = for {
      mimeType <- part.mimeType.toOption.flatMap(Option(_)) if mimeType == "text/html"
      data <- part.body.toOption.flatMap(_.data.toOption).flatMap(Option(_))
    } yield NodeBuffer.from(data, "base64url").toString("utf-8")
    ownHtml.getOrElse(part.parts.getOrElse(js.Array()).iterator.map(extractHtmlBody).find(_.nonEmpty).getOrElse(""))
  }

  def headerDate(header: Option[String], nowMillis: Long): LocalDate = {
    val parsed = header.map(js.Date.parse).filterNot(_.isNaN).map(_.toLong).getOrElse(nowMillis)
    Instant.ofEpochMilli(parsed).atZone(BankZone).toLocalDate
  }
}
