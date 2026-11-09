package composer.telegram

import composer.BotConfig
import composer.adapters.facade.{Bot, BotContext, InlineKeyboard}
import composer.adapters.gmail.{GmailAuth, GmailMailbox}
import composer.adapters.schedule.CronJob
import composer.adapters.sqlite.BotStore
import composer.application.*

import scala.scalajs.js

final class Commands(config: BotConfig, service: PollService, mailbox: GmailMailbox, store: BotStore, schedule: ScheduledPoll) {
  private val html = js.Dynamic.literal(parse_mode = "HTML")

  private def reply(context: BotContext, text: String, options: js.UndefOr[js.Object] = js.undefined): js.Promise[Unit] = js.async(js.await(context.reply(text, options)): Unit)

  private def replyAll(context: BotContext, texts: List[String], options: js.Object): js.Promise[Unit] = js.async(js.await(Async.traverse(texts)(reply(context, _, options))): Unit)

  private def argument(context: BotContext): String = context.`match`.toOption.map(_.toString.trim).getOrElse("")

  def poll(context: BotContext): js.Promise[Unit] = js.async {
    if service.pending.isDefined then js.await(reply(context, "Account picks are still pending. /cancel them first."))
    else {
      val lookbackDays = argument(context).toIntOption.filter(_ > 0).getOrElse(config.lookbackDays)
      js.await(reply(context, "Polling Gmail for new transactions. This can take a minute."))
      val start =
        try Right(js.await(service.poll(lookbackDays)))
        catch { case error: Throwable => Left(Async.messageOf(error)) }
      start match {
        case Left(reason)                                      => js.await(reply(context, s"Poll failed: $reason"))
        case Right(PollStart.Busy)                             => js.await(reply(context, "A poll is already running."))
        case Right(PollStart.PicksPending)                     => js.await(reply(context, "Account picks are still pending. /cancel them first."))
        case Right(PollStart.Finished(report, names, prompts)) =>
          js.await(replyAll(context, Format.pollMessages(report, names), html))
          js.await(Async.traverse(prompts)(prompt => reply(context, Format.accountPrompt(prompt.accountKey, prompt.drafts), Commands.keyboard(prompt))))
      }
    }
  }

  def pick(context: BotContext): js.Promise[Unit] = js.async {
    val groups = context.`match`.asInstanceOf[js.Array[String]]
    val (groupKey, accountId) = (groups(1), groups(2))
    val result =
      try Right(js.await(service.assign(groupKey, accountId)))
      catch { case error: Throwable => Left(Async.messageOf(error)) }
    result match {
      case Left(reason) =>
        js.await(context.answerCallbackQuery())
        js.await(reply(context, s"Import failed: $reason"))
      case Right(AssignResult.NoPending)                                  => js.await(context.answerCallbackQuery("No pending account picks."))
      case Right(AssignResult.AlreadyResolved)                            => js.await(context.answerCallbackQuery("Already resolved."))
      case Right(AssignResult.Imported(report, names, accountKey, count)) =>
        js.await(context.answerCallbackQuery())
        js.await(context.editMessageText(Format.accountPick(accountKey, names.getOrElse(accountId, accountId), count)))
        js.await(replyAll(context, Format.pollMessages(report, names), html))
    }
  }

  def status(context: BotContext): js.Promise[Unit] = service.pending match {
    case Some(pending) => reply(context, s"${pending.count} transaction(s) awaiting an account since ${pending.since}.")
    case None          => reply(context, "Nothing pending.")
  }

  def cancel(context: BotContext): js.Promise[Unit] = js.async {
    val dropped = js.await(service.cancel())
    js.await(reply(context, if dropped > 0 then s"Dropped $dropped transaction(s) that still needed an account. Imported rows stay." else "Nothing pending."))
  }

  def cron(context: BotContext): js.Promise[Unit] = argument(context) match {
    case ""    => reply(context, Format.schedule(schedule.current, schedule.nextRunAt))
    case "off" =>
      store.clearPollCron()
      schedule.restart(None)
      reply(context, Format.schedule(None, None))
    case text =>
      CronJob.check(text) match {
        case Left(reason) => reply(context, s"$reason Send /cron followed by 5 fields, like /cron 0 */6 * * *, or /cron off.")
        case Right(cron)  =>
          store.savePollCron(cron)
          schedule.restart(Some(cron))
          reply(context, Format.schedule(schedule.current, schedule.nextRunAt))
      }
  }

  def auth(context: BotContext): js.Promise[Unit] = argument(context) match {
    case ""       => reply(context, s"Open this URL and approve access. Then send /auth followed by the URL you land on.\n${GmailAuth.authUrl(config.oauth)}")
    case callback =>
      js.async {
        val outcome =
          try {
            val code = GmailAuth.extractAuthCode(callback).fold(reason => throw RuntimeException(reason), identity)
            val token = js.await(GmailAuth.exchangeCode(config.oauth, code))
            store.saveRefreshToken(token)
            mailbox.setRefreshToken(token)
            Right(())
          } catch { case error: Throwable => Left(Async.messageOf(error)) }
        // The callback URL carries a one-time code, so the chat must not keep it.
        try js.await(context.deleteMessage())
        catch { case _: Throwable => () }
        outcome match {
          case Left(reason) => js.await(reply(context, s"Auth failed: $reason"))
          case Right(_)     => js.await(reply(context, "Gmail token saved. The next poll uses it."))
        }
      }
  }

  def unknown(context: BotContext): js.Promise[Unit] = reply(context, s"Unknown command. Try ${Commands.all.map((name, _) => s"/$name").mkString(", ")}.")
}

object Commands {
  val all: List[(String, String)] = List(
    "poll" -> "Import new transactions from Gmail. Optional: days to look back",
    "status" -> "Show transactions that wait for an account",
    "cancel" -> "Drop transactions that wait for an account",
    "cron" -> "Show, set, or turn off the poll crontab, like 0 */6 * * *",
    "auth" -> "Rotate the Gmail token. Optional: callback URL"
  )

  def keyboard(prompt: PickPrompt): js.Object = {
    val buttons = prompt.choices.foldLeft(InlineKeyboard())((keyboard, choice) => keyboard.text(choice._2, s"acct:${prompt.groupKey}:${choice._1}").row())
    js.Dynamic.literal(reply_markup = buttons, parse_mode = "HTML")
  }

  def isCommand(context: BotContext): Boolean = context.message.toOption.flatMap(_.entities.toOption).exists(_.exists(entity => entity.`type` == "bot_command" && entity.offset == 0))

  def register(bot: Bot, commands: Commands, allowedChatId: Long, guard: (BotContext => js.Promise[Unit]) => js.Function1[BotContext, js.Promise[Unit]]): Unit = {
    bot.use((context, next) => if context.chat.toOption.exists(_.id == allowedChatId.toDouble) then next() else js.Promise.resolve[Unit](()))
    bot.command("poll", guard(commands.poll))
    bot.command("status", guard(commands.status))
    bot.command("cancel", guard(commands.cancel))
    bot.command("cron", guard(commands.cron))
    bot.command("auth", guard(commands.auth))
    bot.callbackQuery(js.RegExp("^acct:(.+):([0-9a-f-]+)$"), guard(commands.pick))
    // Registered last so that known commands never reach it.
    bot.use((context, next) => if isCommand(context) then guard(commands.unknown)(context) else next())
  }
}
