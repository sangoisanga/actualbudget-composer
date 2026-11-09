package composer.application

import composer.application.Fakes.*
import composer.domain.*
import utest.*

import java.time.LocalDate
import scala.scalajs.js

object CollectorSuite extends AsyncSuite {
  private def collect(emails: List[Email], settings: CollectSettings = Fakes.settings()): js.Promise[Collected] = Collector.collect(FakeMailbox(emails), settings, NoLog)

  private val routedHtml = """<html><span class="amount-value">50.000 đ</span><span class="payee-name">X</span><span class="account-id">0900000001 - Tài khoản thanh toán</span></html>"""

  val tests = Tests {
    test("builds the query from the senders and the lookback window") {
      Collector.query(List(cake), 7) ==> Some("(from:(no-reply@cake.vn)) newer_than:7d")
    }

    test("builds no query when no senders exist") {
      Collector.query(Nil, 7) ==> None
    }

    test("counts an ignored template as skipped, not failed") {
      val ignoreRules = Map(cake -> (EmailRule.Ignore(".rollover") :: rules(cake)))
      collect(List(email("a"), email("b", """<html><span class="rollover">x</span></html>""")), settings(ignoreRules)).map { collected =>
        (collected.ready.size, collected.failures, collected.skipped, collected.scanned) ==> (1, Nil, 1, 2)
      }
    }

    test("records which message failed and why") {
      val failing = email("b", """<html><div class="wrong">nope</div></html>""", subject = "Sao kê thẻ tín dụng", date = LocalDate.of(2026, 8, 21))
      collect(List(email("a"), failing)).map { collected =>
        collected.ready.size ==> 1
        collected.failures ==> List(FailedEmail("b", cake, "Sao kê thẻ tín dụng", Some(LocalDate.of(2026, 8, 21)), s"No rule matched this email from $cake"))
      }
    }

    test("identifies a message that could not be fetched") {
      Collector.collect(FakeMailbox(List(email("a")), failure = Some("gmail exploded")), settings(), NoLog).map { collected =>
        collected.failures ==> List(FailedEmail("a", "", "", None, "gmail exploded"))
      }
    }

    test("builds an entry with the extracted fields") {
      collect(List(email("a"))).map { collected =>
        collected.ready ==> List(EntryDraft(LocalDate.of(2026, 8, 18), Money.fromMinorUnits(-5000000), "Gong Cha Tea", "Gong Cha Tea", "CAKE123", None, None, None).assignTo("acct1"))
      }
    }

    test("carries the transfer account to the entry") {
      val transfers = Map(cake -> List(rule(transferSelector = Some(".credit-account"), transferMap = Map("0900000002" -> "acct-cake"))))
      val html = """<html><span class="amount-value">50.000 đ</span><span class="payee-name">X</span><span class="credit-account">0900000002</span></html>"""
      collect(List(email("a", html)), settings(transfers)).map(collected => collected.ready.head.draft.transferAccountId ==> Some("acct-cake"))
    }

    test("uses the Gmail message ID and the header date when the email has none") {
      collect(List(email("x", htmlMinimal, date = LocalDate.of(2026, 8, 10)))).map { collected =>
        val draft = collected.ready.head.draft
        (draft.date, draft.amount, draft.payee, draft.importedId) ==> (LocalDate.of(2026, 8, 10), Money.fromMinorUnits(-2000000), "Grab", "x")
      }
    }

    test("applies payee mappings and keeps the raw payee") {
      val html = """<html><span class="amount-value">50.000 đ</span><span class="payee-name">Vnpay_0314658576 Hcm Vn</span></html>"""
      collect(List(email("a", html)), settings(payees = List(PayeeMapping(List("vnpay"), "VNPay", None, None)))).map { collected =>
        val draft = collected.ready.head.draft
        (draft.payee, draft.importedPayee) ==> ("VNPay", "Vnpay_0314658576 Hcm Vn")
      }
    }

    test("resolves a mapped category name to its Actual ID") {
      val html = """<html><span class="amount-value">44.982 đ</span><span class="payee-name">Google YouTube 650-2530000 US</span></html>"""
      val youtube = PayeeMapping(List("youtube"), "Youtube", None, Some("🗓️ Bills"))
      collect(List(email("a", html)), settings(payees = List(youtube), categoryIds = Map("🗓️ Bills" -> "cat-bills"))).map { collected =>
        collected.ready.head.draft.categoryId ==> Some("cat-bills")
      }
    }

    test("leaves the entry without a category when the category name is unknown") {
      collect(List(email("a")), settings(payees = List(PayeeMapping(List("gong cha"), "Gong Cha", None, Some("🥒 Food"))))).map { collected =>
        collected.ready.head.draft.categoryId ==> None
      }
    }

    test("routes the entry to the account in the email") {
      val routing = Map(cake -> List(rule(accountSelector = Some(".account-id"), accountMap = Map("••1111" -> "card-acct"))))
      val html = """<html><span class="amount-value">50.000 đ</span><span class="payee-name">X</span><span class="account-id">••1111</span></html>"""
      collect(List(email("a", html)), settings(routing, fallbackAccountId = Some("fallback-acct"))).map { collected =>
        collected.ready.head.accountId ==> "card-acct"
      }
    }

    test("holds back an entry when no account resolves and no fallback exists") {
      val routing = Map(cake -> List(rule(accountSelector = Some(".account-id"), accountChoices = List("acct-a", "acct-b"))))
      collect(List(email("a", routedHtml)), settings(routing, fallbackAccountId = None)).map { collected =>
        collected.ready ==> Nil
        val pending = collected.pending.head
        (pending.messageId, pending.accountKey, pending.sender, pending.ruleIndex, pending.choices) ==> ("a", Some("0900000001"), cake, 0, List("acct-a", "acct-b"))
      }
    }

    test("uses the fallback account when one exists") {
      val routing = Map(cake -> List(rule(accountSelector = Some(".account-id"))))
      collect(List(email("a", routedHtml)), settings(routing, fallbackAccountId = Some("fallback-acct"))).map { collected =>
        (collected.pending, collected.ready.head.accountId) ==> (Nil, "fallback-acct")
      }
    }

    // Dedup needs a stable imported ID, so the bank reference wins over the Gmail message ID.
    test("uses the bank reference as the imported ID") {
      collect(List(email("gmail-msg-id-1"))).map(collected => collected.ready.head.draft.importedId ==> "CAKE123")
    }

    test("records a parse failure for an unknown sender") {
      collect(List(email("bad", sender = "unknown@bank.com"))).map { collected =>
        (collected.ready, collected.failures.map(_.reason)) ==> (Nil, List("Unmapped sender: unknown@bank.com"))
      }
    }

    test("does not list messages when no senders exist") {
      val mailbox = FakeMailbox(List(email("a")))
      Collector.collect(mailbox, settings(rules = Map.empty), NoLog).map { collected =>
        (collected.ready, collected.scanned, mailbox.listCalls) ==> (Nil, 0, 0)
      }
    }
  }
}
