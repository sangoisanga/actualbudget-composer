package composer.application

import composer.application.Fakes.*
import composer.domain.*
import utest.*

import scala.concurrent.duration.*
import scala.scalajs.js

object PollServiceSuite extends AsyncSuite {
  private val routedHtml = """<html><span class="amount-value">50.000 đ</span><span class="payee-name">X</span><span class="account-id">0900000001</span></html>"""
  private val routingRules: RuleMatrix = Map(cake -> List(rule(accountSelector = Some(".account-id"), accountChoices = List("acct-a"))))

  private def service(mailbox: Mailbox, session: FakeSession, rules: RuleMatrix = routingRules, timeout: FiniteDuration = 15.minutes, onExpired: Int => js.Promise[Unit] = _ => js.Promise.resolve(())) =
    PollService(session, mailbox, PollConfig(rules, Nil, timeout), NoLog, onExpired)

  val tests = Tests {
    test("has no pending picks at start") {
      service(FakeMailbox(Nil), FakeSession(FakeLedger())).pending ==> None
    }

    test("keeps the budget open while picks wait, and cancel closes it") {
      val session = FakeSession(FakeLedger(names = Map("acct-a" -> "Cake OD")))
      val polls = service(FakeMailbox(List(email("a", routedHtml))), session)
      js.async {
        js.await(polls.poll(1)) match {
          case PollStart.Finished(report, _, prompts) =>
            report.unresolved ==> 1
            prompts.map(prompt => (prompt.groupKey, prompt.choices)) ==> List("0900000001" -> List("acct-a" -> "Cake OD"))
          case other => throw java.lang.AssertionError(s"expected Finished, got $other")
        }
        polls.pending.map(_.count) ==> Some(1)
        js.await(polls.cancel()) ==> 1
        (polls.pending, session.closes) ==> (None, 1)
      }
    }

    test("closes the budget after a poll with nothing to pick") {
      val session = FakeSession(FakeLedger())
      val mappedRules = Map(cake -> List(rule(accountSelector = Some(".account-id"), accountMap = Map("0900000001" -> "acct-a"))))
      service(FakeMailbox(List(email("a", routedHtml))), session, rules = mappedRules).poll(1).map(_ => (session.opens, session.closes) ==> (1, 1))
    }

    test("answers Busy while a poll runs") {
      var release: () => Unit = () => ()
      val blocking = new Mailbox {
        def list(query: String): js.Promise[List[String]] = js.Promise[List[String]]((resolve, _) => release = () => resolve(Nil))
        def get(id: String): js.Promise[Email] = rejected("unused")
      }
      val polls = service(blocking, FakeSession(FakeLedger()))
      js.async {
        val first = polls.poll(1)
        js.await(sleep(10.millis))
        js.await(polls.poll(1)) ==> PollStart.Busy
        release()
        js.await(first)
      }
    }

    test("answers PicksPending while picks wait") {
      val polls = service(FakeMailbox(List(email("a", routedHtml))), FakeSession(FakeLedger()))
      js.async {
        js.await(polls.poll(1))
        js.await(polls.poll(1)) ==> PollStart.PicksPending
      }
    }

    test("imports the picked group and closes the budget after the last pick") {
      val session = FakeSession(FakeLedger(names = Map("acct-a" -> "Cake OD")))
      val polls = service(FakeMailbox(List(email("a", routedHtml))), session)
      js.async {
        js.await(polls.poll(1))
        js.await(polls.assign("0900000001", "acct-a")) match {
          case AssignResult.Imported(report, _, accountKey, count) =>
            (report.rows.map(_.entry.accountId), accountKey, count, report.unresolved) ==> (List("acct-a"), Some("0900000001"), 1, 0)
          case other => throw java.lang.AssertionError(s"expected Imported, got $other")
        }
        (polls.pending, session.closes) ==> (None, 1)
      }
    }

    test("drops the picks after the timeout and reports the count") {
      val session = FakeSession(FakeLedger())
      var expired: Option[Int] = None
      val polls = service(FakeMailbox(List(email("a", routedHtml))), session, timeout = 20.millis, onExpired = count => {
        expired = Some(count)
        js.Promise.resolve(())
      })
      js.async {
        js.await(polls.poll(1))
        js.await(sleep(100.millis))
        (polls.pending, expired, session.closes) ==> (None, Some(1), 1)
      }
    }
  }
}
