package composer.telegram

import composer.adapters.schedule.CronJob
import composer.application.{Async, Log, PollService, PollStart}

import java.time.Instant
import scala.scalajs.js

final class ScheduledPoll(service: PollService, send: (String, js.UndefOr[js.Object]) => js.Promise[Unit], lookbackDays: Int, log: Log, initialCron: Option[String]) {
  private val html = js.Dynamic.literal(parse_mode = "HTML")
  private var cron = initialCron
  private var job: Option[CronJob] = None
  private var reportedFailureIds = Set.empty[String]
  private var lastError: Option[String] = None

  def current: Option[String] = cron

  def nextRunAt: Option[Instant] = job.flatMap(_.nextRun)

  def start(): Unit = job = cron.map(CronJob.start(_, () => tickAndLog()))

  def restart(next: Option[String]): Unit = {
    stop()
    cron = next
    start()
  }

  def stop(): Unit = {
    job.foreach(_.stop())
    job = None
  }

  // croner waits on this promise to block an overlapping run, so it must never reject.
  private def tickAndLog(): js.Promise[Unit] = js.async {
    try js.await(tick())
    catch { case error: Throwable => log.error(s"Scheduled poll failed: ${Async.messageOf(error)}") }
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
