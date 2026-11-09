package composer.adapters.gmail

import composer.adapters.facade.{OAuth2Client, URL}

import scala.scalajs.js

final case class OAuthConfig(clientId: String, clientSecret: String, redirectUri: String)

object GmailAuth {
  val DefaultRedirectUri = "http://localhost"
  private val ReadOnlyScope = "https://www.googleapis.com/auth/gmail.readonly"
  private val SenderPrefixes = Map("no-reply@cake.vn" -> "cake", "vpbankonline@vpb.com.vn" -> "vpbank")

  def authUrl(config: OAuthConfig): String =
    client(config).generateAuthUrl(js.Dynamic.literal(access_type = "offline", prompt = "consent", scope = js.Array(ReadOnlyScope)))

  def extractAuthCode(input: String): Either[String, String] = {
    val trimmed = input.trim
    if !URL.canParse(trimmed) then Right(trimmed)
    else Option(URL(trimmed).searchParams.get("code")).map(_.toString).toRight("Callback URL has no code parameter")
  }

  def exchangeCode(config: OAuthConfig, code: String): js.Promise[String] = js.async {
    val tokens = js.await(client(config).getToken(code)).tokens
    (tokens.access_token.toOption.flatMap(Option(_)), tokens.refresh_token.toOption.flatMap(Option(_))) match {
      case (Some(_), Some(refreshToken)) => refreshToken
      case _                             => throw RuntimeException("OAuth token exchange returned incomplete tokens")
    }
  }

  def extractMessageId(input: String): Either[String, String] = {
    val trimmed = input.trim
    if !URL.canParse(trimmed) then Right(trimmed)
    else URL(trimmed).hash.split("/").lastOption.filter(_.nonEmpty).filterNot(_.startsWith("#")).toRight(s"Gmail link has no message ID: $input")
  }

  def fixtureName(sender: String, messageId: String): String = {
    val prefix = SenderPrefixes.getOrElse(sender, sender.split("@").lift(1).flatMap(_.split("\\.").headOption).getOrElse("unknown"))
    s"$prefix-$messageId.html"
  }

  private def client(config: OAuthConfig): OAuth2Client = OAuth2Client(config.clientId, config.clientSecret, config.redirectUri)
}
