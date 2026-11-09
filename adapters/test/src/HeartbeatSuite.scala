package composer.adapters

import composer.adapters.facade.{NodeFs, tmpdir}
import composer.adapters.telegram.Heartbeat
import utest.*

import scala.scalajs.js
import scala.util.{Failure, Success, Try}

object HeartbeatSuite extends TestSuite {
  private val now = 1_800_000_000_000L
  private val staleMillis = Heartbeat.staleAfter.toMillis

  private def respond(response: js.Dynamic): Heartbeat.ApiCall = (_, _, _) => js.Promise.resolve[js.Dynamic](response)

  private final case class Tracked(outcome: Try[js.Dynamic], polls: Int)

  private def track(previous: Heartbeat.ApiCall, method: String): js.Promise[Tracked] = js.async {
    var polls = 0
    val outcome = try Success(js.await(Heartbeat.trackPolls(() => polls += 1)(previous, method, js.Object(), js.undefined))) catch { case error: Throwable => Failure(error) }
    Tracked(outcome, polls)
  }

  private def withDirectory[A](body: String => A): A = {
    val directory = NodeFs.mkdtempSync(s"${tmpdir()}/heartbeat-")
    try body(directory)
    finally NodeFs.rmSync(directory, js.Dynamic.literal(recursive = true, force = true))
  }

  val tests = Tests {
    test("records a successful getUpdates and returns the response unchanged") {
      val response = js.Dynamic.literal(ok = true, result = js.Array())
      js.async {
        val tracked = js.await(track(respond(response), "getUpdates"))
        tracked.polls ==> 1
        assert(tracked.outcome.get eq response)
      }.toFuture
    }

    test("ignores a failed getUpdates") {
      js.async { js.await(track(respond(js.Dynamic.literal(ok = false)), "getUpdates")).polls ==> 0 }.toFuture
    }

    test("ignores other methods") {
      js.async { js.await(track(respond(js.Dynamic.literal(ok = true)), "setMyCommands")).polls ==> 0 }.toFuture
    }

    test("passes a rejection through without a record") {
      val rejecting: Heartbeat.ApiCall = (_, _, _) => js.Promise.reject(js.Error("network down")).asInstanceOf[js.Promise[js.Dynamic]]
      js.async {
        val tracked = js.await(track(rejecting, "getUpdates"))
        tracked.polls ==> 0
        assert(tracked.outcome.failed.get.getMessage.contains("network down"))
      }.toFuture
    }

    test("is fresh at exactly the limit") {
      assert(!Heartbeat.isStale(0, staleMillis))
    }

    test("is stale past the limit") {
      assert(Heartbeat.isStale(0, staleMillis + 1))
    }

    test("is unhealthy with no heartbeat file") {
      withDirectory(directory => assert(!Heartbeat.isHealthy(s"$directory/heartbeat", now)))
    }

    test("is healthy after a fresh heartbeat") {
      withDirectory { directory =>
        Heartbeat.write(s"$directory/heartbeat", now)
        assert(Heartbeat.isHealthy(s"$directory/heartbeat", now))
      }
    }

    test("is unhealthy after a stale heartbeat") {
      withDirectory { directory =>
        Heartbeat.write(s"$directory/heartbeat", now - staleMillis - 1)
        assert(!Heartbeat.isHealthy(s"$directory/heartbeat", now))
      }
    }
  }
}
