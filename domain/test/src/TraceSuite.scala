package composer.domain

import utest.*

object TraceSuite extends TestSuite {
  private lazy val rules = BankEmail.decodeRules(Fixtures.read("examples/rules.json"))

  val tests = Tests {
    test("shows the ignore rule that matches a declined card email") {
      val traces = BankEmail.trace(rules, Fixtures.example("cake-card-declined.html"), "no-reply@cake.vn")
      assert(traces.filter(_.isIgnoreRule).exists(_.selectors.head.text != "0 match(es)"))
    }

    test("shows the amount text that a VPBank rule finds") {
      val traces = BankEmail.trace(rules, Fixtures.example("vpbank-transfer.html"), "vpbankonline@vpb.com.vn")
      assert(traces.flatMap(_.selectors).exists(selector => selector.field == "amount_selector" && selector.text.nonEmpty))
    }

    test("returns no traces for an unknown sender") {
      BankEmail.trace(rules, "<p></p>", "unknown@example.com") ==> Nil
    }
  }
}
