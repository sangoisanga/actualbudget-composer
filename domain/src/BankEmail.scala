package composer.domain

import composer.domain.html.{CheerioDocument, loadHtml}
import upickle.default.{ReadWriter, read}
import upickle.implicits.key

import java.time.{Instant, LocalDate, ZoneId, ZoneOffset}
import scala.scalajs.js

// Vietnam has no daylight saving time, so a fixed offset avoids the full time zone database.
val BankZone: ZoneId = ZoneOffset.ofHours(7)

enum AmountSign {
  case Expense, Explicit
}

enum EmailRule {
  case Ignore(matchSelector: String)
  case Extract(
      amountSelector: String,
      payeeSelector: String,
      payeeDefault: Option[String],
      dateSelector: Option[String],
      idSelector: Option[String],
      notesSelector: Option[String],
      amountSign: AmountSign,
      accountSelector: Option[String],
      accountMap: Map[String, String],
      accountDefault: Option[String],
      accountChoices: List[String],
      transferSelector: Option[String],
      transferMap: Map[String, String]
  )
}

type RuleMatrix = Map[String, List[EmailRule]]

enum AccountRef {
  case Known(accountId: String)
  case Unknown(accountKey: Option[String], choices: List[String])
}

final case class ParsedEmail(
    amount: Money,
    payee: String,
    rawPayee: String,
    date: Option[LocalDate],
    reference: Option[String],
    notes: Option[String],
    categoryName: Option[String],
    account: AccountRef,
    accountKey: Option[String],
    transferAccountId: Option[String],
    sender: String,
    ruleIndex: Int
)

enum ParseResult {
  case Parsed(email: ParsedEmail)
  case Ignored(ruleIndex: Int)
  case Unrecognized(reason: String)
}

final case class SelectorTrace(field: String, selector: String, text: String)
final case class RuleTrace(index: Int, isIgnoreRule: Boolean, selectors: List[SelectorTrace])

private final case class RuleJson(
    ignore: Boolean = false,
    @key("match_selector") matchSelector: Option[String] = None,
    @key("amount_selector") amountSelector: Option[String] = None,
    @key("payee_selector") payeeSelector: Option[String] = None,
    @key("payee_default") payeeDefault: Option[String] = None,
    @key("date_selector") dateSelector: Option[String] = None,
    @key("id_selector") idSelector: Option[String] = None,
    @key("notes_selector") notesSelector: Option[String] = None,
    @key("amount_sign") amountSign: String = "expense",
    @key("account_selector") accountSelector: Option[String] = None,
    @key("account_map") accountMap: Map[String, String] = Map.empty,
    @key("account_default") accountDefault: Option[String] = None,
    @key("account_choices") accountChoices: List[String] = Nil,
    @key("transfer_selector") transferSelector: Option[String] = None,
    @key("transfer_map") transferMap: Map[String, String] = Map.empty
) derives ReadWriter

object BankEmail {

  /** Throws on an invalid file: the rules are config, so the process must not start without them. */
  def decodeRules(json: String): RuleMatrix = read[Map[String, List[RuleJson]]](json).map((sender, rules) => sender -> rules.map(toRule(sender, _)))

  private def toRule(sender: String, rule: RuleJson): EmailRule = {
    def required(field: String, value: Option[String]): String = value.getOrElse(throw IllegalArgumentException(s"A rule for $sender has no $field"))
    if rule.ignore then EmailRule.Ignore(required("match_selector", rule.matchSelector))
    else {
      val sign = rule.amountSign match {
        case "expense"  => AmountSign.Expense
        case "explicit" => AmountSign.Explicit
        case other      => throw IllegalArgumentException(s"A rule for $sender has an unknown amount_sign: $other")
      }
      EmailRule.Extract(
        required("amount_selector", rule.amountSelector),
        required("payee_selector", rule.payeeSelector),
        rule.payeeDefault,
        rule.dateSelector,
        rule.idSelector,
        rule.notesSelector,
        sign,
        rule.accountSelector,
        rule.accountMap,
        rule.accountDefault,
        rule.accountChoices,
        rule.transferSelector,
        rule.transferMap
      )
    }
  }

  def parse(rules: RuleMatrix, html: String, sender: String): ParseResult = rules.get(sender).filter(_.nonEmpty) match {
    case None           => ParseResult.Unrecognized(s"Unmapped sender: $sender")
    case Some(ruleList) =>
      val document = loadHtml(html)
      val outcomes = ruleList.iterator.zipWithIndex.map((rule, index) => applyRule(document, rule, index, sender))
      outcomes.collectFirst { case Some(result) => result }.getOrElse(ParseResult.Unrecognized(s"No rule matched this email from $sender"))
  }

