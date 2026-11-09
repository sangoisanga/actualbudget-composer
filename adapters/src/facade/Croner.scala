package composer.adapters.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("croner", "Cron")
class Cron(pattern: String, options: js.Object, run: js.Function0[js.Promise[Unit]]) extends js.Object {
  def nextRun(): js.Date | Null = js.native
  def stop(): Unit = js.native
}
