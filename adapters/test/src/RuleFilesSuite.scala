package composer.adapters

import composer.adapters.facade.{NodeFs, tmpdir}
import composer.domain.{EmailRule, PayeeMapping}
import utest.*

import scala.scalajs.js

object RuleFilesSuite extends TestSuite {
  private def withDir[A](body: String => A): A = {
    val dir = NodeFs.mkdtempSync(s"${tmpdir()}/rule-files-")
    try body(dir)
    finally NodeFs.rmSync(dir, js.Dynamic.literal(recursive = true, force = true))
  }

  val tests = Tests {
    test("loads rules and payees from the folder") {
      withDir { dir =>
        NodeFs.writeFileSync(s"$dir/rules.json", """{ "bank@example.com": [{ "ignore": true, "match_selector": "p" }] }""")
        NodeFs.writeFileSync(s"$dir/payees.json", """[{ "match": ["grab"], "payee": "Grab" }]""")
        val loaded = RuleFiles.load(dir)
        loaded.rules ==> Map("bank@example.com" -> List(EmailRule.Ignore("p")))
        loaded.payees ==> List(PayeeMapping(List("grab"), "Grab"))
      }
    }

    test("pairs fixtures and expected files by name") {
      withDir { dir =>
        NodeFs.mkdirSync(s"$dir/fixtures", js.Dynamic.literal(recursive = true))
        NodeFs.writeFileSync(s"$dir/fixtures/bank-transfer.html", "<p></p>")
        RuleFiles.writeExpected(dir, "bank-transfer", "{}")
        RuleFiles.fixtures(dir) ==> FixtureFiles(Map("bank-transfer" -> "<p></p>"), Map("bank-transfer" -> "{}"))
      }
    }
  }
}
