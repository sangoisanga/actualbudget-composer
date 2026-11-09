package composer.application

import composer.domain.*

import java.time.LocalDate
import scala.collection.mutable.ListBuffer
import scala.scalajs.js

object Fakes {
  val cake = "no-reply@cake.vn"

  val htmlWithExtras =
    """<html><span class="amount-value">50.000 đ</span><span class="payee-name">Gong Cha Tea</span><span class="transaction-date">18/08/2026</span><span class="transaction-id">CAKE123</span></html>"""

  val htmlMinimal = """<html><span class="amount-value">20.000 đ</span><span class="payee-name">Grab</span></html>"""

  def rule(dateSelector: Option[String] = None, idSelector: Option[String] = None, accountSelector: Option[String] = None, accountMap: Map[String, String] = Map.empty, accountChoices: List[String] = Nil): EmailRule.Extract =
    EmailRule.Extract(".amount-value", ".payee-name", None, dateSelector, idSelector, None, AmountSign.Expense, accountSelector, accountMap, None, accountChoices)

  val rules: RuleMatrix = Map(cake -> List(rule(Some(".transaction-date"), Some(".transaction-id"))))

  def email(id: String, htmlBody: String = htmlWithExtras, sender: String = cake, subject: String = "Thông báo giao dịch", date: LocalDate = LocalDate.of(2026, 8, 18)): Email =
    Email(id, sender, subject, date, htmlBody)

  def rejected[A](message: String): js.Promise[A] = js.Promise.reject(js.Error(message)).asInstanceOf[js.Promise[A]]

  final class FakeMailbox(emails: List[Email], failure: Option[String] = None) extends Mailbox {
    var listCalls = 0
    def list(query: String): js.Promise[List[String]] = {
      listCalls += 1
      js.Promise.resolve(emails.map(_.id))
    }
    def get(id: String): js.Promise[Email] = failure.fold(js.Promise.resolve(emails.find(_.id == id).get))(rejected)
  }

  final class FakeLedger(respond: List[LedgerEntry] => js.Promise[Map[String, ImportVerdict]] = _ => js.Promise.resolve(Map.empty), names: Map[String, String] = Map.empty) extends Ledger {
    val batches = ListBuffer.empty[(String, List[LedgerEntry])]
    def categoryIds: js.Promise[Map[String, String]] = js.Promise.resolve(Map.empty)
    def accountNames: js.Promise[Map[String, String]] = js.Promise.resolve(names)
    def importBatch(accountId: String, entries: List[LedgerEntry]): js.Promise[Map[String, ImportVerdict]] = {
      batches += accountId -> entries
      respond(entries)
    }
  }

  final class FakeSession(ledger: Ledger) extends LedgerSession {
    var opens = 0
    var closes = 0
    def open(): js.Promise[Ledger] = {
      opens += 1
      js.Promise.resolve(ledger)
    }
    def close(): js.Promise[Unit] = {
      closes += 1
      js.Promise.resolve(())
    }
  }

  object NoLog extends Log {
    def debug(message: String, fields: (String, Any)*): Unit = ()
    def info(message: String, fields: (String, Any)*): Unit = ()
    def error(message: String, fields: (String, Any)*): Unit = ()
  }

  def settings(rules: RuleMatrix = rules, payees: List[PayeeMapping] = Nil, categoryIds: Map[String, String] = Map.empty, fallbackAccountId: Option[String] = Some("acct1")): CollectSettings =
    CollectSettings(rules, payees, categoryIds, fallbackAccountId, 7)
}
