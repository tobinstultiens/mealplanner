package dev.tobins.mealplanner.server

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tobins.mealplanner.server.db.Database
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration
import java.util.Properties
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.concurrent.locks.ReentrantLock

/**
 * Opens (and creates or migrates) the SQLite database at [url], e.g. `jdbc:sqlite:/data/mealplanner.db`. A writer
 * waits up to [writeWait] for its turn ([writing]); tests make that short to see one give up.
 */
fun openDatabase(url: String, writeWait: Duration = WRITE_WAIT): Pair<SqlDriver, Database> {
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
    val database = Database(driver)
    synchronized(writerTurns) { writerTurns[database] = WriterTurn(writeWait) }
    return driver to database
}

/**
 * Runs [body] in a transaction that takes the write lock before it reads, as BEGIN IMMEDIATE would; for
 * every write, and every transaction that reads and then writes what it read. SQLDelight begins every
 * transaction deferred and can't be told otherwise: it reads from a snapshot, and its first write then fails
 * at once with "database is locked" (SQLITE_BUSY_SNAPSHOT) when another connection wrote since, as the busy
 * timeout does not wait for a reader that would write. Taken first, by a statement that changes nothing, the
 * lock is waited for instead, and what is read is what gets written over. Writers of this process wait for
 * their turn before that (below), so this is for writers outside it: a second server process, the sqlite3 shell.
 *
 * Callers in this process also take their turn before the transaction begins ([WriterTurn], issues 246 and
 * 248): on a file each thread has a connection of its own and SQLite alone would keep them apart, but only for
 * the busy timeout, polling and in no order, while several accounts' imports of the cooking site start at
 * once. And an in-memory database has one connection that every thread shares, with one current
 * transaction: there two writers at once began a second transaction on it ("cannot start a transaction
 * within a transaction"), or one joined the other's and was rolled back with it. So every write of the
 * server goes through here, a single statement too: on that shared connection, one outside would run
 * inside whichever transaction another thread has open.
 *
 * The turn is the whole database's, so [body] must do no network calls or other slow work: every other
 * writer waits for it, and gives up with [DatabaseBusyException] after the database's wait ([WRITE_WAIT]).
 * [body] is no `suspend` function, so it stays on the thread that took the turn. Called inside another
 * transaction, it joins that one; the outer one must then have taken the lock first too, or its reads came
 * before it. Called inside [writing] on the same thread, it joins it as well, without waiting: the turn is the
 * thread's, and is taken again rather than waited for. The two then commit or are taken back together.
 */
fun <T> Database.writing(body: () -> T): T = writerTurn().holding {
    transactionWithResult {
        userQueries.lockForWriting()
        body()
    }
}

/**
 * How long a writer waits for its turn before it gives up: a request must not hang behind a writer that
 * hangs, and must not fail behind writers that are only many, such as a nightly job's. Three times SQLite's
 * busy timeout, which was all writers waited for each other before issue 246; the server's long jobs write in
 * short turns (one account's import of its cooking site, one store's offers, one price found).
 */
val WRITE_WAIT: Duration = Duration.ofSeconds(30)

/** Thrown by [writing] when it waited the database's whole wait for its turn; answered with 503 ([errorMapping]). */
class DatabaseBusyException(message: String) : RuntimeException(message)

/** Where the writer that held the turn was when another gave up waiting for it, for the log of [DatabaseBusyException]. */
private class HolderStack(thread: Thread) : RuntimeException("The writer holding the turn, on thread ${thread.name}, is at") {
    init {
        stackTrace = thread.stackTrace
    }
}

/**
 * The turn of [writing] in one database. Reentrant, as writers call others: marking a meal eaten or not
 * ([PlanRepository.setStatus]) and using up a bought item ([ShoppingListRepository.useUp]) change the stock
 * ([StockRepository]) inside their own. A thread's turn suits [writing], whose body does not suspend: the
 * transaction is the thread's too, and on a file database so is its connection.
 *
 * Fair, first come first served: with a wait limit, a writer must not be passed over until it gives up while
 * later ones keep taking the turn. A writer that held it for more than a third of the [wait] is logged as it lets
 * go: three such in a row would make the next one give up.
 */
private class WriterTurn(private val wait: Duration) {
    private val lock = ReentrantLock(true)

    @Volatile
    private var holder: Thread? = null

    @Volatile
    private var since = 0L

    fun <T> holding(body: () -> T): T {
        take()
        try {
            return body()
        } finally {
            letGo()
        }
    }

    private fun take() {
        if (!lock.tryLock(wait.toNanos(), NANOSECONDS)) throw busy()
        if (lock.holdCount == 1) {
            // In this order: whoever sees the holder ([busy]) sees when it began.
            since = System.nanoTime()
            holder = Thread.currentThread()
        }
    }

    private fun letGo() {
        if (lock.holdCount == 1) {
            holder = null
            val held = Duration.ofNanos(System.nanoTime() - since)
            if (held > wait.dividedBy(3)) {
                // This frame is in the writer's own function; the first one outside this file is who wrote.
                val writer = Throwable().stackTrace.firstOrNull { it.fileName != "Database.kt" }
                log.warn("A write held the database for ${held.toMillis()} ms (${writer ?: "unknown"}); other writers wait for it, and give up after ${wait.toMillis()} ms")
            }
        }
        lock.unlock()
    }

    /** The error for a writer that waited [wait] in vain, with where the one holding the turn is now. */
    private fun busy(): DatabaseBusyException {
        val thread = holder
        val held = Duration.ofNanos(System.nanoTime() - since).toMillis()
        val who = if (thread == null) "another write" else "a write on thread ${thread.name}, writing for $held ms,"
        return DatabaseBusyException("Gave up writing after waiting ${wait.toMillis()} ms for its turn: $who has it").apply {
            thread?.let { addSuppressed(HolderStack(it)) }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(WriterTurn::class.java)
    }
}

/** One turn to write per open database, kept as long as the database is in use. */
private val writerTurns = WeakHashMap<Database, WriterTurn>()

/** The turn of [writing] in this database; one not opened by [openDatabase] (a test's own driver) waits [WRITE_WAIT]. */
private fun Database.writerTurn(): WriterTurn = synchronized(writerTurns) { writerTurns.getOrPut(this) { WriterTurn(WRITE_WAIT) } }
