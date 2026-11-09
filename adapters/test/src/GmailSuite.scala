package composer.adapters

import composer.adapters.facade.MessagePart
import composer.adapters.gmail.{GmailAuth, GmailMessages}
import utest.*

import scala.scalajs.js

object GmailSuite extends TestSuite {
  private val authCode = Right("4/0AbC")
  private val missingCode = Left("Callback URL has no code parameter")
  private val messageId = Right("1a0581496579ffb6")

  val tests = Tests {
    test("extracts the address from a display-name header") {
      GmailMessages.extractAddress(Some("\"Cake by VPBank\" <no-reply@cake.vn>")) ==> "no-reply@cake.vn"
    }

    test("extracts a bare address") {
      GmailMessages.extractAddress(Some("no-reply@cake.vn")) ==> "no-reply@cake.vn"
    }

    test("returns an empty address when the header is missing") {
      GmailMessages.extractAddress(None) ==> ""
    }

    test("decodes the HTML part of a multipart message") {
      val payload = js.JSON.parse(Fixtures.read("gmail-message.json")).payload.asInstanceOf[MessagePart]
      assert(GmailMessages.extractHtmlBody(payload).contains("Gong Cha Tea"))
    }

    test("auth code") {
      test("reads the code from a callback URL") { GmailAuth.extractAuthCode("http://localhost/?code=4%2F0AbC&scope=gmail.readonly") ==> authCode }
      test("returns a bare code") { GmailAuth.extractAuthCode("4/0AbC") ==> authCode }
      test("trims padding") { GmailAuth.extractAuthCode("  4/0AbC \n") ==> authCode }
      test("rejects a URL with no code") { GmailAuth.extractAuthCode("http://localhost/?scope=gmail.readonly") ==> missingCode }
      test("rejects a URL with an error") { GmailAuth.extractAuthCode("http://localhost/?error=access_denied") ==> missingCode }
    }

    test("message ID") {
      test("reads a bare ID") { GmailAuth.extractMessageId(" 1a0581496579ffb6 ") ==> messageId }
      test("reads an all-mail link") { GmailAuth.extractMessageId("https://mail.google.com/mail/u/0/#all/1a0581496579ffb6") ==> messageId }
      test("reads an inbox link") { GmailAuth.extractMessageId("https://mail.google.com/mail/u/0/#inbox/1a0581496579ffb6") ==> messageId }
      test("rejects a link with no ID") { assert(GmailAuth.extractMessageId("https://mail.google.com/mail/u/0/").left.exists(_.startsWith("Gmail link has no message ID"))) }
    }

    test("fixtureName") {
      test("cake") { GmailAuth.fixtureName("no-reply@cake.vn", "abc") ==> "cake-abc.html" }
      test("vpbank") { GmailAuth.fixtureName("vpbankonline@vpb.com.vn", "abc") ==> "vpbank-abc.html" }
      test("unknown bank") { GmailAuth.fixtureName("alerts@newbank.com", "abc") ==> "newbank-abc.html" }
    }
  }
}
