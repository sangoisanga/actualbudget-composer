package composer.domain

import utest.*

import java.time.LocalDate

object BankEmailSuite extends TestSuite {
  private val cake = "no-reply@cake.vn"

  private def extract(dateSelector: Option[String] = None, idSelector: Option[String] = None, notesSelector: Option[String] = None, accountSelector: Option[String] = None, accountMap: Map[String, String] = Map.empty, transferSelector: Option[String] = None, transferMap: Map[String, String] = Map.empty, payeeDefault: Option[String] = None) =
    EmailRule.Extract(".amount-value", ".payee-name", payeeDefault, dateSelector, idSelector, notesSelector, AmountSign.Expense, accountSelector, accountMap, None, Nil, transferSelector, transferMap)

  private val transferHtml = """<html><span class="amount-value">3,000,000 VND</span><span class="payee-name">NGUYEN VAN AN</span><span class="credit-account">0900000002</span></html>"""

  private val fullRule = extract(Some(".transaction-date"), Some(".transaction-id"), Some(".transaction-notes"), Some(".account-id"), Map("••1111" -> "acct-card"))
  private val cakeRules: RuleMatrix = Map(cake -> List(fullRule))
  private val withIgnoreRule: RuleMatrix = Map(cake -> List(EmailRule.Ignore(".rollover"), fullRule))
  private lazy val notification = Fixtures.html("notification.html")

  private def parsed(rules: RuleMatrix, html: String): ParsedEmail = BankEmail.parse(rules, html, cake) match {
    case ParseResult.Parsed(email) => email
    case other                     => throw java.lang.AssertionError(s"expected Parsed, got $other")
  }

  val tests = Tests {
    test("negates the amount for an expense rule and maps the account") {
      val email = parsed(cakeRules, notification)
      email.amount ==> Money.fromMajorUnits(-50000)
      email.payee ==> "Gong Cha Tea"
      email.account ==> AccountRef.Known("acct-card")
    }

    test("reads the date, reference, and notes") {
      val email = parsed(cakeRules, notification)
      email.date ==> Some(LocalDate.of(2026, 8, 18))
      email.reference ==> Some("CAKE1234567890")
      email.notes ==> Some("Card payment at Gong Cha")
    }

    test("leaves optional fields empty when the rule has no selectors for them") {
      val email = parsed(Map(cake -> List(extract())), notification)
      (email.amount, email.payee) ==> (Money.fromMajorUnits(-50000), "Gong Cha Tea")
      (email.date, email.reference, email.notes, email.accountKey) ==> (None, None, None, None)
      email.account ==> AccountRef.Unknown(None, Nil)
      email.transferAccountId ==> None
    }

    test("maps the credit account to a transfer account") {
      val rule = extract(transferSelector = Some(".credit-account"), transferMap = Map("0900000002" -> "acct-cake"))
      parsed(Map(cake -> List(rule)), transferHtml).transferAccountId ==> Some("acct-cake")
    }

    test("uses the payee default when the bank writes n/a") {
      val html = """<html><span class="amount-value">200.000 đ</span><span class="payee-name">n/a</span><span class="account-id">N/A</span></html>"""
      val rule = extract(accountSelector = Some(".account-id"), payeeDefault = Some("Chuyển tiền đến"))
      val email = parsed(Map(cake -> List(rule)), html)
      (email.payee, email.accountKey) ==> ("Chuyển tiền đến", None)
    }

    test("skips a rule when the payee is n/a and the rule has no default") {
      val html = """<html><span class="amount-value">200.000 đ</span><span class="payee-name">n/a</span></html>"""
      BankEmail.parse(Map(cake -> List(extract())), html, cake) ==> ParseResult.Unrecognized(s"No rule matched this email from $cake")
    }

    test("leaves the transfer account empty when the credit account has no mapping") {
      val rule = extract(transferSelector = Some(".credit-account"), transferMap = Map("0900000009" -> "acct-cake"))
      parsed(Map(cake -> List(rule)), transferHtml).transferAccountId ==> None
    }

    test("parses an ISO date") {
      val html = """<html><span class="amount-value">50.000 đ</span><span class="payee-name">Gong Cha Tea</span><span class="transaction-date">2026-08-18T09:00:00</span></html>"""
      parsed(cakeRules, html).date ==> Some(LocalDate.of(2026, 8, 18))
    }

    test("reports an unmapped sender") {
      BankEmail.parse(cakeRules, "<html></html>", "unknown@bank.com") ==> ParseResult.Unrecognized("Unmapped sender: unknown@bank.com")
    }

    test("reports an email that no rule matches") {
      BankEmail.parse(cakeRules, """<html><div class="wrong">nope</div></html>""", cake) ==> ParseResult.Unrecognized(s"No rule matched this email from $cake")
    }

    test("ignores an email that an ignore rule matches") {
      BankEmail.parse(withIgnoreRule, """<html><span class="rollover">x</span></html>""", cake) ==> ParseResult.Ignored(0)
    }

    test("tries the next rule when the ignore selector misses") {
      parsed(withIgnoreRule, notification).payee ==> "Gong Cha Tea"
    }

    test("decodes snake_case rule keys") {
      val json = s"""{"$cake": [{"amount_selector": ".amount-value", "payee_selector": ".payee-name", "amount_sign": "explicit", "account_map": {"••1111": "acct-card"}, "transfer_selector": ".credit-account", "transfer_map": {"0900000002": "acct-cake"}}]}"""
      val expected = EmailRule.Extract(".amount-value", ".payee-name", None, None, None, None, AmountSign.Explicit, None, Map("••1111" -> "acct-card"), None, Nil, Some(".credit-account"), Map("0900000002" -> "acct-cake"))
      BankEmail.decodeRules(json) ==> Map(cake -> List(expected))
    }

    test("rejects a camelCase rule key by its snake_case name") {
      val json = s"""{"$cake": [{"amountSelector": ".amount-value", "payee_selector": ".payee-name"}]}"""
      assertThrows[IllegalArgumentException](BankEmail.decodeRules(json)).getMessage ==> s"A rule for $cake has no amount_selector"
    }
  }
}
