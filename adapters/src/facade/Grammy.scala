package composer.adapters.facade

import composer.adapters.telegram.Heartbeat

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

type Handler = js.Function1[BotContext, js.Promise[Unit]]
type Middleware = js.Function2[BotContext, js.Function0[js.Promise[Unit]], js.Promise[Unit]]

@js.native
@JSImport("grammy", "Bot")
class Bot(token: String) extends js.Object {
  val api: BotApi = js.native
  def use(middleware: Middleware): Unit = js.native
  def command(name: String, handler: Handler): Unit = js.native
  def callbackQuery(trigger: js.RegExp, handler: Handler): Unit = js.native
  def start(): js.Promise[Unit] = js.native
  def stop(): js.Promise[Unit] = js.native
}

@js.native
trait BotApi extends js.Object {
  val config: BotApiConfig = js.native
  def sendMessage(chatId: Double, text: String, other: js.UndefOr[js.Object] = js.undefined): js.Promise[js.Any] = js.native
  def setMyCommands(commands: js.Array[js.Object]): js.Promise[js.Any] = js.native
}

@js.native
trait BotApiConfig extends js.Object {
  def use(transformer: Heartbeat.Transformer): Unit = js.native
}

@js.native
trait BotContext extends js.Object {
  val chat: js.UndefOr[Chat] = js.native
  val message: js.UndefOr[Message] = js.native
  val `match`: js.UndefOr[js.Any] = js.native
  def reply(text: String, other: js.UndefOr[js.Object] = js.undefined): js.Promise[js.Any] = js.native
  def answerCallbackQuery(text: js.UndefOr[String] = js.undefined): js.Promise[js.Any] = js.native
  def editMessageText(text: String): js.Promise[js.Any] = js.native
  def deleteMessage(): js.Promise[js.Any] = js.native
}

@js.native
trait Chat extends js.Object {
  val id: Double = js.native
}

@js.native
trait Message extends js.Object {
  val entities: js.UndefOr[js.Array[MessageEntity]] = js.native
}

@js.native
trait MessageEntity extends js.Object {
  val `type`: String = js.native
  val offset: Int = js.native
}

@js.native
@JSImport("grammy", "InlineKeyboard")
class InlineKeyboard() extends js.Object {
  def text(label: String, data: String): InlineKeyboard = js.native
  def row(): InlineKeyboard = js.native
}
