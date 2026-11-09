package composer.adapters.sqlite

import composer.adapters.facade.DatabaseSync

final class BotStore private (database: DatabaseSync) {
  private val selectToken = database.prepare("SELECT refresh_token FROM gmail_token WHERE id = 1")
  private val upsertToken = database.prepare("INSERT INTO gmail_token (id, refresh_token) VALUES (1, ?) ON CONFLICT(id) DO UPDATE SET refresh_token = excluded.refresh_token")
  private val selectInterval = database.prepare("SELECT poll_interval_hours FROM bot_settings WHERE id = 1")
  private val upsertInterval = database.prepare("INSERT INTO bot_settings (id, poll_interval_hours) VALUES (1, ?) ON CONFLICT(id) DO UPDATE SET poll_interval_hours = excluded.poll_interval_hours")

  def refreshToken: Option[String] = selectToken.get().toOption.flatMap(_.get("refresh_token")).map(_.toString)

  def saveRefreshToken(token: String): Unit = upsertToken.run(token)

  def pollIntervalHours: Option[Int] = selectInterval.get().toOption.flatMap(_.get("poll_interval_hours")).flatMap(_.toString.toIntOption)

  def savePollIntervalHours(hours: Int): Unit = upsertInterval.run(hours)

  def close(): Unit = database.close()
}

object BotStore {
  def open(path: String): BotStore = {
    val database = DatabaseSync(path)
    database.exec("CREATE TABLE IF NOT EXISTS gmail_token (id INTEGER PRIMARY KEY CHECK (id = 1), refresh_token TEXT NOT NULL)")
    database.exec("CREATE TABLE IF NOT EXISTS bot_settings (id INTEGER PRIMARY KEY CHECK (id = 1), poll_interval_hours INTEGER NOT NULL)")
    new BotStore(database)
  }
}
