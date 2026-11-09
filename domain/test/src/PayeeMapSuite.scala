package composer.domain

import utest.*

object PayeeMapSuite extends TestSuite {
  private def mapping(pattern: String, payee: String, note: Option[String] = None, category: Option[String] = None) = PayeeMapping(List(pattern), payee, note, category)

  private val mappings = List(
    mapping("begroup", "Be", note = Some("Be")),
    mapping("vnpay", "VNPay"),
    mapping("tien lo", "GS25"),
    mapping("green sm", "XanhSM"),
    mapping("youtube", "Youtube"),
    mapping("halico", "Bun real"),
    mapping("tt the tin dung cake", "Cake Credit Card payment")
  )

  private def email(payee: String, notes: Option[String] = None) =
    ParsedEmail(Money.zero, payee, payee, None, None, notes, None, AccountRef.Unknown(None, Nil), None, "no-reply@cake.vn", 0)

  val tests = Tests {
    test("maps a noisy raw payee to the canonical name and replaces the note") {
      val result = PayeeMap(mappings, email("MPGS BEGROUP 9 HO CHI MINH VN", Some("Thanh toán thẻ qua ECOM")))
      (result.payee, result.notes) ==> ("Be", Some("Be"))
    }

    test("matches the raw payee without regard to case") {
      val result = PayeeMap(mappings, email("Vnpay_0314658576 Hcm Vn"))
      (result.payee, result.notes) ==> ("VNPay", None)
    }

    test("keeps the note when the entry has no note") {
      val result = PayeeMap(mappings, email("CTY TNHH CUA HANG TIEN LO TP.HCM VN", Some("Thanh toán POS")))
      (result.payee, result.notes) ==> ("GS25", Some("Thanh toán POS"))
    }

    test("maps a merchant tagged with a phone number") {
      PayeeMap(mappings, email("OP *GREEN SM 02439366668 VN")).payee ==> "XanhSM"
    }

    test("sets the mapped category name") {
      val result = PayeeMap(List(mapping("youtube", "Youtube", category = Some("🗓️ Bills"))), email("Google YouTube 650-2530000 US"))
      (result.payee, result.categoryName) ==> ("Youtube", Some("🗓️ Bills"))
    }

    test("maps a US merchant suffix") {
      PayeeMap(mappings, email("GOOGLE YOUTUBE 650-2530000 US")).payee ==> "Youtube"
    }

    test("maps a Vietnamese company name to its merchant") {
      PayeeMap(mappings, email("CTCP HALICO-HA NOI")).payee ==> "Bun real"
    }

    test("returns an email with no matching entry unchanged") {
      val original = email("LE VAN BINH")
      PayeeMap(mappings, original) ==> original
    }

    test("maps card payments with different bank references to one payee") {
      val first = PayeeMap(mappings, email("900000003 - NGUYỄN VĂN AN - TT THE TIN DUNG CAKE"))
      val second = PayeeMap(mappings, email("9000000000000000015 - NGUYỄN VĂN AN - TT THE TIN DUNG CAKE"))
      first.payee ==> "Cake Credit Card payment"
      second.payee ==> first.payee
    }

    test("uses the first matching entry in list order") {
      PayeeMap(List(mapping("grab", "First"), mapping("grab", "Second")), email("Grab")).payee ==> "First"
    }

    test("loads the example payee file") {
      val loaded = PayeeMap.decodeMappings(Fixtures.read("examples/payees.json"))
      assert(loaded.sizeIs >= 3)
      loaded.find(_.payee == "Be") ==> Some(PayeeMapping(List("begroup"), "Be", Some("Be"), None))
    }
  }
}
