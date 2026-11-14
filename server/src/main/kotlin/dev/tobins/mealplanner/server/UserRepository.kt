package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.server.db.Database
import java.time.Instant

/** Who a valid access token belongs to, as the identity provider describes them. */
data class Identity(
    /** The token's `sub`: stable for the account, never reused. */
    val subject: String,
    val username: String? = null,
    val displayName: String? = null,
)

/** A logged-in account: the principal of every authenticated call. */
data class Account(val id: Long, val subject: String, val username: String?, val displayName: String?)

/** Accounts and their small per-account settings. */
class UserRepository(private val db: Database) {
    private val q = db.userQueries

    /**
     * The account for [identity], created on its first login. The data from before accounts
     * existed (an unclaimed account) goes to [owner] if that username is set, otherwise to
     * whoever logs in first. [onCreated] runs once for a new account, e.g. to seed its catalogue.
     */
    // One at a time: an app's first requests arrive together, and must not claim or create twice.
    @Synchronized
    fun resolve(identity: Identity, owner: String? = null, onCreated: (Long) -> Unit = {}): Account = db.transactionWithResult {
        q.selectBySubject(identity.subject).executeAsOneOrNull()?.let { row ->
            if (identity.username != null && (row.username != identity.username || row.displayName != identity.displayName)) {
                q.updateNames(identity.username, identity.displayName, row.id)
            }
            return@transactionWithResult Account(row.id, identity.subject, identity.username ?: row.username, identity.displayName ?: row.displayName)
        }
        val unclaimed = q.selectUnclaimed().executeAsOneOrNull()
        val id = if (unclaimed != null && (owner.isNullOrBlank() || owner.equals(identity.username, ignoreCase = true))) {
            q.claimUser(identity.subject, identity.username, identity.displayName, unclaimed.id)
            unclaimed.id
        } else {
            q.insertUser(identity.subject, identity.username, identity.displayName, Instant.now().toString())
            q.lastUserId().executeAsOne()
        }
        Account(id, identity.subject, identity.username, identity.displayName).also { onCreated(id) }
    }

    fun all(): List<Long> = q.selectAllUsers().executeAsList().map { it.id }

    fun meta(userId: Long, key: String): String? = q.selectUserMeta(userId, key).executeAsOneOrNull()

    fun setMeta(userId: Long, key: String, value: String?) {
        if (value == null) q.deleteUserMeta(userId, key) else q.upsertUserMeta(userId, key, value)
    }

    /** Every account with a value for [key], with that value. */
    fun withMeta(key: String): Map<Long, String> = q.selectMetaForAll(key).executeAsList().associate { it.userId to it.value_ }
}
