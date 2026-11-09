package composer.adapters

import composer.adapters.log.JsonLog
import utest.*

import scala.collection.mutable.ListBuffer
import scala.scalajs.js

object JsonLogSuite extends TestSuite {
  private val console = js.Dynamic.global.console
  private val methods = List("log", "info", "debug", "warn", "error", "group", "groupEnd")
  private var originals = Map.empty[String, js.Dynamic]

  override def utestBeforeEach(path: Seq[String]): Unit = originals = methods.map(name => name -> console.selectDynamic(name)).toMap

  override def utestAfterEach(path: Seq[String]): Unit = originals.foreach((name, method) => console.updateDynamic(name)(method))

  private final class Capture {
    val lines = ListBuffer.empty[js.Dynamic]
    val write: String => Unit = line => lines += js.JSON.parse(line)
  }

  val tests = Tests {
    test("writes one JSON line with the message and the fields") {
      val capture = Capture()
      JsonLog(isDebugEnabled = false, capture.write).info("Imported transaction", "id" -> "a1")
      val line = capture.lines.head
      (line.severity.asInstanceOf[String], line.message.asInstanceOf[String], line.id.asInstanceOf[String]) ==> ("INFO", "Imported transaction", "a1")
    }

    test("writes severity as the first key") {
      val capture = Capture()
      JsonLog(isDebugEnabled = false, capture.write).info("Imported transaction", "level" -> "debug")
      js.Object.keys(capture.lines.head.asInstanceOf[js.Object]).head ==> "severity"
    }

    test("writes the time in RFC 3339 at the bank offset") {
      val capture = Capture()
      JsonLog(isDebugEnabled = false, capture.write).info("tick")
      val time = capture.lines.head.time.asInstanceOf[String]
      assert(time.matches("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}\+07:00"""))
    }

    test("drops debug lines unless debug is on") {
      val quiet = Capture()
      val verbose = Capture()
      JsonLog(isDebugEnabled = false, quiet.write).debug("hidden")
      JsonLog(isDebugEnabled = true, verbose.write).debug("shown")
      (quiet.lines.size, verbose.lines.size) ==> (0, 1)
    }

    test("writes log error lines at the error severity") {
      val capture = Capture()
      JsonLog(isDebugEnabled = false, capture.write).error("Handler failed")
      capture.lines.head.severity.asInstanceOf[String] ==> "ERROR"
    }

    test("turns console.log arguments into a JSON line") {
      val capture = Capture()
      JsonLog.redirectConsole(capture.write)
      console.log("Imported transaction", js.Dynamic.literal(payee = "Be", amount = -2900000))
      val line = capture.lines.head
      (line.message.asInstanceOf[String], line.data.payee.asInstanceOf[String]) ==> ("Imported transaction", "Be")
    }

    test("turns console.group into a JSON line and drops console.groupEnd") {
      val capture = Capture()
      JsonLog.redirectConsole(capture.write)
      console.group("Sync")
      console.groupEnd()
      capture.lines.map(_.message.asInstanceOf[String]).toList ==> List("Sync")
    }

    test("writes console.warn at the warning severity") {
      val capture = Capture()
      JsonLog.redirectConsole(capture.write)
      console.warn("slow sync")
      capture.lines.head.severity.asInstanceOf[String] ==> "WARNING"
    }

    test("writes console.error at the error severity") {
      val capture = Capture()
      JsonLog.redirectConsole(capture.write)
      console.error("boom")
      val line = capture.lines.head
      (line.severity.asInstanceOf[String], line.message.asInstanceOf[String]) ==> ("ERROR", "boom")
    }
  }
}