  def trace(rules: RuleMatrix, html: String, sender: String): List[RuleTrace] = {
    val document = loadHtml(html)
    rules.getOrElse(sender, Nil).zipWithIndex.map {
      case (EmailRule.Ignore(selector), index) =>
        RuleTrace(index, isIgnoreRule = true, List(SelectorTrace("match_selector", selector, s"${document(selector).length} match(es)")))
      case (rule: EmailRule.Extract, index) =>
        val selectors = selectorFields(rule).map((field, selector) => SelectorTrace(field, selector, textOf(document, selector)))
        RuleTrace(index, isIgnoreRule = false, selectors)
    }
  }

  private def selectorFields(rule: EmailRule.Extract): List[(String, String)] =
    List(
      "amount_selector" -> Some(rule.amountSelector),
      "payee_selector" -> Some(rule.payeeSelector),
      "date_selector" -> rule.dateSelector,
      "id_selector" -> rule.idSelector,
      "notes_selector" -> rule.notesSelector,
      "account_selector" -> rule.accountSelector,
      "transfer_selector" -> rule.transferSelector
    ).collect { case (field, Some(selector)) => field -> selector }

  private def applyRule(document: CheerioDocument, rule: EmailRule, index: Int, sender: String): Option[ParseResult] = rule match {
    case EmailRule.Ignore(selector) => Option.when(document(selector).length > 0)(ParseResult.Ignored(index))
    case extract: EmailRule.Extract =>
      for {
        rawAmount <- nonEmptyText(document, Some(extract.amountSelector))
        payee <- nonEmptyText(document, Some(extract.payeeSelector)).orElse(extract.payeeDefault)
      } yield ParseResult.Parsed(buildEmail(document, extract, index, sender, rawAmount, payee))
  }

  private def buildEmail(document: CheerioDocument, rule: EmailRule.Extract, index: Int, sender: String, rawAmount: String, payee: String): ParsedEmail = {
    val amount = Money.fromMajorUnits(Money.parseMajorUnits(rawAmount))
    val accountLabel = nonEmptyText(document, rule.accountSelector)
    val accountKey = keyOf(accountLabel)
    val mappedAccount = lookupAccount(rule.accountMap, accountLabel).orElse(rule.accountDefault)
    ParsedEmail(
      amount = if rule.amountSign == AmountSign.Explicit then amount else -amount.abs,
      payee = payee,
      rawPayee = payee,
      date = nonEmptyText(document, rule.dateSelector).flatMap(parseDate),
      reference = nonEmptyText(document, rule.idSelector),
      notes = nonEmptyText(document, rule.notesSelector),
      categoryName = None,
      account = mappedAccount.fold(AccountRef.Unknown(accountKey, rule.accountChoices))(AccountRef.Known(_)),
      accountKey = accountKey,
      transferAccountId = lookupAccount(rule.transferMap, nonEmptyText(document, rule.transferSelector)),
      sender = sender,
      ruleIndex = index
    )
  }

  private def keyOf(label: Option[String]): Option[String] = label.map(_.split(" - ").head.trim).filter(_.nonEmpty)

  // A label reads "0900000001 - Tài khoản thanh toán": one phone number fronts several sub-accounts, so the full label maps first.
  private def lookupAccount(accountMap: Map[String, String], label: Option[String]): Option[String] = label.flatMap(accountMap.get).orElse(keyOf(label).flatMap(accountMap.get))

  private def textOf(document: CheerioDocument, selector: String): String = document(selector).text().trim

  // Cake writes "n/a" when it does not know a value, for example the sender of a transfer from VPBank.
  private val UnknownText = "n/a"

  private def nonEmptyText(document: CheerioDocument, selector: Option[String]): Option[String] = selector.map(textOf(document, _)).filter(text => text.nonEmpty && !text.equalsIgnoreCase(UnknownText))

  private val DayMonthYear = """(\d{1,2})[/-](\d{1,2})[/-](\d{4})""".r.unanchored

  def parseDate(raw: String): Option[LocalDate] = raw match {
    case DayMonthYear(day, month, year) => Some(LocalDate.of(year.toInt, month.toInt, day.toInt))
    case _                              => parseJsDate(raw)
  }

  // An ISO time with no offset is wall-clock time in the bank zone, the same as the email header.
  private def parseJsDate(raw: String): Option[LocalDate] = {
    val isoLocal = """^(\d{4})-(\d{2})-(\d{2})(T[\d:.]+)?$""".r
    raw match {
      case isoLocal(year, month, day, _) => Some(LocalDate.of(year.toInt, month.toInt, day.toInt))
      case _                             =>
        val millis = js.Date.parse(raw)
        Option.unless(millis.isNaN)(Instant.ofEpochMilli(millis.toLong).atZone(BankZone).toLocalDate)
    }
  }
}
