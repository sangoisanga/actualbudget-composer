package composer.adapters.sqlite

import composer.adapters.facade.DatabaseSync

final class BotStore private (database: DatabaseSync) {
  private val selectToken = database.prepare("SELECT refresh_token FROM gmail_token WHERE id = 1")
  private val upsertToken = database.prepare("INSERT INTO gmail_token (id, refresh_token) VALUES (1, ?) ON CONFLICT(id) DO UPDATE SET refresh_token = excluded.refresh_token")
  private val selectCron = database.prepare("SELECT cron FROM poll_schedule WHERE id = 1")
  private val upsertCron = database.prepare("INSERT INTO poll_schedule (id, cron) VALUES (1, ?) ON CONFLICT(id) DO UPDATE SET cron = excluded.cron")
  private val deleteCron = database.prepare("DELETE FROM poll_schedule WHERE id = 1")

  def refreshToken: Option[String] = selectToken.get().toOption.flatMap(_.get("refresh_token")).map(_.toString)

  def saveRefreshToken(token: String): Unit = upsertToken.run(token)

  def pollCron: Option[String] = selectCron.get().toOption.flatMap(_.get("cron")).map(_.toString)

  def savePollCron(cron: String): Unit = upsertCron.run(cron)

  def clearPollCron(): Unit = deleteCron.run()

  def close(): Unit = database.close()
}

object BotStore {
  def open(path: String): BotStore = {
    val database = DatabaseSync(path)
    database.exec("CREATE TABLE IF NOT EXISTS gmail_token (id INTEGER PRIMARY KEY CHECK (id = 1), refresh_token TEXT NOT NULL)")
    database.exec("CREATE TABLE IF NOT EXISTS poll_schedule (id INTEGER PRIMARY KEY CHECK (id = 1), cron TEXT NOT NULL)")
    new BotStore(database)
  }
}
