package dev.tobins.mealplanner.server

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tobins.mealplanner.server.db.Database
import java.io.File
import java.util.Properties
import java.util.WeakHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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

/**
 * Runs [body] in a transaction that takes the write lock before it reads, as BEGIN IMMEDIATE would; for
 * every transaction that reads and then writes what it read. SQLDelight begins every transaction deferred
 * and can't be told otherwise: it reads from a snapshot, and its first write then fails at once with
 * "database is locked" (SQLITE_BUSY_SNAPSHOT) when another connection wrote since (the offer refresh, the
 * price lookup, another device), as the busy timeout does not wait for a reader that would write. Taken
 * first, by a statement that changes nothing, the lock is waited for instead, and what is read is what
 * gets written over.
 *
 * Callers in this process also take their turn before the transaction begins ([writerLock], issue 246):
 * on a file each thread has a connection of its own and SQLite alone would keep them apart, but only for
 * the busy timeout, polling and in no order, while several accounts' imports of the cooking site start at
 * once. And an in-memory database has one connection that every thread shares, with one current
 * transaction: there two writers at once began a second transaction on it ("cannot start a transaction
 * within a transaction"), or one joined the other's and was rolled back with it.
 *
 * The lock is the whole database's, so [body] must do no network calls or other slow work: every other
 * writer waits for it. Called inside another transaction, it joins that one; the outer one must then have
 * taken the lock first too, or its reads came before it. Called inside [writing] on the same thread, it
 * joins it as well: the turn is the thread's, and is taken again rather than waited for.
 */
fun <T> Database.writing(body: () -> T): T = writerLock().withLock {
    transactionWithResult {
        userQueries.lockForWriting()
        body()
    }
}

/** One turn to write per open database, kept as long as the database is in use. */
private val writerLocks = WeakHashMap<Database, ReentrantLock>()

/**
 * The turn of [writing] in this database. Reentrant, as writers call others: marking a meal eaten or not
 * ([PlanRepository.setStatus]) and using up a bought item ([ShoppingListRepository.useUp]) change the stock
 * ([StockRepository]) inside their own. A thread's turn suits [writing], whose body does not suspend: the
 * transaction is the thread's too, and on a file database so is its connection.
 */
private fun Database.writerLock(): ReentrantLock = synchronized(writerLocks) { writerLocks.getOrPut(this) { ReentrantLock() } }
