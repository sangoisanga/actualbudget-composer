package composer.domain

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("node:fs", "readFileSync")
private object readFileSync extends js.Object {
  def apply(path: String, encoding: String): String = js.native
}

@js.native
@JSImport("node:fs", "readdirSync")
private object readdirSync extends js.Object {
  def apply(path: String): js.Array[String] = js.native
}

object Fixtures {
  private val root = js.Dynamic.global.process.env.COMPOSER_ROOT.asInstanceOf[String]
  private val resources = js.Dynamic.global.process.env.MILL_TEST_RESOURCE_DIR.asInstanceOf[String]

  def read(relativePath: String): String = readFileSync(s"$root/$relativePath", "utf-8")

  def html(name: String): String = readFileSync(s"$resources/$name", "utf-8")

  def example(name: String): String = read(s"examples/fixtures/$name")

  def exampleConfig: RuleConfig = RuleConfig(BankEmail.decodeRules(read("examples/rules.json")), PayeeMap.decodeMappings(read("examples/payees.json")))

  def examplesBySuffix(suffix: String): Map[String, String] =
    readdirSync(s"$root/examples/fixtures").toList.filter(_.endsWith(suffix)).map(fileName => fileName.stripSuffix(suffix) -> example(fileName)).toMap
}
