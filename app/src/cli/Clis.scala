package composer.cli

import composer.{Config, PollCliConfig}
import composer.adapters.RuleFiles
import composer.adapters.actual.{ActualSession, DryRunLedger}
import composer.adapters.facade.{NodeFs, createReadline}
import composer.adapters.gmail.{GmailAuth, GmailCredentials, GmailMailbox}
import composer.adapters.log.JsonLog
import composer.application.*
import composer.domain.{BankEmail, ParseResult, RuleCheck, RuleConfig}

import scala.scalajs.js

object PollCli {
  def run(config: PollCliConfig): js.Promise[Int] = js.async {
    JsonLog.redirectConsole(JsonLog.stdout)
    val log = JsonLog(config.isDebug, JsonLog.stdout)
    val mailbox = GmailMailbox(GmailCredentials(config.oauth.clientId, config.oauth.clientSecret, config.refreshToken))
    if config.isDryRun then js.await(pollWith(DryRunLedger(log), mailbox, config.fallbackAccountId.orElse(Some("dry-run")), config, log))
    else {
      val session = ActualSession(Config.actualCli())
      val ledger = js.await(session.open())
      try js.await(pollWith(ledger, mailbox, config.fallbackAccountId, config, log))
      finally js.await(session.close())
    }
    0
  }

  private def pollWith(ledger: Ledger, mailbox: Mailbox, fallbackAccountId: Option[String], config: PollCliConfig, log: Log): js.Promise[Unit] = js.async {
    val files = RuleFiles.load(config.rulesDir)
    val categoryIds = js.await(ledger.categoryIds)
    val collected = js.await(Collector.collect(mailbox, CollectSettings(files.rules, files.payees, categoryIds, fallbackAccountId, config.lookbackDays), log))
    val imported = js.await(Importer.importAll(ledger, collected.ready, log))
    log.info(
      "Poll complete",
      "imported" -> imported.rows.size,
      "failed_imports" -> imported.failedImports,
      "failures" -> collected.failures.size,
      "skipped" -> collected.skipped,
      "unresolved" -> collected.pending.size
    )
  }
}

object InspectCli {
  def run(inputs: List[String]): js.Promise[Int] =
    if inputs.isEmpty then {
      println("Usage: npm run inspect:mail -- <message ID or Gmail link>...")
      js.Promise.resolve(1)
    } else
      js.async {
        val config = Config.gmailCli()
        val files = RuleFiles.load(config.rulesDir)
        NodeFs.mkdirSync(s"${config.rulesDir}/fixtures", js.Dynamic.literal(recursive = true))
        val mailbox = GmailMailbox(GmailCredentials(config.oauth.clientId, config.oauth.clientSecret, config.refreshToken))
        val messageIds = inputs.map(input => GmailAuth.extractMessageId(input).fold(reason => throw RuntimeException(reason), identity))
        js.await(Async.traverse(messageIds)(inspect(mailbox, files, config.rulesDir, _)))
        0
      }

  private def inspect(mailbox: Mailbox, files: RuleConfig, rulesDir: String, messageId: String): js.Promise[Unit] = js.async {
    val email = js.await(mailbox.get(messageId))
    val fixturePath = s"$rulesDir/fixtures/${GmailAuth.fixtureName(email.sender, messageId)}"
    val traces = BankEmail.trace(files.rules, email.htmlBody, email.sender).flatMap { trace =>
      s"  rule ${trace.index}${if trace.isIgnoreRule then " (ignore)" else ""}" :: trace.selectors
        .map(selector => s"    ${selector.field}: ${selector.selector}\n      → ${if selector.text.isEmpty then "(empty)" else selector.text}")
    }
    val parse = BankEmail.parse(files.rules, email.htmlBody, email.sender) match {
      case ParseResult.Parsed(parsed)       => parsed.toString
      case ParseResult.Ignored(_)           => "ignored (an ignore rule matched)"
      case ParseResult.Unrecognized(reason) => s"error: $reason"
    }
    NodeFs.writeFileSync(fixturePath, email.htmlBody)
    NodeFs.writeFileSync(s"${fixturePath.stripSuffix(".html")}.expected.json", RuleCheck.render(RuleCheck.snapshot(files, email.htmlBody, email.sender)))
    println((List(s"\n$messageId: saved $fixturePath", s"  sender: ${email.sender}\n  subject: ${email.subject}\n  date: ${email.date}") ++ traces :+ s"  parse: $parse").mkString("\n"))
  }
}

object CheckCli {
  def run(arguments: List[String]): Int = arguments match {
    case dir :: Nil               => report(dir)
    case dir :: "--update" :: Nil =>
      val files = RuleFiles.fixtures(dir)
      RuleCheck.update(RuleFiles.load(dir), files.html, files.expected).foreach((name, json) => RuleFiles.writeExpected(dir, name, json))
      report(dir)
    case _ =>
      println("Usage: check-rules <dir> [--update]")
      1
  }

  private def report(dir: String): Int = {
    val config = RuleFiles.load(dir)
    val files = RuleFiles.fixtures(dir)
    RuleCheck.check(config, files.html, files.expected) match {
      case Nil =>
        println(s"ok  ${files.html.size} fixtures, ${config.rules.values.map(_.size).sum} rules covered")
        0
      case problems =>
        problems.foreach(println)
        1
    }
  }
}

object AuthCli {
  def run(): js.Promise[Int] = js.async {
    val oauth = Config.authCli()
    println(s"Open this URL, authorize Gmail read access, then paste the redirect URL here:\n${GmailAuth.authUrl(oauth)}")
    val readline = createReadline(js.Dynamic.literal(input = js.Dynamic.global.process.stdin, output = js.Dynamic.global.process.stdout))
    val pasted =
      try js.await(readline.question("Redirect URL or code: "))
      finally readline.close()
    val code = GmailAuth.extractAuthCode(pasted).fold(reason => throw RuntimeException(reason), identity)
    println(s"Add this to your environment:\nGMAIL_REFRESH_TOKEN=${js.await(GmailAuth.exchangeCode(oauth, code))}")
    0
  }
}
