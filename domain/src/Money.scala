package composer.domain

import scala.math.BigDecimal.RoundingMode

opaque type Money = Long

object Money {
  val zero: Money = 0L

  def fromMinorUnits(minorUnits: Long): Money = minorUnits

  def fromMajorUnits(majorUnits: BigDecimal): Money = (majorUnits * 100).setScale(0, RoundingMode.HALF_UP).toLong

  // Cake writes "1.234.567,89" and VPBank writes "1,234,567.89", so the decimal separator is found from the digits.
  def parseMajorUnits(raw: String): BigDecimal = {
    val cleaned = raw.replaceAll("[^\\d.,-]", "")
    val isNegative = cleaned.startsWith("-")
    val value = BigDecimal(withDotAsDecimal(cleaned.replace("-", "")))
    if isNegative then -value else value
  }

  private def withDotAsDecimal(digits: String): String = findDecimalSeparator(digits) match {
    case None            => digits.replaceAll("[.,]", "")
    case Some(separator) =>
      val at = digits.lastIndexOf(separator)
      s"${digits.substring(0, at).replaceAll("[.,]", "")}.${digits.substring(at + 1)}"
  }

  private def findDecimalSeparator(digits: String): Option[Char] = {
    val commas = digits.count(_ == ',')
    val dots = digits.count(_ == '.')
    if commas > 0 && dots > 0 then Some(if digits.lastIndexOf(',') > digits.lastIndexOf('.') then ',' else '.')
    else if digits.matches(".*[.,]\\d{3}") then None
    else if commas == 1 then Some(',')
    else if dots == 1 then Some('.')
    else None
  }

  extension (money: Money) {
    def minorUnits: Long = money
    def unary_- : Money = -money
    def abs: Money = math.abs(money)
    def +(other: Money): Money = money + other
  }
}
