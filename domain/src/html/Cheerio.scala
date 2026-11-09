package composer.domain.html

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
trait CheerioSelection extends js.Object {
  val length: Int = js.native
  def text(): String = js.native
}

@js.native
trait CheerioDocument extends js.Object {
  def apply(selector: String): CheerioSelection = js.native
}

@js.native
@JSImport("cheerio", "load")
object loadHtml extends js.Object {
  def apply(html: String): CheerioDocument = js.native
}
