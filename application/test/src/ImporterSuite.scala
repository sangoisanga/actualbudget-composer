package composer.application

import composer.application.Fakes.*
import composer.domain.*
import utest.*

import java.time.LocalDate
import scala.scalajs.js

object ImporterSuite extends AsyncSuite {
  private def entry(importedId: String, accountId: String = "acct1", transferAccountId: Option[String] = None) =
    EntryDraft(LocalDate.of(2026, 8, 18), Money.fromMinorUnits(-5000000), "Gong Cha Tea", "Gong Cha Tea", importedId, None, None, transferAccountId).assignTo(accountId)

  private def importAll(ledger: FakeLedger, entries: List[LedgerEntry]) = Importer.importAll(ledger, entries, NoLog)

  val tests = Tests {
    test("tags a row as new when Actual adds it") {
      importAll(FakeLedger(), List(entry("CAKE123"))).map { result =>
        result ==> ImportResult(List(ImportedRow(entry("CAKE123"), ImportVerdict.New)), 0)
      }
    }

    test("tags a row as updated when Actual matches an existing imported ID") {
      importAll(FakeLedger(_ => js.Promise.resolve(Map("CAKE123" -> ImportVerdict.Updated))), List(entry("CAKE123"))).map { result =>
        result.rows.map(_.verdict) ==> List(ImportVerdict.Updated)
      }
    }

    test("tags each row with its own verdict") {
      importAll(FakeLedger(_ => js.Promise.resolve(Map("b" -> ImportVerdict.Duplicate))), List(entry("CAKE123"), entry("b"))).map { result =>
        result.rows.map(row => row.entry.draft.importedId -> row.verdict) ==> List("CAKE123" -> ImportVerdict.New, "b" -> ImportVerdict.Duplicate)
      }
    }

    // The reconciler tracks matches inside one batch, so equal transactions must share one call.
    test("imports each account in one batch") {
      val ledger = FakeLedger()
      importAll(ledger, List(entry("a"), entry("b"), entry("c", "acct2"))).map { _ =>
        ledger.batches.toList.map((accountId, entries) => accountId -> entries.size).sorted ==> List("acct1" -> 2, "acct2" -> 1)
      }
    }

    test("imports a batch with a transfer before the receiving account") {
      val ledger = FakeLedger()
      importAll(ledger, List(entry("in", "acct-cake"), entry("out", "acct-vpbank", Some("acct-cake")))).map { _ =>
        ledger.batches.toList.map(_._1) ==> List("acct-vpbank", "acct-cake")
      }
    }

    test("counts a failed batch and keeps its rows out of the report") {
      importAll(FakeLedger(_ => rejected("amount must be an integer")), List(entry("a"))).map { result =>
        result ==> ImportResult(Nil, 1)
      }
    }

    test("imports a held-back entry after an account is picked") {
      importAll(FakeLedger(), List(entry("a").draft.assignTo("acct-a"))).map(result => result.rows.map(_.entry.accountId) ==> List("acct-a"))
    }
  }
}
