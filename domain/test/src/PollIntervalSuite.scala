package composer.domain

import utest.*

import scala.concurrent.duration.*

object PollIntervalSuite extends TestSuite {
  val tests = Tests {
    test("parse") {
      test("hours") { PollInterval.parse("6h") ==> Some(6.hours) }
      test("days") { PollInterval.parse("1d") ==> Some(1.day) }
      test("trims spaces") { PollInterval.parse(" 12h ") ==> Some(12.hours) }
      test("accepts the maximum") { (PollInterval.parse("7d"), PollInterval.parse("168h")) ==> (Some(7.days), Some(7.days)) }
      test("rejects past the maximum") { (PollInterval.parse("8d"), PollInterval.parse("169h")) ==> (None, None) }
      test("rejects zero") { PollInterval.parse("0h") ==> None }
      test("rejects a negative count") { PollInterval.parse("-1d") ==> None }
      test("rejects minutes") { PollInterval.parse("30m") ==> None }
      test("rejects a bare number") { PollInterval.parse("6") ==> None }
      test("rejects weeks") { PollInterval.parse("1w") ==> None }
      test("rejects text") { PollInterval.parse("abc") ==> None }
    }

    test("show") {
      test("hours") { PollInterval.show(6.hours) ==> "6h" }
      test("whole days") { PollInterval.show(48.hours) ==> "2d" }
      test("hours past a day") { PollInterval.show(36.hours) ==> "36h" }
    }
  }
}
