package dev.tobins.mealplanner.server

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import dev.tobins.mealplanner.server.db.Database

/**
 * A driver that fails the statements [refuse] picks, as [DatabaseBusyException] does when a write waited too
 * long for its turn: for the jobs that must go on after one write gave up (issue 285). It refuses nothing at first.
 */
class RefusingWrites(private val delegate: SqlDriver) : SqlDriver by delegate {
    /** Which statements (by their SQL) fail; replaced by a test while it runs. */
    @Volatile
    var refuse: (String) -> Boolean = { false }

    val database = Database(this)

    override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
        if (refuse(sql)) throw DatabaseBusyException("Refused for the test: $sql")
        return delegate.execute(identifier, sql, parameters, binders)
    }
}
