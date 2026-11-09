package composer.application

import composer.domain.*

import scala.scalajs.js

final case class PendingEntry(messageId: String, draft: EntryDraft, accountKey: Option[String], choices: List[String], sender: String, ruleIndex: Int)

final case class Collected(ready: List[LedgerEntry], pending: List[PendingEntry], failures: List[FailedEmail], skipped: Int, scanned: Int)

final case class CollectSettings(rules: RuleMatrix, payees: List[PayeeMapping], categoryIds: Map[String, String], fallbackAccountId: Option[String], lookbackDays: Int)

object Collector {
  private enum Outcome {
    case Ready(entry: LedgerEntry)
    case Pending(entry: PendingEntry)
    case Failed(failure: FailedEmail)
    case Skipped
  }

  def query(senders: Iterable[String], lookbackDays: Int): Option[String] =
    Option.when(senders.nonEmpty)(s"(${senders.map(sender => s"from:($sender)").mkString(" OR ")}) newer_than:${lookbackDays}d")

  def collect(mailbox: Mailbox, settings: CollectSettings, log: Log): js.Promise[Collected] = js.async {
    query(settings.rules.keys, settings.lookbackDays) match {
      case None =>
        log.info("No senders configured in rules. Skipping the poll.")
        Collected(Nil, Nil, Nil, 0, 0)
      case Some(gmailQuery) =>
        val ids = js.await(mailbox.list(gmailQuery))
        val outcomes = js.await(Async.traverse(ids)(id => classify(mailbox, settings, id, log)))
        Collected(
          ready = outcomes.collect { case Outcome.Ready(entry) => entry },
          pending = outcomes.collect { case Outcome.Pending(entry) => entry },
          failures = outcomes.collect { case Outcome.Failed(failure) => failure },
          skipped = outcomes.count(_ == Outcome.Skipped),
          scanned = ids.size
        )
    }
  }

  private def classify(mailbox: Mailbox, settings: CollectSettings, id: String, log: Log): js.Promise[Outcome] = js.async {
    val outcome =
      try parseOutcome(js.await(mailbox.get(id)), id, settings)
      catch { case error: Throwable => Outcome.Failed(FailedEmail(id, "", "", None, Async.messageOf(error))) }
    outcome match {
      case Outcome.Failed(failure) => log.error(s"Failed to process message $id: ${failure.reason}")
      case _                       => ()
    }
    outcome
  }

  private def parseOutcome(email: Email, id: String, settings: CollectSettings): Outcome = BankEmail.parse(settings.rules, email.htmlBody, email.sender) match {
    case ParseResult.Ignored(_)           => Outcome.Skipped
    case ParseResult.Unrecognized(reason) => Outcome.Failed(FailedEmail(id, email.sender, email.subject, Some(email.date), reason))
    case ParseResult.Parsed(parsed)       => route(id, PayeeMap(settings.payees, parsed), email, settings)
  }

  private def route(id: String, parsed: ParsedEmail, email: Email, settings: CollectSettings): Outcome = {
    val draft = EntryDraft(
      date = parsed.date.getOrElse(email.date),
      amount = parsed.amount,
      payee = parsed.payee,
      importedPayee = parsed.rawPayee,
      importedId = parsed.reference.getOrElse(id),
      notes = parsed.notes,
      categoryId = parsed.categoryName.flatMap(settings.categoryIds.get),
      transferAccountId = parsed.transferAccountId
    )
    (parsed.account, settings.fallbackAccountId) match {
      case (AccountRef.Known(accountId), _)           => Outcome.Ready(draft.assignTo(accountId))
      case (AccountRef.Unknown(_, _), Some(fallback)) => Outcome.Ready(draft.assignTo(fallback))
      case (AccountRef.Unknown(key, choices), None)   => Outcome.Pending(PendingEntry(id, draft, key, choices, parsed.sender, parsed.ruleIndex))
    }
  }
}
