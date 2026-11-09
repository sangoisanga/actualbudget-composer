package composer.application

import composer.domain.*

import java.time.Instant
import scala.concurrent.duration.*
import scala.scalajs.js
import scala.scalajs.js.timers.{SetTimeoutHandle, clearTimeout, setTimeout}

final case class PickPrompt(groupKey: String, accountKey: Option[String], drafts: List[EntryDraft], choices: List[(String, String)])

enum PollStart {
  case Busy
  case PicksPending
  case Finished(report: PollReport, accountNames: Map[String, String], prompts: List[PickPrompt])
}

enum AssignResult {
  case NoPending
  case AlreadyResolved
  case Imported(report: PollReport, accountNames: Map[String, String], accountKey: Option[String], count: Int)
}

final case class PendingSummary(count: Int, since: Instant)

private[application] enum PollState {
  case Idle
  case Polling
  case AwaitingPicks(ledger: Ledger, pending: List[PendingEntry], since: Instant, expiry: SetTimeoutHandle)
}

final case class PollConfig(rules: RuleMatrix, payees: List[PayeeMapping], pickTimeout: FiniteDuration = 15.minutes)

// JS runs one callback at a time, so each check and change of `state` before the first js.await cannot interleave.
final class PollService(session: LedgerSession, mailbox: Mailbox, config: PollConfig, log: Log, onExpired: Int => js.Promise[Unit]) {
  private var state: PollState = PollState.Idle

  def poll(lookbackDays: Int): js.Promise[PollStart] = state match {
    case PollState.Idle =>
      state = PollState.Polling
      runPoll(lookbackDays)
    case PollState.Polling          => js.Promise.resolve(PollStart.Busy)
    case _: PollState.AwaitingPicks => js.Promise.resolve(PollStart.PicksPending)
  }

  def assign(groupKey: String, accountId: String): js.Promise[AssignResult] = state match {
    case awaiting: PollState.AwaitingPicks =>
      val (group, rest) = awaiting.pending.partition(groupKeyOf(_) == groupKey)
      if group.isEmpty then js.Promise.resolve(AssignResult.AlreadyResolved)
      else if rest.nonEmpty then {
        state = awaiting.copy(pending = rest)
        importGroup(awaiting.ledger, group, accountId, rest.size)
      } else {
        state = PollState.Polling
        clearTimeout(awaiting.expiry)
        js.async {
          try js.await(importGroup(awaiting.ledger, group, accountId, 0))
          finally js.await(closeSession())
        }
      }
    case _ => js.Promise.resolve(AssignResult.NoPending)
  }

  def pending: Option[PendingSummary] = state match {
    case awaiting: PollState.AwaitingPicks => Some(PendingSummary(awaiting.pending.size, awaiting.since))
    case _                                 => None
  }

  def cancel(): js.Promise[Int] = state match {
    case awaiting: PollState.AwaitingPicks =>
      state = PollState.Polling
      clearTimeout(awaiting.expiry)
      js.async {
        js.await(closeSession())
        awaiting.pending.size
      }
    case _ => js.Promise.resolve(0)
  }

  def shutdown(): js.Promise[Unit] = state match {
    case PollState.Idle                    => js.Promise.resolve(())
    case awaiting: PollState.AwaitingPicks =>
      clearTimeout(awaiting.expiry)
      closeSession()
    case PollState.Polling => closeSession()
  }

  private def runPoll(lookbackDays: Int): js.Promise[PollStart] = js.async {
    try {
      val ledger = js.await(session.open())
      val categoryIds = js.await(ledger.categoryIds)
      val collected = js.await(Collector.collect(mailbox, CollectSettings(config.rules, config.payees, categoryIds, None, lookbackDays), log))
      val accountNames = js.await(ledger.accountNames)
      val imported = js.await(Importer.importAll(ledger, collected.ready, log))
      if collected.pending.isEmpty then js.await(closeSession()) else awaitPicks(ledger, collected.pending)
      val report = PollReport(imported.rows, collected.failures, imported.failedImports, collected.skipped, Some(collected.scanned), collected.pending.size)
      PollStart.Finished(report, accountNames, prompts(collected.pending, accountNames))
    } catch {
      case error: Throwable =>
        js.await(closeSession())
        throw error
    }
  }

  private def awaitPicks(ledger: Ledger, pending: List[PendingEntry]): Unit = {
    val expiry = setTimeout(config.pickTimeout)(Async.runAndLog(expire(), log, "Dropping the account picks"))
    state = PollState.AwaitingPicks(ledger, pending, Instant.ofEpochMilli(js.Date.now().toLong), expiry)
  }

  private def expire(): js.Promise[Unit] = state match {
    case awaiting: PollState.AwaitingPicks =>
      state = PollState.Polling
      js.async {
        js.await(closeSession())
        js.await(onExpired(awaiting.pending.size))
      }
    case _ => js.Promise.resolve(())
  }

  private def importGroup(ledger: Ledger, group: List[PendingEntry], accountId: String, remaining: Int): js.Promise[AssignResult] = js.async {
    val accountNames = js.await(ledger.accountNames)
    val imported = js.await(Importer.importAll(ledger, group.map(_.draft.assignTo(accountId)), log))
    AssignResult.Imported(PollReport(imported.rows, Nil, imported.failedImports, 0, None, remaining), accountNames, group.head.accountKey, group.size)
  }

  private def closeSession(): js.Promise[Unit] = js.async {
    try js.await(session.close())
    catch { case error: Throwable => log.error(s"Closing the budget failed: ${Async.messageOf(error)}") }
    finally state = PollState.Idle
  }

  private def prompts(pending: List[PendingEntry], accountNames: Map[String, String]): List[PickPrompt] =
    pending.groupBy(groupKeyOf).toList.map { (key, group) =>
      val choiceIds = if group.head.choices.nonEmpty then group.head.choices else accountNames.keys.toList
      PickPrompt(key, group.head.accountKey, group.map(_.draft), choiceIds.map(id => id -> accountNames.getOrElse(id, id)))
    }

  private def groupKeyOf(entry: PendingEntry): String = entry.accountKey.getOrElse("unknown")
}
