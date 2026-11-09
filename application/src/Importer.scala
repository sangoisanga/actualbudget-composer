package composer.application

import composer.domain.{ImportVerdict, ImportedRow, LedgerEntry}

import scala.scalajs.js

final case class ImportResult(rows: List[ImportedRow], failedImports: Int)

object Importer {
  // One batch per account: the reconciler tracks matches inside a batch, so equal amounts in one poll do not shadow each other.
  def importAll(ledger: Ledger, entries: List[LedgerEntry], log: Log): js.Promise[ImportResult] = js.async {
    // Actual creates the receiving row of a transfer, so the receiving email in this poll must import after it to match that row.
    val batches = entries.groupBy(_.accountId).toList.sortBy((_, batch) => !batch.exists(_.draft.transferAccountId.isDefined))
    val results = js.await(Async.traverse(batches)((accountId, batch) => importBatch(ledger, accountId, batch, log)))
    ImportResult(results.flatMap(_.rows), results.map(_.failedImports).sum)
  }

  private def importBatch(ledger: Ledger, accountId: String, batch: List[LedgerEntry], log: Log): js.Promise[ImportResult] = js.async {
    try {
      val verdicts = js.await(ledger.importBatch(accountId, batch))
      val rows = batch.map(entry => ImportedRow(entry, verdicts.getOrElse(entry.draft.importedId, ImportVerdict.New)))
      ImportResult(rows, 0)
    } catch {
      case error: Throwable =>
        log.error(s"Failed to import batch for account $accountId: ${Async.messageOf(error)}")
        ImportResult(Nil, batch.size)
    }
  }
}
