package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.RegistrationRequest
import kotlinx.coroutines.delay
import java.util.Collections
import kotlin.time.Duration

/**
 * The registration helper and its directory, in memory, doing what `scripts/registration-helper.py` does (its own test
 * runs it against a fake LLDAP): users with their groups, the password each was given, and the user names it made that
 * still wait, the only ones it approves or deletes.
 */
class FakeDirectory(private val group: String = "mealplanner") : DirectoryHelper {
    val users: MutableMap<String, MutableList<String>> = Collections.synchronizedMap(mutableMapOf())
    val passwords: MutableMap<String, String> = Collections.synchronizedMap(mutableMapOf())
    private val made = Collections.synchronizedSet(mutableSetOf<String>())

    /** Every call, as `register anna`, in order. */
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** What each call answers without doing anything: [HelperAnswer.FAILED] for a helper that is down, or [HelperAnswer.UNSURE]. */
    var failing: HelperAnswer? = null

    /** How long each call takes, so that two can overlap. */
    var takes: Duration = Duration.ZERO

    override suspend fun register(request: RegistrationRequest) = call("register ${request.username}") {
        if (request.username in users) return@call HelperAnswer.TAKEN
        users[request.username] = mutableListOf()
        passwords[request.username] = request.password
        made += request.username
        HelperAnswer.DONE
    }

    override suspend fun approve(username: String) = call("approve $username") {
        own(username) ?: HelperAnswer.DONE.also { users.getValue(username) += group }
    }

    override suspend fun reject(username: String) = call("reject $username") {
        own(username) ?: HelperAnswer.DONE.also { users.remove(username) }
    }

    /** Null when [name] is a user it made that is still in no group; the refusal otherwise. Forgets it either way once decided. */
    private fun own(name: String): HelperAnswer? = when {
        name !in made -> HelperAnswer.UNKNOWN
        users[name] == null -> HelperAnswer.UNKNOWN.also { made -= name }
        users.getValue(name).isNotEmpty() -> HelperAnswer.IN_GROUP.also { made -= name }
        else -> null.also { made -= name }
    }

    private suspend fun call(what: String, act: () -> HelperAnswer): HelperAnswer {
        calls += what
        delay(takes)
        return failing ?: act()
    }
}
