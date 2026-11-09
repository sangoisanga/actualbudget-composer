package composer.telegram

import composer.domain.*

import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDate}
import scala.scalajs.js

object Format {
  private val PayeeWidth = 22
  private val NoteWidth = 24
  private val SubjectWidth = 38
  private val ReasonWidth = 52
  private val TelegramLimit = 4096
  private val ClockFormat = DateTimeFormatter.ofPattern("HH:mm dd/MM")

  private val VerdictTags = Map(ImportVerdict.New -> "[N]", ImportVerdict.Updated -> "[U]", ImportVerdict.Duplicate -> "[=]")

  def escapeHtml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  def vnd(amount: Money): String = s"${js.Dynamic.global.Number(amount.minorUnits.toDouble / 100).toLocaleString("vi-VN")} ₫"

  private def truncate(value: String, width: Int): String = if value.length > width then s"${value.take(width - 1).reverse.dropWhile(_.isWhitespace).reverse}…" else value

  private def shortDate(date: LocalDate): String = f"${date.getDayOfMonth}%02d/${date.getMonthValue}%02d"

  def transactionLine(draft: EntryDraft, verdict: ImportVerdict = ImportVerdict.New): String = {
    val payee = truncate(draft.payee, PayeeWidth).padTo(PayeeWidth, ' ')
    val amount = vnd(draft.amount).reverse.padTo(12, ' ').reverse
    val notes = draft.notes.fold("")(note => s"  · ${truncate(note, NoteWidth)}")
    s"${VerdictTags(verdict)} ${shortDate(draft.date)}  $payee  $amount$notes"
  }

  def accountBreakdown(entries: List[LedgerEntry], accountNames: Map[String, String]): String = {
    val names = entries.map(entry => accountNames.getOrElse(entry.accountId, entry.accountId))
    names.distinct.map(name => s"$name · ${names.count(_ == name)}").mkString("  |  ")
  }

  // The link opens the email that broke, and the subject says which template it was.
  def failureLine(failure: FailedEmail): String = {
    val date = failure.date.fold("")(day => s"${shortDate(day)} ")
    val label = escapeHtml(truncate(if failure.subject.nonEmpty then failure.subject else if failure.sender.nonEmpty then failure.sender else "Unknown", SubjectWidth))
    val reason = escapeHtml(truncate(failure.reason, ReasonWidth))
    s"""· $date<a href="https://mail.google.com/mail/u/0/#all/${failure.messageId}">$label</a> — $reason"""
  }

  def fixCommand(failures: List[FailedEmail]): String = s"<code>/fix-mail ${failures.map(_.messageId).mkString(" ")}</code>"

  def schedule(cron: Option[String], nextRun: Option[Instant]): String = (cron, nextRun) match {
    case (Some(cron), Some(at)) => s"Scheduled poll runs on $cron (UTC+7). Next poll at ${at.atZone(BankZone).format(ClockFormat)}."
    case _                      => "Scheduled poll is off. Send /cron 0 */6 * * * to set it."
  }

  def accountPrompt(accountKey: Option[String], drafts: List[EntryDraft]): String = {
    val reason = accountKey.fold("The email does not show which account paid.")(key => s"The email shows ${escapeHtml(key)}, which has no account.")
    s"<b>Pick an account for ${drafts.size} transaction(s).</b>\n$reason\n${pre(drafts.map(draft => escapeHtml(transactionLine(draft))))}"
  }

  def accountPick(accountKey: Option[String], accountName: String, count: Int): String =
    s"Assigned $count transaction(s) to $accountName (${accountKey.fold("email showed no account")(key => s"email showed $key")})."

  def pollMessages(report: PollReport, accountNames: Map[String, String]): List[String] = {
    val sorted = report.rows.sortBy(_.entry.draft.date)(using Ordering[LocalDate].reverse)
    val header = buildHeader(report, sorted)
    val footer = buildFooter(report, accountNames)
    val lines = sorted.map(row => escapeHtml(transactionLine(row.entry.draft, row.verdict)))
    if lines.isEmpty then List(List(header, footer).filter(_.nonEmpty).mkString("\n"))
    else {
      val chunks = chunk(lines, TelegramLimit - header.length - footer.length - pre(Nil).length - 8)
      chunks.zipWithIndex.map { (lines, index) =>
        val withHeader = if index == 0 then s"$header\n${pre(lines)}" else pre(lines)
        if index == chunks.size - 1 && footer.nonEmpty then s"$withHeader\n$footer" else withHeader
      }
    }
  }

  def scheduledMessages(report: PollReport, accountNames: Map[String, String], reportedFailureIds: Set[String]): List[String] = {
    val fresh = report.copy(
      rows = report.rows.filterNot(_.verdict == ImportVerdict.Duplicate),
      failures = report.failures.filterNot(failure => reportedFailureIds.contains(failure.messageId))
    )
    val isQuiet = fresh.rows.isEmpty && fresh.failures.isEmpty && fresh.unresolved == 0 && fresh.failedImports == 0
    if isQuiet then Nil else pollMessages(fresh, accountNames)
  }

  private def pre(lines: List[String]): String = s"<pre>\n${lines.mkString("\n")}\n</pre>"

  private def chunk(lines: List[String], budget: Int): List[List[String]] =
    lines
      .foldLeft(List.empty[(List[String], Int)]) {
        case ((current, size) :: done, line) if size + line.length + 1 <= budget => (line :: current, size + line.length + 1) :: done
        case (done, line)                                                        => (List(line), line.length + 1) :: done
      }
      .reverse
      .map((lines, _) => lines.reverse)

  // A full poll shows every email bucket, zeros included, so the counts add up to the scanned total.
  private def buildHeader(report: PollReport, rows: List[ImportedRow]): String = {
    val total = rows.foldLeft(Money.zero)(_ + _.entry.draft.amount)
    val totals = s"<b>${rows.size}</b> transaction(s) · total <b>${vnd(total)}</b>"
    report.scanned.fold(totals) { scanned =>
      val buckets = s"<b>${report.skipped}</b> skipped · <b>${report.unresolved}</b> awaiting an account · <b>${report.failures.size}</b> failed"
      s"<b>$scanned</b> email(s) scanned\n$totals\n$buckets"
    }
  }

  private def buildFooter(report: PollReport, accountNames: Map[String, String]): String = {
    val verdictCounts = List(ImportVerdict.New -> "new", ImportVerdict.Updated -> "updated", ImportVerdict.Duplicate -> "duplicate").flatMap { (verdict, label) =>
      val count = report.rows.count(_.verdict == verdict)
      Option.when(count > 0)(s"$count $label")
    }
    val failures = Option.when(report.failures.nonEmpty)((s"<b>${report.failures.size} failed to parse</b>" :: report.failures.map(failureLine)) :+ fixCommand(report.failures))
    List(
      verdictCounts.mkString(" · "),
      escapeHtml(accountBreakdown(report.rows.map(_.entry), accountNames)),
      if report.scanned.isEmpty && report.unresolved > 0 then s"${report.unresolved} awaiting an account" else "",
      if report.failedImports > 0 then s"<b>${report.failedImports} failed to import</b>. See the log." else "",
      failures.fold("")(_.mkString("\n"))
    ).filter(_.nonEmpty).mkString("\n")
  }
}
