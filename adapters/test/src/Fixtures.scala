package composer.adapters

import composer.adapters.facade.NodeFs

import scala.scalajs.js

object Fixtures {
  private val resources = js.Dynamic.global.process.env.MILL_TEST_RESOURCE_DIR.asInstanceOf[String]

  def read(name: String): String = NodeFs.readFileSync(s"$resources/$name", "utf-8")
}
