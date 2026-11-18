package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.ProfileState
import dev.tobins.mealplanner.planner.PortionRange
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.targets.TargetCalculator
import kotlinx.serialization.json.Json

/** One account's profile, stored as one JSON value. */
class ProfileRepository(private val db: Database, private val userId: Long) {
    // Defaults are written out, so that a profile keeps what was saved if a default changes later.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun profile(): Profile? =
        db.userQueries.selectUserMeta(userId, KEY).executeAsOneOrNull()?.let { json.decodeFromString<Profile>(it) }

    /** The portion range the planner sizes this account's meals in: the profile's, or the defaults without one. */
    fun portions(): PortionRange = PortionRange.of(profile())

    fun state(): ProfileState = profile().let { profile ->
        ProfileState(
            profile = profile,
            suggested = profile?.let(TargetCalculator::suggest),
            targets = profile?.let(TargetCalculator::targets),
            maintenanceKcal = profile?.let(TargetCalculator::maintenanceKcal),
        )
    }

    fun save(profile: Profile): ProfileState {
        val problems = TargetCalculator.problems(profile) + PortionRange.of(profile).problems()
        require(problems.isEmpty()) { problems.joinToString("; ") }
        db.userQueries.upsertUserMeta(userId, KEY, json.encodeToString(profile))
        return state()
    }

    /**
     * Saves [profile] as `PUT /api/profile` sent it, [sent] being the fields its body had. An app from
     * before the portion range (issue 227) sends neither of the range's fields: the stored range is kept
     * rather than reset to the defaults, as such an app neither shows the range nor means to change it.
     */
    fun save(profile: Profile, sent: Set<String>): ProfileState = db.writing {
        val stored = profile()
        save(
            profile.copy(
                minPortions = if (Profile::minPortions.name in sent) profile.minPortions else stored?.minPortions ?: profile.minPortions,
                maxPortions = if (Profile::maxPortions.name in sent) profile.maxPortions else stored?.maxPortions ?: profile.maxPortions,
            ),
        )
    }

    private companion object {
        const val KEY = "profile"
    }
}
