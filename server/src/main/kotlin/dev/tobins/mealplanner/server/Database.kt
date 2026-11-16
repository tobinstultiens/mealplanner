package dev.tobins.mealplanner.server

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tobins.mealplanner.server.db.Database
import java.io.File
import java.util.Properties

/** Opens (and creates or migrates) the SQLite database at [url], e.g. `jdbc:sqlite:/data/mealplanner.db`. */
fun openDatabase(url: String): Pair<SqlDriver, Database> {
    url.removePrefix("jdbc:sqlite:").takeIf { it.isNotEmpty() && !it.startsWith(":") }
        ?.let { File(it).absoluteFile.parentFile?.mkdirs() }
    val properties = Properties().apply {
        put("foreign_keys", "true")
        // Requests run in parallel: wait for a lock instead of failing with SQLITE_BUSY, and let
        // readers go on while one request writes (WAL).
        put("busy_timeout", "10000")
        put("journal_mode", "WAL")
    }
    val driver = JdbcSqliteDriver(url, properties, Database.Schema)
    return driver to Database(driver)
}
