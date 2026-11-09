package composer.domain

import upickle.default.{ReadWriter, read, write}
import upickle.implicits.key

final case class RuleConfig(rules: RuleMatrix, payees: List[PayeeMapping])

enum FixtureOutcome derives ReadWriter {
  case Parsed, Ignored, Unrecognized
}

final case class FixtureSnapshot(
    sender: String,
    outcome: FixtureOutcome,
    amount: Option[String] = None,
    payee: Option[String] = None,
    @key("raw_payee") rawPayee: Option[String] = None,
    date: Option[String] = None,
    reference: Option[String] = None,
    notes: Option[String] = None,
    category: Option[String] = None,
    @key("account_key") accountKey: Option[String] = None,
    @key("account_id") accountId: Option[String] = None,
    @key("transfer_account_id") transferAccountId: Option[String] = None,
    error: Option[String] = None
) derives ReadWriter

object RuleCheck {
  def snapshot(config: RuleConfig, html: String, sender: String): FixtureSnapshot = snapshotOf(config, sender, BankEmail.parse(config.rules, html, sender))

  def check(config: RuleConfig, fixtures: Map[String, String], expected: Map[String, String]): List[String] = {
    val decoded = expected.map((name, json) => name -> decode(name, json))
    val paired = fixtures.keySet.intersect(decoded.keySet).toList.sorted
    val results = paired.map(name => name -> BankEmail.parse(config.rules, fixtures(name), decoded(name).sender)).toMap
    val missingExpected = (fixtures.keySet -- decoded.keySet).toList.sorted.map(name => s"$name.html has no $name.expected.json")
    val missingFixtures = (decoded.keySet -- fixtures.keySet).toList.sorted.map(name => s"$name.expected.json has no $name.html")
    val mismatches = paired.filter(name => snapshotOf(config, decoded(name).sender, results(name)) != decoded(name))
    val covered = paired.flatMap(name => ruleIndexOf(results(name)).map(decoded(name).sender -> _)).toSet
    val uncovered =
      config.rules.toList.sortBy(_._1).flatMap((sender, rules) => rules.indices.filterNot(index => covered((sender, index))).map(index => s"$sender rule $index has no fixture that it matches first"))
    missingExpected ++ missingFixtures ++ mismatches.map(name => s"$name.html does not match $name.expected.json. Run check-rules with --update and review the diff.") ++ uncovered
  }

  def update(config: RuleConfig, fixtures: Map[String, String], expected: Map[String, String]): Map[String, String] =
    expected.collect { case (name, json) if fixtures.contains(name) => name -> render(snapshot(config, fixtures(name), decode(name, json).sender)) }

  def render(snapshot: FixtureSnapshot): String = write(snapshot, indent = 2) + "\n"

  private def decode(name: String, json: String): FixtureSnapshot =
    try read[FixtureSnapshot](json)
    catch { case error: Exception => throw IllegalArgumentException(s"$name.expected.json is not valid: ${error.getMessage}") }

  private def snapshotOf(config: RuleConfig, sender: String, result: ParseResult): FixtureSnapshot = result match {
    case ParseResult.Parsed(parsed) =>
      val email = PayeeMap(config.payees, parsed)
      FixtureSnapshot(
        sender,
        FixtureOutcome.Parsed,
        amount = Some(BigDecimal(email.amount.minorUnits, 2).bigDecimal.stripTrailingZeros.toPlainString),
        payee = Some(email.payee),
        rawPayee = Some(email.rawPayee),
        date = email.date.map(_.toString),
        reference = email.reference,
        notes = email.notes,
        category = email.categoryName,
        accountKey = email.accountKey,
        accountId = knownAccountId(email.account),
        transferAccountId = email.transferAccountId
      )
    case ParseResult.Ignored(_)           => FixtureSnapshot(sender, FixtureOutcome.Ignored)
    case ParseResult.Unrecognized(reason) => FixtureSnapshot(sender, FixtureOutcome.Unrecognized, error = Some(reason))
  }

  private def ruleIndexOf(result: ParseResult): Option[Int] = result match {
    case ParseResult.Parsed(email)   => Some(email.ruleIndex)
    case ParseResult.Ignored(index)  => Some(index)
    case ParseResult.Unrecognized(_) => None
  }

  private def knownAccountId(account: AccountRef): Option[String] = account match {
    case AccountRef.Known(accountId) => Some(accountId)
    case AccountRef.Unknown(_, _)    => None
  }
}
