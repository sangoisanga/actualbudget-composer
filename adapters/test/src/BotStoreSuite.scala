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

  private def savedCron(saves: String*): Option[String] = withStore { store =>
    saves.foreach(store.savePollCron)
    store.pollCron
  }

  val tests = Tests {
    test("refresh token") {
      test("returns nothing when empty") { savedToken() ==> None }
      test("returns the saved token") { savedToken("token-1") ==> Some("token-1") }
      test("replaces the earlier token") { savedToken("token-1", "token-2") ==> Some("token-2") }
    }

    test("poll cron") {
      test("returns nothing when empty") { savedCron() ==> None }
      test("returns the saved cron") { savedCron("0 */6 * * *") ==> Some("0 */6 * * *") }
      test("replaces the earlier cron") { savedCron("0 */6 * * *", "30 8 * * 1-5") ==> Some("30 8 * * 1-5") }
      test("returns nothing after a clear") {
        withStore { store =>
          store.savePollCron("0 */6 * * *")
          store.clearPollCron()
          store.pollCron ==> None
        }
      }
    }

    test("keeps the token after a cron save") {
      withStore { store =>
        store.saveRefreshToken("token-1")
        store.savePollCron("0 */6 * * *")
        store.refreshToken ==> Some("token-1")
      }
    }
  }
}
