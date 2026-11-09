package composer.domain

import scala.concurrent.duration.*

object PollInterval {
  // Node runs a setInterval delay above about 24.8 days after 1 ms.
  val Max: FiniteDuration = 7.days
  private val Pattern = "([1-9][0-9]*)([hd])".r

  def parse(text: String): Option[FiniteDuration] = text.trim match {
    case Pattern(count, "h") => count.toIntOption.map(_.hours).filter(_ <= Max)
    case Pattern(count, "d") => count.toIntOption.map(_.days).filter(_ <= Max)
    case _                   => None
  }

  def show(interval: FiniteDuration): String = if interval.toHours % 24 == 0 then s"${interval.toDays}d" else s"${interval.toHours}h"
}
