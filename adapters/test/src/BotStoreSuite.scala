package composer.adapters

import composer.adapters.sqlite.BotStore
import utest.*

object BotStoreSuite extends TestSuite {
  private def withStore[A](body: BotStore => A): A = {
    val store = BotStore.open(":memory:")
    try body(store)
    finally store.close()
  }

  private def savedToken(saves: String*): Option[String] = withStore { store =>
    saves.foreach(store.saveRefreshToken)
    store.refreshToken
  }

  private def savedInterval(saves: Int*): Option[Int] = withStore { store =>
    saves.foreach(store.savePollIntervalHours)
    store.pollIntervalHours
  }

  val tests = Tests {
    test("refresh token") {
      test("returns nothing when empty") { savedToken() ==> None }
      test("returns the saved token") { savedToken("token-1") ==> Some("token-1") }
      test("replaces the earlier token") { savedToken("token-1", "token-2") ==> Some("token-2") }
    }

    test("poll interval") {
      test("returns nothing when empty") { savedInterval() ==> None }
      test("returns the saved hours") { savedInterval(6) ==> Some(6) }
      test("replaces the earlier hours") { savedInterval(6, 24) ==> Some(24) }
    }

    test("keeps the token after an interval save") {
      withStore { store =>
        store.saveRefreshToken("token-1")
        store.savePollIntervalHours(6)
        store.refreshToken ==> Some("token-1")
      }
    }
  }
}
