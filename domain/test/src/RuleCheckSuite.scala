package composer.domain

import upickle.default.read
import utest.*

object RuleCheckSuite extends TestSuite {
  private val bank = "bank@example.com"
  private val payment = EmailRule.Extract(".amount", ".payee", None, None, None, None, AmountSign.Expense, None, Map.empty, None, Nil)
  private val config = RuleConfig(Map(bank -> List(EmailRule.Ignore(".skip"), payment)), List(PayeeMapping(List("grab"), "Grab")))
  private val paymentHtml = """<p class="amount">50.000 đ</p><p class="payee">GRAB VN</p>"""
  private val skipHtml = """<p class="skip">x</p>"""
  private val paymentSnapshot = FixtureSnapshot(bank, FixtureOutcome.Parsed, amount = Some("-50000"), payee = Some("Grab"), rawPayee = Some("GRAB VN"))
  private val stub = s"""{ "sender": "$bank", "outcome": "Parsed" }"""
  private val bothFixtures = Map("payment" -> paymentHtml, "skip" -> skipHtml)
  private val bothExpected = Map("payment" -> RuleCheck.render(paymentSnapshot), "skip" -> RuleCheck.render(FixtureSnapshot(bank, FixtureOutcome.Ignored)))

  val tests = Tests {
    test("snapshot maps the payee and keeps the raw payee") {
      RuleCheck.snapshot(config, paymentHtml, bank) ==> paymentSnapshot
    }

    test("snapshot JSON keeps every field") {
      read[FixtureSnapshot](RuleCheck.render(paymentSnapshot)) ==> paymentSnapshot
      assert(RuleCheck.render(paymentSnapshot).contains(""""raw_payee": "GRAB VN""""))
    }

    test("passes when each fixture matches and each rule is covered") {
      RuleCheck.check(config, bothFixtures, bothExpected) ==> Nil
    }

    test("reports a fixture with no expected file") {
      RuleCheck.check(config, bothFixtures + ("extra" -> skipHtml), bothExpected) ==> List("extra.html has no extra.expected.json")
    }

    test("reports an expected file with no fixture") {
      RuleCheck.check(config, bothFixtures, bothExpected + ("extra" -> stub)) ==> List("extra.expected.json has no extra.html")
    }

    test("reports a fixture that does not match its expected file") {
      RuleCheck.check(config, bothFixtures, bothExpected + ("payment" -> stub)) ==> List("payment.html does not match payment.expected.json. Run check-rules with --update and review the diff.")
    }

    test("reports a rule that no fixture matches first") {
      RuleCheck.check(config, bothFixtures - "skip", bothExpected - "skip") ==> List(s"$bank rule 0 has no fixture that it matches first")
    }

    test("update rewrites the expected file from the parse") {
      RuleCheck.update(config, bothFixtures, Map("payment" -> stub)) ==> Map("payment" -> RuleCheck.render(paymentSnapshot))
    }

    test("the example config passes") {
      RuleCheck.check(Fixtures.exampleConfig, Fixtures.examplesBySuffix(".html"), Fixtures.examplesBySuffix(".expected.json")) ==> Nil
    }
  }
}
