package composer.adapters.telegram

import composer.adapters.facade.{NodeFs, tmpdir}

import scala.concurrent.duration.*
import scala.scalajs.js
import scala.util.Try

object Heartbeat {
  val path: String = s"${tmpdir()}/actualbudget-composer-heartbeat"
  val staleAfter: FiniteDuration = 5.minutes

  type ApiCall = js.Function3[String, js.Any, js.Any, js.Promise[js.Dynamic]]
  type Transformer = js.Function4[ApiCall, String, js.Any, js.Any, js.Promise[js.Dynamic]]

  def trackPolls(onPoll: () => Unit): Transformer = (previous, method, payload, signal) =>
    previous(method, payload, signal).`then`[js.Dynamic] { (response: js.Dynamic) =>
      if method == "getUpdates" && response.ok.asInstanceOf[Boolean] then onPoll()
      response
    }

  def isStale(lastPollAtMillis: Long, nowMillis: Long): Boolean = nowMillis - lastPollAtMillis > staleAfter.toMillis

  def write(path: String, atMillis: Long): Unit = NodeFs.writeFileSync(path, atMillis.toString)

  def isHealthy(path: String, nowMillis: Long): Boolean =
    Try(NodeFs.readFileSync(path, "utf8").trim.toLong).toOption.exists(lastPollAt => !isStale(lastPollAt, nowMillis))
}
