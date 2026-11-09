package composer.application

import composer.domain.{ImportVerdict, LedgerEntry}

import java.time.LocalDate
import scala.scalajs.js

final case class Email(id: String, sender: String, subject: String, date: LocalDate, htmlBody: String)

trait Mailbox {
  def list(query: String): js.Promise[List[String]]
  def get(id: String): js.Promise[Email]
}

trait Ledger {
  def categoryIds: js.Promise[Map[String, String]]
  def accountNames: js.Promise[Map[String, String]]
  // Rejects when Actual reports errors. Verdicts are keyed by imported ID.
  def importBatch(accountId: String, entries: List[LedgerEntry]): js.Promise[Map[String, ImportVerdict]]
}

trait LedgerSession {
  def open(): js.Promise[Ledger]
  def close(): js.Promise[Unit]
}

trait Log {
  def debug(message: String, fields: (String, Any)*): Unit
  def info(message: String, fields: (String, Any)*): Unit
  def error(message: String, fields: (String, Any)*): Unit
}
