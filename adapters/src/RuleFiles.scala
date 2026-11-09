package composer.adapters

import composer.adapters.facade.NodeFs
import composer.domain.{BankEmail, PayeeMap, RuleConfig}

final case class FixtureFiles(html: Map[String, String], expected: Map[String, String])

object RuleFiles {
  def load(dir: String): RuleConfig =
    RuleConfig(BankEmail.decodeRules(NodeFs.readFileSync(s"$dir/rules.json", "utf-8")), PayeeMap.decodeMappings(NodeFs.readFileSync(s"$dir/payees.json", "utf-8")))

  def fixtures(dir: String): FixtureFiles = {
    val folder = s"$dir/fixtures"
    val fileNames = NodeFs.readdirSync(folder).toList
    def contentsBySuffix(suffix: String): Map[String, String] =
      fileNames.filter(_.endsWith(suffix)).map(fileName => fileName.stripSuffix(suffix) -> NodeFs.readFileSync(s"$folder/$fileName", "utf-8")).toMap
    FixtureFiles(contentsBySuffix(".html"), contentsBySuffix(".expected.json"))
  }

  def writeExpected(dir: String, name: String, json: String): Unit = NodeFs.writeFileSync(s"$dir/fixtures/$name.expected.json", json)
}
