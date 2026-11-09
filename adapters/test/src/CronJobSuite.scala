package composer.adapters

import composer.adapters.schedule.CronJob
import composer.domain.BankZone
import utest.*

import java.time.{DayOfWeek, ZonedDateTime}
import scala.scalajs.js

object CronJobSuite extends TestSuite {
  private def nextRunInBankZone(text: String): Option[ZonedDateTime] = {
    val job = CronJob.start(text, () => js.Promise.resolve[Unit](()))
    try job.nextRun.map(_.atZone(BankZone))
    finally job.stop()
  }

  private def isRejected(text: String): Boolean = CronJob.check(text).isLeft

  val tests = Tests {
    test("check") {
      test("trims the text") { CronJob.check(" 0 */6 * * * ") ==> Right("0 */6 * * *") }
      test("rejects 4 fields") { assert(isRejected("0 */6 * *")) }
      test("rejects a seconds field") { assert(isRejected("0 0 */6 * * *")) }
      test("rejects a minute past 59") { assert(isRejected("60 * * * *")) }
      test("rejects text") { assert(isRejected("abc")) }
      test("rejects a date that never comes") { CronJob.check("0 0 31 2 *") ==> Left("0 0 31 2 * never runs.") }
    }

    test("start") {
      test("runs every 6 hours in BankZone") {
        val next = nextRunInBankZone("0 */6 * * *").get
        (next.getMinute, next.getHour % 6) ==> (0, 0)
      }
      test("runs on Monday midnight in BankZone") {
        val next = nextRunInBankZone("0 0 * * 1").get
        (next.getDayOfWeek, next.getHour, next.getMinute) ==> (DayOfWeek.MONDAY, 0, 0)
      }
    }
  }
}
