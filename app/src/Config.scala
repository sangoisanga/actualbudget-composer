package composer

import composer.adapters.actual.ActualConfig
import composer.adapters.gmail.{GmailAuth, OAuthConfig}
import composer.domain.PollInterval

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*
import scala.scalajs.js

final case class BotConfig(
    telegramToken: String,
    allowedChatId: Long,
    actual: ActualConfig,
    oauth: OAuthConfig,
    refreshToken: Option[String],
    lookbackDays: Int,
    pollInterval: FiniteDuration,
    isDebug: Boolean,
    rulesDir: String
)

final case class PollCliConfig(oauth: OAuthConfig, refreshToken: String, fallbackAccountId: Option[String], lookbackDays: Int, isDryRun: Boolean, isDebug: Boolean, rulesDir: String)

final case class GmailCliConfig(oauth: OAuthConfig, refreshToken: String, rulesDir: String)

// Collects every env problem first, so one failed start lists all of them.
private final class Env {
  private val values = js.Dynamic.global.process.env.asInstanceOf[js.Dictionary[String]]
  private val problems = ListBuffer.empty[String]

  def optional(name: String): Option[String] = values.get(name).filter(_.nonEmpty)

  def required(name: String): String = optional(name).getOrElse {
    problems += s"$name is required"
    ""
  }

  def isTrue(name: String): Boolean = optional(name).contains("true")

  def positiveInt(name: String, default: Int): Int = optional(name).fold(default) { value =>
    value.toIntOption.filter(_ > 0).getOrElse {
      problems += s"$name must be a positive integer"
      default
    }
  }

  def interval(name: String, default: FiniteDuration): FiniteDuration = optional(name).fold(default) { value =>
    PollInterval.parse(value).getOrElse {
      problems += s"$name must be hours or days, like 6h or 1d, up to ${PollInterval.show(PollInterval.Max)}"
      default
    }
  }

  def long(name: String): Long = {
    val value = required(name)
    value.toLongOption.getOrElse {
      if value.nonEmpty then problems += s"$name must be an integer"
      0L
    }
  }

  def checked[A](config: A): A = if problems.isEmpty then config else throw IllegalArgumentException(problems.mkString("; "))
}

object Config {
  private def oauth(env: Env): OAuthConfig =
    OAuthConfig(env.required("GMAIL_CLIENT_ID"), env.required("GMAIL_CLIENT_SECRET"), env.optional("GMAIL_REDIRECT_URI").getOrElse(GmailAuth.DefaultRedirectUri))

  private def actual(env: Env): ActualConfig =
    ActualConfig(
      env.optional("ACTUAL_BUDGET_DATA_DIR").getOrElse("./data"),
      env.required("ACTUAL_BUDGET_URL"),
      env.required("ACTUAL_BUDGET_PASSWORD"),
      env.required("ACTUAL_BUDGET_SYNC_ID"),
      env.isTrue("POLL_DEBUG")
    )

  def bot(): BotConfig = {
    val env = Env()
    env.checked(
      BotConfig(
        env.required("TELEGRAM_BOT_TOKEN"),
        env.long("TELEGRAM_ALLOWED_CHAT_ID"),
        actual(env),
        oauth(env),
        env.optional("GMAIL_REFRESH_TOKEN"),
        env.positiveInt("POLL_LOOKBACK_DAYS", 3),
        env.interval("POLL_INTERVAL", 6.hours),
        env.isTrue("POLL_DEBUG"),
        env.required("RULES_DIR")
      )
    )
  }

  def pollCli(): PollCliConfig = {
    val env = Env()
    env.checked(
      PollCliConfig(
        oauth(env),
        env.required("GMAIL_REFRESH_TOKEN"),
        env.optional("ACTUAL_BUDGET_ACCOUNT_ID"),
        env.positiveInt("POLL_LOOKBACK_DAYS", 1),
        env.isTrue("POLL_DRY_RUN"),
        env.isTrue("POLL_DEBUG"),
        env.required("RULES_DIR")
      )
    )
  }

  def actualCli(): ActualConfig = {
    val env = Env()
    env.checked(actual(env))
  }

  def gmailCli(): GmailCliConfig = {
    val env = Env()
    env.checked(GmailCliConfig(oauth(env), env.required("GMAIL_REFRESH_TOKEN"), env.required("RULES_DIR")))
  }

  def authCli(): OAuthConfig = {
    val env = Env()
    env.checked(oauth(env))
  }
}
