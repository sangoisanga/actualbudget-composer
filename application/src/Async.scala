package composer.application

import scala.scalajs.js

object Async {
  // js.await cannot sit inside a lambda, so this one loop runs a promise for each item, in order.
  def traverse[A, B](items: List[A])(run: A => js.Promise[B]): js.Promise[List[B]] = js.async {
    val results = List.newBuilder[B]
    val remaining = items.iterator
    while remaining.hasNext do results += js.await(run(remaining.next()))
    results.result()
  }

  // A rejected JS promise arrives as JavaScriptException, whose getMessage adds an "Error: " prefix.
  def messageOf(error: Throwable): String = error match {
    case js.JavaScriptException(jsError: js.Error) => jsError.message
    case other                                     => other.getMessage
  }

  def runAndLog(promise: js.Promise[Unit], log: Log, context: String): Unit =
    promise.`then`[Unit]((_: Unit) => (), (error: Any) => log.error(s"$context failed: ${messageOf(toThrowable(error))}"))

  def toThrowable(error: Any): Throwable = error match {
    case throwable: Throwable => throwable
    case other                => js.JavaScriptException(other)
  }
}
