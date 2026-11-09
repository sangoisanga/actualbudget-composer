package composer.telegram

import composer.BotConfig
import composer.adapters.RuleFiles
import composer.adapters.actual.ActualSession
import composer.adapters.facade.{Bot, BotContext, NodeFs}
import composer.adapters.gmail.{GmailCredentials, GmailMailbox}
import composer.adapters.log.JsonLog
import composer.adapters.schedule.CronJob
import composer.adapters.sqlite.BotStore
import composer.adapters.telegram.Heartbeat
import composer.application.{Async, Log, PollConfig, PollService}

import scala.concurrent.duration.*
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.timers.{clearInterval, setInterval}

object BotApp {
  private val process = js.Dynamic.global.process

  def run(config: BotConfig): js.Promise[Int] = js.async {
    JsonLog.redirectConsole(JsonLog.stdout)
    val log = JsonLog(config.isDebug, JsonLog.stdout)
    NodeFs.mkdirSync(config.actual.dataDir, js.Dynamic.literal(recursive = true))
    val store = BotStore.open(s"${config.actual.dataDir}/bot.sqlite")
    val files = RuleFiles.load(config.rulesDir)
    val refreshToken = store.refreshToken.orElse(config.refreshToken).getOrElse(throw RuntimeException("No Gmail refresh token. Set GMAIL_REFRESH_TOKEN or run npm run auth:gmail"))
    val mailbox = GmailMailbox(GmailCredentials(config.oauth.clientId, config.oauth.clientSecret, refreshToken))
    val bot = Bot(config.telegramToken)
    val sendToChat = (text: String, options: js.UndefOr[js.Object]) => js.async(js.await(bot.api.sendMessage(config.allowedChatId.toDouble, text, options)): Unit)
    val notifyExpired = (dropped: Int) => if dropped == 0 then js.Promise.resolve(()) else sendToChat(s"Dropped $dropped transaction(s) that still needed an account after 15 minutes.", js.undefined)
    val service = PollService(ActualSession(config.actual), mailbox, PollConfig(files.rules, files.payees), log, notifyExpired)
    val savedCron = store.pollCron.flatMap(text => CronJob.check(text).fold(reason => { log.error(s"Saved poll cron is not valid: $reason"); None }, Some(_)))
    val schedule = ScheduledPoll(service, sendToChat, config.lookbackDays, log, savedCron)

    var lastPollAt = js.Date.now().toLong
    bot.api.config.use(Heartbeat.trackPolls { () =>
      lastPollAt = js.Date.now().toLong
      try Heartbeat.write(Heartbeat.path, lastPollAt)
      catch { case error: Throwable => log.error(s"Heartbeat write failed: ${Async.messageOf(error)}") }
    })
    Commands.register(bot, Commands(config, service, mailbox, store, schedule), config.allowedChatId, guard(log))
    js.await(bot.api.setMyCommands(Commands.all.map((name, description) => js.Dynamic.literal(command = name, description = description): js.Object).toJSArray))
    log.info("Telegram bot started", "poll_cron" -> schedule.current.getOrElse("off"))
    schedule.start()
    js.await(sendToChat(Format.schedule(schedule.current, schedule.nextRunAt), js.undefined))

    // The first stop signal wins. A promise ignores every later resolve call.
    var stop: Int => Unit = _ => ()
    val stopped = js.Promise[Int]((resolve, _) => stop = code => resolve(code))
    val watchdog = setInterval(1.minute) {
      if Heartbeat.isStale(lastPollAt, js.Date.now().toLong) then {
        log.error(s"No successful getUpdates for ${Heartbeat.staleAfter.toMinutes} minutes. Exiting for a restart.")
        stop(1)
      }
    }
    process.once("SIGINT", () => stop(0))
    process.once("SIGTERM", () => stop(0))
    bot
      .start()
      .`then`[Unit](
        (_: Unit) => stop(0),
        (error: Any) => {
          log.error(s"Telegram polling failed: ${Async.messageOf(Async.toThrowable(error))}")
          stop(1)
        }
      )

    val exitCode = js.await(stopped)
    clearInterval(watchdog)
    schedule.stop()
    js.await(service.shutdown())
    store.close()
    js.await(bot.stop())
    exitCode
  }

  private def guard(log: Log)(handler: BotContext => js.Promise[Unit]): js.Function1[BotContext, js.Promise[Unit]] = context =>
    js.async {
      try js.await(handler(context))
      catch { case error: Throwable => log.error(s"Handler failed: ${Async.messageOf(error)}") }
    }
}
