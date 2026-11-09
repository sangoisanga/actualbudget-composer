package composer.adapters.schedule

import composer.adapters.facade.Cron
import composer.application.Async
import composer.domain.BankZone

import java.time.Instant
import scala.scalajs.js

final class CronJob private (val text: String, cron: Cron) {
  def nextRun: Option[Instant] = CronJob.instantOf(cron.nextRun())

  def stop(): Unit = cron.stop()
}

object CronJob {
  private val offsetMinutes = BankZone.getRules.getOffset(Instant.EPOCH).getTotalSeconds / 60

  def check(text: String): Either[String, String] = {
    val pattern = text.trim
    try {
      val cron = Cron(pattern, options(isPaused = true), () => js.Promise.resolve[Unit](()))
      val nextRun = instantOf(cron.nextRun())
      cron.stop()
      Either.cond(nextRun.isDefined, pattern, s"$pattern never runs.")
    } catch { case error: js.JavaScriptException => Left(Async.messageOf(error)) }
  }

  def start(text: String, run: () => js.Promise[Unit]): CronJob = new CronJob(text, Cron(text, options(isPaused = false), () => run()))

  private def options(isPaused: Boolean): js.Object = js.Dynamic.literal(utcOffset = offsetMinutes, mode = "5-part", protect = true, paused = isPaused)

  private def instantOf(date: js.Date | Null): Option[Instant] = date match {
    case found: js.Date => Some(Instant.ofEpochMilli(found.getTime().toLong))
    case null           => None
  }
}
