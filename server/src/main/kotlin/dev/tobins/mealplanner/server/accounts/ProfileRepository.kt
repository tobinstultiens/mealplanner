package dev.tobins.mealplanner.server.accounts

import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.ProfileState
import dev.tobins.mealplanner.planner.PortionRange
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.writing
import dev.tobins.mealplanner.targets.TargetCalculator
import kotlinx.serialization.json.Json
import kotlin.reflect.KProperty1

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
        db.writing { db.userQueries.upsertUserMeta(userId, KEY, json.encodeToString(profile)) }
        return state()
    }

    /**
     * Saves [profile] as `PUT /api/profile` sent it, [sent] being the fields its body had. An app from
     * before a field sends a profile without it: the stored value is kept rather than reset to the
     * default, as such an app neither shows the field nor means to change it. So are the portion range
     * (issue 227), the priority and the fibre target (issue 223). An app that knows the fibre target
     * sends it as null to have none.
     */
    fun save(profile: Profile, sent: Set<String>): ProfileState = db.writing {
        val stored = profile()
        fun <T> kept(field: KProperty1<Profile, T>): T = field.get(if (field.name in sent || stored == null) profile else stored)
        save(
            profile.copy(
                minPortions = kept(Profile::minPortions),
                maxPortions = kept(Profile::maxPortions),
                priority = kept(Profile::priority),
                fibreOverrideG = kept(Profile::fibreOverrideG),
            ),
        )
    }

    private companion object {
        const val KEY = "profile"
    }
}
