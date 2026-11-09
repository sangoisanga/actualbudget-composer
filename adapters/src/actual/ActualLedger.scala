package composer.adapters.actual

import composer.adapters.facade.{ActualApi, NodeFs, PreviewEntry}
import composer.application.{Ledger, LedgerSession, Log}
import composer.domain.{ImportVerdict, LedgerEntry}

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

final case class ActualConfig(dataDir: String, serverUrl: String, password: String, syncId: String, isVerbose: Boolean)

object ActualLedger extends Ledger {
  def categoryIds: js.Promise[Map[String, String]] = js.async(js.await(ActualApi.getCategories()).map(category => category.name -> category.id).toMap)

  def accountNames: js.Promise[Map[String, String]] = js.async(js.await(ActualApi.getAccounts()).map(account => account.id -> account.name).toMap)

  def importBatch(accountId: String, entries: List[LedgerEntry]): js.Promise[Map[String, ImportVerdict]] = js.async {
    val response = js.await(ActualApi.importTransactions(accountId, entries.map(toTransaction).toJSArray))
    val errors = response.errors.getOrElse(js.Array()).map(_.message)
    if errors.nonEmpty then throw RuntimeException(errors.mkString("; "))
    verdictsOf(response.updatedPreview.getOrElse(js.Array()).toList)
  }

  // A batch import returns generated IDs in added and updated, so the verdict for each row comes from updatedPreview.
  def verdictsOf(preview: List[PreviewEntry]): Map[String, ImportVerdict] =
    preview.flatMap { entry =>
      val verdict = if entry.ignored.contains(true) then ImportVerdict.Duplicate else if entry.existing.isDefined then ImportVerdict.Updated else ImportVerdict.New
      entry.transaction.imported_id.toOption.map(_ -> verdict)
    }.toMap

  private def toTransaction(entry: LedgerEntry): js.Object = {
    val draft = entry.draft
    js.Dynamic.literal(
      account = entry.accountId,
      date = draft.date.toString,
      amount = draft.amount.minorUnits.toDouble,
      payee_name = draft.payee,
      imported_payee = draft.importedPayee,
      imported_id = draft.importedId,
      cleared = true,
      notes = draft.notes.orUndefined,
      category = draft.categoryId.orUndefined
    )
  }
}

final class ActualSession(config: ActualConfig) extends LedgerSession {
  def open(): js.Promise[Ledger] = js.async {
    NodeFs.mkdirSync(config.dataDir, js.Dynamic.literal(recursive = true))
    js.await(ActualApi.init(js.Dynamic.literal(dataDir = config.dataDir, serverURL = config.serverUrl, password = config.password, verbose = config.isVerbose)))
    js.await(ActualApi.downloadBudget(config.syncId))
    ActualLedger
  }

  def close(): js.Promise[Unit] = ActualApi.shutdown()
}

final class DryRunLedger(log: Log) extends Ledger {
  def categoryIds: js.Promise[Map[String, String]] = js.Promise.resolve(Map.empty)
  def accountNames: js.Promise[Map[String, String]] = js.Promise.resolve(Map.empty)
  def importBatch(accountId: String, entries: List[LedgerEntry]): js.Promise[Map[String, ImportVerdict]] = {
    entries.foreach(entry => log.info("Would import", "account_id" -> accountId, "entry" -> entry.toString))
    js.Promise.resolve(Map.empty)
  }
}
