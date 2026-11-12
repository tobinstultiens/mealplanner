package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.ProfileState
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.targets.TargetCalculator
import kotlinx.serialization.json.Json

/** The single user's profile, stored as one JSON value. */
class ProfileRepository(private val db: Database) {
    private val json = Json { ignoreUnknownKeys = true }

    fun profile(): Profile? =
        db.foodQueries.selectMeta(KEY).executeAsOneOrNull()?.let { json.decodeFromString<Profile>(it) }

    fun state(): ProfileState = profile().let { profile ->
        ProfileState(
            profile = profile,
            suggested = profile?.let(TargetCalculator::suggest),
            targets = profile?.let(TargetCalculator::targets),
            maintenanceKcal = profile?.let(TargetCalculator::maintenanceKcal),
        )
    }

    fun save(profile: Profile): ProfileState {
        val problems = TargetCalculator.problems(profile)
        require(problems.isEmpty()) { problems.joinToString("; ") }
        db.foodQueries.upsertMeta(KEY, json.encodeToString(profile))
        return state()
    }

    private companion object {
        const val KEY = "profile"
    }
}
