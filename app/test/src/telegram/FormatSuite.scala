package composer.telegram

import composer.domain.*
import utest.*

import java.time.{Instant, LocalDate}

object FormatSuite extends TestSuite {
  private val accountNames = Map("acct-1" -> "Cake card", "acct-2" -> "Cake OD")

  private def draft(payee: String = "Be", date: String = "2026-08-18", importedId: String = "msg-1", notes: Option[String] = None) =
    EntryDraft(LocalDate.parse(date), Money.fromMinorUnits(-1200000), payee, payee, importedId, notes, None)

  private def row(draft: EntryDraft = this.draft(), accountId: String = "acct-1", verdict: ImportVerdict = ImportVerdict.New) = ImportedRow(draft.assignTo(accountId), verdict)

  private def failure(messageId: String = "1a0581496579ffb6", subject: String = "Thông báo giao dịch thành công") =
    FailedEmail(messageId, "no-reply@cake.vn", subject, Some(LocalDate.of(2026, 8, 31)), "No rule matched this email from no-reply@cake.vn")

  private def report(rows: List[ImportedRow], failures: List[FailedEmail] = Nil, skipped: Int = 0, scanned: Option[Int] = None, unresolved: Int = 0) =
    PollReport(rows, failures, 0, skipped, scanned, unresolved)

  private def message(report: PollReport): String = Format.pollMessages(report, accountNames).head

  private def scheduled(report: PollReport, reported: Set[String] = Set.empty): List[String] = Format.scheduledMessages(report, accountNames, reported)

  private def checkAccountPrompt(accountKey: Option[String], reason: String): Unit = {
    val text = Format.accountPrompt(accountKey, List(draft(), draft()))
    assert(text.contains("Pick an account for 2 transaction(s)."))
    assert(text.contains(reason))
    assert(text.contains("18/08  Be"))
  }

