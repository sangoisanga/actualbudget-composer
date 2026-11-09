package composer.adapters.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSGlobal, JSImport}

@js.native
@JSImport("node:fs", JSImport.Namespace)
object NodeFs extends js.Object {
  def readFileSync(path: String, encoding: String): String = js.native
  def readdirSync(path: String): js.Array[String] = js.native
  def writeFileSync(path: String, data: String): Unit = js.native
  def mkdirSync(path: String, options: js.Object): js.UndefOr[String] = js.native
  def mkdtempSync(prefix: String): String = js.native
  def rmSync(path: String, options: js.Object): Unit = js.native
}

@js.native
@JSImport("node:os", "tmpdir")
object tmpdir extends js.Object {
  def apply(): String = js.native
}

@js.native
@JSImport("node:buffer", "Buffer")
object NodeBuffer extends js.Object {
  def from(data: String, encoding: String): NodeBufferValue = js.native
}

@js.native
trait NodeBufferValue extends js.Object {
  def toString(encoding: String): String = js.native
}

@js.native
trait URLSearchParams extends js.Object {
  def get(name: String): String | Null = js.native
}

@js.native
@JSGlobal("URL")
class URL(input: String) extends js.Object {
  val hash: String = js.native
  val searchParams: URLSearchParams = js.native
}

@js.native
@JSGlobal("URL")
object URL extends js.Object {
  def canParse(input: String): Boolean = js.native
}

@js.native
@JSImport("node:readline/promises", "createInterface")
object createReadline extends js.Object {
  def apply(options: js.Object): ReadlineInterface = js.native
}

@js.native
trait ReadlineInterface extends js.Object {
  def question(query: String): js.Promise[String] = js.native
  def close(): Unit = js.native
}

@js.native
@JSImport("node:sqlite", "DatabaseSync")
class DatabaseSync(path: String) extends js.Object {
  def exec(sql: String): Unit = js.native
  def prepare(sql: String): StatementSync = js.native
  def close(): Unit = js.native
}

@js.native
trait StatementSync extends js.Object {
  def get(parameters: js.Any*): js.UndefOr[js.Dictionary[js.Any]] = js.native
  def run(parameters: js.Any*): js.Object = js.native
}
