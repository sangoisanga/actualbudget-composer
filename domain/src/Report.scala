package composer.domain

import java.time.LocalDate

enum ImportVerdict {
  case New, Updated, Duplicate
}

final case class EntryDraft(
    date: LocalDate,
    amount: Money,
    payee: String,
    importedPayee: String,
    importedId: String,
    notes: Option[String],
    categoryId: Option[String],
    transferAccountId: Option[String]
) {
  def assignTo(accountId: String): LedgerEntry = LedgerEntry(accountId, this)
}

final case class LedgerEntry(accountId: String, draft: EntryDraft)

final case class ImportedRow(entry: LedgerEntry, verdict: ImportVerdict)

final case class FailedEmail(messageId: String, sender: String, subject: String, date: Option[LocalDate], reason: String)

final case class PollReport(rows: List[ImportedRow], failures: List[FailedEmail], failedImports: Int, skipped: Int, scanned: Option[Int], unresolved: Int)
