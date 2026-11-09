package composer

import composer.adapters.telegram.Heartbeat
import composer.application.Async
import composer.cli.{AuthCli, CheckCli, InspectCli, PollCli}
import composer.telegram.BotApp

import scala.scalajs.js

object Main {
  private val usage = "Usage: node dist/main.js <bot | poll | auth-gmail | inspect-mail <id>... | check-rules <dir> [--update] | healthcheck>"
  private val process = js.Dynamic.global.process

  def main(args: Array[String]): Unit = {
    // Scala.js passes no arguments to main, so the subcommand comes from process.argv.
    val arguments = process.argv.asInstanceOf[js.Array[String]].drop(2).toList
    run(arguments).`then`[Unit](
      (exitCode: Int) => exit(exitCode),
      (error: Any) => {
        System.err.println(Async.messageOf(Async.toThrowable(error)))
        exit(1)
      }
    )
  }

  private def exit(code: Int): Unit = process.exit(code)

  private def run(arguments: List[String]): js.Promise[Int] = js.async {
    arguments match {
      case "bot" :: _             => js.await(BotApp.run(Config.bot()))
      case "poll" :: _            => js.await(PollCli.run(Config.pollCli()))
      case "auth-gmail" :: _      => js.await(AuthCli.run())
      case "inspect-mail" :: rest => js.await(InspectCli.run(rest))
      case "check-rules" :: rest  => CheckCli.run(rest)
      case "healthcheck" :: _     => if Heartbeat.isHealthy(Heartbeat.path, js.Date.now().toLong) then 0 else 1
      case _                      =>
        println(usage)
        1
    }
  }
}
