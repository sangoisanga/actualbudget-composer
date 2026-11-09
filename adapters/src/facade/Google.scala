package composer.adapters.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("google-auth-library", "OAuth2Client")
class OAuth2Client(clientId: String, clientSecret: String, redirectUri: js.UndefOr[String] = js.undefined) extends js.Object {
  def setCredentials(credentials: js.Object): Unit = js.native
  def generateAuthUrl(options: js.Object): String = js.native
  def getToken(code: String): js.Promise[TokenResponse] = js.native
}

@js.native
trait TokenResponse extends js.Object {
  val tokens: Tokens = js.native
}

@js.native
trait Tokens extends js.Object {
  val access_token: js.UndefOr[String | Null] = js.native
  val refresh_token: js.UndefOr[String | Null] = js.native
}

@js.native
@JSImport("@googleapis/gmail", "gmail")
object gmail extends js.Object {
  def apply(options: js.Object): GmailClient = js.native
}

@js.native
trait GmailClient extends js.Object {
  val users: GmailUsers = js.native
}

@js.native
trait GmailUsers extends js.Object {
  val messages: GmailMessages = js.native
}

@js.native
trait GmailMessages extends js.Object {
  def list(params: js.Object): js.Promise[GmailResponse[ListMessagesResponse]] = js.native
  def get(params: js.Object): js.Promise[GmailResponse[GmailMessage]] = js.native
}

@js.native
trait GmailResponse[A] extends js.Object {
  val data: A = js.native
}

@js.native
trait ListMessagesResponse extends js.Object {
  val messages: js.UndefOr[js.Array[GmailMessageRef]] = js.native
  val nextPageToken: js.UndefOr[String | Null] = js.native
}

@js.native
trait GmailMessageRef extends js.Object {
  val id: js.UndefOr[String | Null] = js.native
}

@js.native
trait GmailMessage extends js.Object {
  val payload: js.UndefOr[MessagePart] = js.native
}

@js.native
trait MessagePart extends js.Object {
  val mimeType: js.UndefOr[String | Null] = js.native
  val headers: js.UndefOr[js.Array[MessageHeader]] = js.native
  val body: js.UndefOr[MessageBody] = js.native
  val parts: js.UndefOr[js.Array[MessagePart]] = js.native
}

@js.native
trait MessageHeader extends js.Object {
  val name: js.UndefOr[String | Null] = js.native
  val value: js.UndefOr[String | Null] = js.native
}

@js.native
trait MessageBody extends js.Object {
  val data: js.UndefOr[String | Null] = js.native
}
