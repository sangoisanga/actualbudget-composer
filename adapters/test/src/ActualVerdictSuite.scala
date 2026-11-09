package composer.adapters

import composer.adapters.actual.ActualLedger
import composer.adapters.facade.{Payee, PreviewEntry}
import composer.domain.ImportVerdict
import utest.*

import scala.scalajs.js

object ActualVerdictSuite extends TestSuite {
  private def preview(importedId: String, existing: Boolean = false, ignored: Boolean = false): PreviewEntry = {
    val entry = js.Dynamic.literal(transaction = js.Dynamic.literal(imported_id = importedId), ignored = ignored)
    if existing then entry.existing = js.Object()
    entry.asInstanceOf[PreviewEntry]
  }

  val tests = Tests {
    test("maps updatedPreview entries to verdicts by imported ID") {
      val verdicts = ActualLedger.verdictsOf(List(preview("b", ignored = true), preview("c", existing = true), preview("d")))
      verdicts ==> Map("b" -> ImportVerdict.Duplicate, "c" -> ImportVerdict.Updated, "d" -> ImportVerdict.New)
    }

    test("maps each account to its transfer payee and skips plain payees") {
      val payees = List(
        js.Dynamic.literal(id = "payee-cake", transfer_acct = "acct-cake"),
        js.Dynamic.literal(id = "payee-grab", transfer_acct = null),
        js.Dynamic.literal(id = "payee-be")
      ).map(_.asInstanceOf[Payee])
      ActualLedger.transferPayeesOf(payees) ==> Map("acct-cake" -> "payee-cake")
    }
  }
}
