package composer.adapters

import composer.adapters.actual.ActualLedger
import composer.adapters.facade.PreviewEntry
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
  }
}
