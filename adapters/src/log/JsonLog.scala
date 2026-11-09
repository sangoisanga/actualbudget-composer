package composer.adapters.log

import composer.application.Log
import composer.domain.BankZone

import java.time.Instant
import java.time.format.DateTimeFormatter
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

enum Severity {
  case Debug, Info, Warning, Error
}

final class JsonLog(isDebugEnabled: Boolean, write: String => Unit) extends Log {
  def debug(message: String, fields: (String, Any)*): Unit = if isDebugEnabled then JsonLog.writeLine(write, Severity.Debug, message, JsonLog.toJsFields(fields))
  def info(message: String, fields: (String, Any)*): Unit = JsonLog.writeLine(write, Severity.Info, message, JsonLog.toJsFields(fields))
  def error(message: String, fields: (String, Any)*): Unit = JsonLog.writeLine(write, Severity.Error, message, JsonLog.toJsFields(fields))
}

object JsonLog {
  private val timeFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX")

  def stdout: String => Unit = {
    val stream = js.Dynamic.global.process.stdout
    line => stream.write(line)
  }

  def writeLine(write: String => Unit, severity: Severity, message: String, fields: Map[String, js.Any]): Unit = {
    val time = Instant.ofEpochMilli(js.Date.now().toLong).atZone(BankZone).format(timeFormat)
    // Coolify reads the first level-like key in the line, so severity must come before the event fields.
    val record = js.Dictionary[js.Any]("severity" -> severity.toString.toUpperCase, "time" -> time, "message" -> message)
    fields.foreach((key, value) => record(key) = value)
    write(js.JSON.stringify(record) + "\n")
  }

  private type ConsoleMethod = js.Function4[js.UndefOr[js.Any], js.UndefOr[js.Any], js.UndefOr[js.Any], js.UndefOr[js.Any], Unit]

  // Actual writes its sync progress through console, so console lines must become JSON too. Calls use at most four arguments.
  def redirectConsole(write: String => Unit): Unit = {
    val console = js.Dynamic.global.console
    def route(severity: Severity): ConsoleMethod = (first, second, third, fourth) => {
      val arguments = List(first, second, third, fourth).flatMap(_.toOption)
      val (texts, data) = arguments.partition(argument => js.typeOf(argument) == "string")
      val message = texts.map(_.toString).mkString(" ")
      val fields: Map[String, js.Any] = data match {
        case Nil         => Map.empty
        case only :: Nil => Map("data" -> only)
        case many        => Map("data" -> many.toJSArray)
      }
      writeLine(write, severity, message, fields)
    }
    console.log = route(Severity.Info)
    console.info = route(Severity.Info)
    console.debug = route(Severity.Debug)
    console.warn = route(Severity.Warning)
    console.error = route(Severity.Error)
    console.group = route(Severity.Info)
    console.groupEnd = (() => ()): js.Function0[Unit]
  }

  private def toJsFields(fields: Seq[(String, Any)]): Map[String, js.Any] = fields.map((key, value) => key -> toJs(value)).toMap

  private def toJs(value: Any): js.Any = value match {
    case text: String   => text
    case number: Int    => number
    case number: Long   => number.toDouble
    case number: Double => number
    case flag: Boolean  => flag
    case other          => other.toString
  }
}
