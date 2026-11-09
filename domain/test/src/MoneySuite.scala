package composer.domain

import utest.*

object MoneySuite extends TestSuite {
  val tests = Tests {
    test("parses Vietnamese dot-thousands") {
      Money.parseMajorUnits("17.000 đ") ==> BigDecimal(17000)
      Money.parseMajorUnits("1.234.567 đ") ==> BigDecimal(1234567)
    }

    test("parses VPBank comma-thousands") {
      Money.parseMajorUnits("29,000,000 VND") ==> BigDecimal(29000000)
      Money.parseMajorUnits("2,518,813 VND") ==> BigDecimal(2518813)
    }

    test("parses comma-thousands with dot decimals") {
      Money.parseMajorUnits("9,136,858.00 VND") ==> BigDecimal(9136858)
    }

    test("parses comma decimals") {
      Money.parseMajorUnits("1.234,56 đ") ==> BigDecimal("1234.56")
    }

    test("parses plain decimals") {
      Money.parseMajorUnits("17.5") ==> BigDecimal("17.5")
    }

    test("parses an explicit sign") {
      Money.parseMajorUnits("-30.000 đ") ==> BigDecimal(-30000)
      Money.parseMajorUnits("+30.000 đ") ==> BigDecimal(30000)
    }
  }
}