  val tests = Tests {
    test("formats minor units as Vietnamese đồng") {
      Format.vnd(Money.fromMinorUnits(-1200000)) ==> "-12.000 ₫"
    }

    test("formats zero") {
      Format.vnd(Money.zero) ==> "0 ₫"
    }

    test("renders the date, padded payee, right-aligned amount, and notes") {
      Format.transactionLine(draft(notes = Some("Ride home"))) ==> "[N] 18/08  Be                         -12.000 ₫  · Ride home"
    }

    test("omits the notes suffix when there are no notes") {
      Format.transactionLine(draft()) ==> "[N] 18/08  Be                         -12.000 ₫"
    }

    test("tags the line with the import verdict") {
      List(ImportVerdict.New, ImportVerdict.Updated, ImportVerdict.Duplicate).map(Format.transactionLine(draft(), _).take(3)) ==> List("[N]", "[U]", "[=]")
    }

    test("truncates long payees") {
      assert(Format.transactionLine(draft("A very long merchant name that overflows")).contains("A very long merchant…"))
    }

    test("groups counts by account name") {
      val entries = List(row(), row(), row(accountId = "acct-2")).map(_.entry)
      Format.accountBreakdown(entries, accountNames) ==> "Cake card · 2  |  Cake OD · 1"
    }

    test("account prompt") {
      test("known key") { checkAccountPrompt(Some("••1111"), "The email shows ••1111, which has no account.") }
      test("no key") { checkAccountPrompt(None, "The email does not show which account paid.") }
    }

    test("links the failing email") {
      val line = Format.failureLine(failure())
      List("31/08", """<a href="https://mail.google.com/mail/u/0/#all/1a0581496579ffb6">""", "Thông báo giao dịch thành công", "No rule matched this email").foreach(part => assert(line.contains(part)))
    }

    test("uses the sender when the subject is missing") {
      assert(Format.failureLine(failure(subject = "")).contains("no-reply@cake.vn"))
    }

    test("escapes HTML in the subject") {
      val line = Format.failureLine(failure(subject = "A&B <Store>"))
      assert(line.contains("A&amp;B &lt;Store&gt;"))
      assert(!line.contains("<Store>"))
    }

    test("lists every failed message ID in one command") {
      Format.fixCommand(List(failure(), failure("other"))) ==> "<code>/fix-mail 1a0581496579ffb6 other</code>"
    }

    test("packs a small batch into one message with a header and a footer") {
      val text = message(report(List(row(), row())))
      List("<b>2</b> transaction(s) · total <b>-24.000 ₫</b>", "<pre>", "[N] 18/08  Be                         -12.000 ₫", "Cake card · 2").foreach(part => assert(text.contains(part)))
    }

    test("counts the rows by verdict") {
      val rows = List(row(verdict = ImportVerdict.New), row(verdict = ImportVerdict.Updated), row(verdict = ImportVerdict.Duplicate))
      assert(message(report(rows)).contains("1 new · 1 updated · 1 duplicate"))
    }

    test("lists every failure with its reason") {
      val text = message(report(List(row()), failures = List(failure(), failure("other", "Sao kê"))))
      List("<b>2 failed to parse</b>", "Thông báo giao dịch thành công", "Sao kê", "<code>/fix-mail 1a0581496579ffb6 other</code>").foreach(part => assert(text.contains(part)))
    }

    test("starts a full poll with every email bucket, zeros included") {
      val text = message(report(List(row()), scanned = Some(17), unresolved = 12))
      assert(text.startsWith("<b>17</b> email(s) scanned\n<b>1</b> transaction(s)"))
      assert(text.contains("<b>0</b> skipped · <b>12</b> awaiting an account · <b>0</b> failed"))
      "awaiting an account".r.findAllMatchIn(text).size ==> 1
    }

    test("keeps the account-pick summary to transactions and open picks") {
      val text = message(report(List(row()), unresolved = 3))
      assert(!text.contains("scanned"))
      assert(!text.contains("skipped"))
      assert(text.contains("3 awaiting an account"))
    }

    test("omits sections with nothing to report") {
      val text = message(report(List(row())))
      List("failed to parse", "/fix-mail", "awaiting an account", "failed to import").foreach(part => assert(!text.contains(part)))
    }

    test("reports failures when the poll imported nothing") {
      val messages = Format.pollMessages(report(Nil, failures = List(failure()), skipped = 2, scanned = Some(3)), accountNames)
      messages.size ==> 1
      List("<b>0</b> transaction(s)", "<b>2</b> skipped", "<b>1 failed to parse</b>", "<code>/fix-mail 1a0581496579ffb6</code>").foreach(part => assert(messages.head.contains(part)))
    }

    test("reports a failed import batch") {
      assert(message(PollReport(List(row()), Nil, 2, 0, None, 0)).contains("<b>2 failed to import</b>"))
    }

    test("sorts the newest date first") {
      val text = message(report(List(row(draft("BHX", "2026-08-19")), row(draft("Be", "2026-08-21")), row(draft("GS25", "2026-08-20")))))
      assert(text.indexOf("21/08  Be") < text.indexOf("20/08  GS25"))
      assert(text.indexOf("20/08  GS25") < text.indexOf("19/08  BHX"))
    }

    test("escapes HTML in the payee") {
      val text = message(report(List(row(draft("A&B <Store>")))))
      assert(text.contains("A&amp;B &lt;Store&gt;"))
      assert(!text.contains("<Store>"))
    }

    test("splits into several messages when the lines exceed the limit") {
      val many = (0 until 200).toList.map(index => row(draft(s"Merchant $index", notes = Some("A somewhat long note here"))))
      val messages = Format.pollMessages(report(many), accountNames)
      assert(messages.size > 1)
      assert(messages.forall(_.length <= 4096))
      assert(messages.head.contains("<b>200</b> transaction(s)"))
      assert(messages.last.contains("Cake card · 200"))
    }

    test("escapes ampersands and angle brackets") {
      Format.escapeHtml("a & b <c>") ==> "a &amp; b &lt;c&gt;"
    }

    test("scheduled messages") {
      test("sends nothing when every row is a duplicate") {
        scheduled(report(List(row(verdict = ImportVerdict.Duplicate)))) ==> Nil
      }
      test("drops duplicate rows") {
        val messages = scheduled(report(List(row(verdict = ImportVerdict.New), row(verdict = ImportVerdict.Duplicate))))
        messages.size ==> 1
        assert(messages.head.contains("[N]"))
        assert(!messages.head.contains("[=]"))
      }
      test("keeps updated rows") {
        assert(scheduled(report(List(row(verdict = ImportVerdict.Updated)))).head.contains("[U]"))
      }
      test("sends nothing for a failure it reported before") {
        scheduled(report(Nil, failures = List(failure())), reported = Set(failure().messageId)) ==> Nil
      }
      test("lists only the new failures") {
        val text = scheduled(report(Nil, failures = List(failure(), failure("other"))), reported = Set(failure().messageId)).head
        assert(text.contains("<code>/fix-mail other</code>"))
      }
      test("reports open picks with no rows") {
        assert(scheduled(report(Nil, scanned = Some(2), unresolved = 2)).head.contains("<b>2</b> awaiting an account"))
      }
      test("reports a failed import batch with no rows") {
        assert(scheduled(PollReport(Nil, Nil, 1, 0, None, 0)).head.contains("failed to import"))
      }
    }

    test("schedule") {
      test("on shows the next poll in BankZone") {
        Format.schedule(Some("0 */6 * * *"), Some(Instant.parse("2026-11-09T05:00:00Z"))) ==> "Scheduled poll runs on 0 */6 * * * (UTC+7). Next poll at 12:00 09/11."
      }
      test("off") { Format.schedule(None, None) ==> "Scheduled poll is off. Send /cron 0 */6 * * * to set it." }
    }

    test("account pick") {
      test("known key") { Format.accountPick(Some("0900000001"), "Cake OD", 8) ==> "Assigned 8 transaction(s) to Cake OD (email showed 0900000001)." }
      test("no key") { Format.accountPick(None, "Cake OD", 1) ==> "Assigned 1 transaction(s) to Cake OD (email showed no account)." }
    }
  }
}
