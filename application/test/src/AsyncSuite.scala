package composer.application

import utest.*

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.timers.setTimeout

abstract class AsyncSuite extends TestSuite {
  // utest waits only on a Future, so a returned js.Promise would pass before its asserts run.
  override def utestWrap(path: Seq[String], runBody: => Future[Any])(implicit ec: ExecutionContext): Future[Any] = runBody.flatMap {
    case promise: js.Promise[?] => promise.toFuture
    case value                  => Future.successful(value)
  }

  def sleep(duration: FiniteDuration): js.Promise[Unit] = js.Promise[Unit]((resolve, _) => setTimeout(duration)(resolve(())))

  extension [A](promise: js.Promise[A]) {
    def map[B](transform: A => B): js.Promise[B] = promise.`then`[B]((value: A) => transform(value): B | js.Thenable[B])
  }
}
