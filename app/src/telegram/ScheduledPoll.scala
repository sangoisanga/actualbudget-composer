package composer.telegram

import composer.application.{Async, Log, PollService, PollStart}

import scala.concurrent.duration.FiniteDuration
import scala.scalajs.js
import scala.scalajs.js.timers.{SetIntervalHandle, clearInterval, setInterval}

final class ScheduledPoll(service: PollService, send: (String, js.UndefOr[js.Object]) => js.Promise[Unit], lookbackDays: Int, log: Log, initialInterval: FiniteDuration) {
  private val html = js.Dynamic.literal(parse_mode = "HTML")
  private var every = initialInterval
  private var timer: Option[SetIntervalHandle] = None
  private var reportedFailureIds = Set.empty[String]
  private var lastError: Option[String] = None

  def interval: FiniteDuration = every

  def start(): Unit = timer = Some(setInterval(every)(Async.runAndLog(tick(), log, "Scheduled poll")))

  def restart(next: FiniteDuration): Unit = {
    stop()
    every = next
    start()
  }

  def stop(): Unit = {
    timer.foreach(clearInterval)
    timer = None
  }

  private def tick(): js.Promise[Unit] = js.async {
    val outcome =
      try Right(js.await(service.poll(lookbackDays)))
      catch { case error: Throwable => Left(Async.messageOf(error)) }
    outcome match {
      case Left(reason) =>
        // An outage sends one message, on its first failed tick.
        if !lastError.contains(reason) then js.await(send(s"Scheduled poll failed: $reason", js.undefined))
        lastError = Some(reason)
      case Right(PollStart.Finished(report, names, prompts)) =>
        lastError = None
        js.await(Async.traverse(Format.scheduledMessages(report, names, reportedFailureIds))(send(_, html)))
        js.await(Async.traverse(prompts)(prompt => send(Format.accountPrompt(prompt.accountKey, prompt.drafts), Commands.keyboard(prompt))))
        reportedFailureIds = report.failures.map(_.messageId).toSet
      case Right(skipped) => log.info(s"Skipped the scheduled poll: $skipped")
    }
  }
}
