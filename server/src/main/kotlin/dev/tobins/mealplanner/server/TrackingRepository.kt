package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.DayView
import dev.tobins.mealplanner.api.LogEntry
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.WeightEntry
import dev.tobins.mealplanner.api.WeightLog
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.tracking.Weighing
import dev.tobins.mealplanner.tracking.WeightTrend
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** What was actually eaten and weighed, next to what was planned. */
class TrackingRepository(
    private val db: Database,
    private val userId: Long,
    private val plans: PlanRepository,
    private val profiles: ProfileRepository,
) {
    private val q = db.trackingQueries

    fun day(date: String): DayView {
        val day = parseDate(date)
        val (weekStart, index, meals) = plans.mealsOn(day)
        val extras = q.selectLogForDate(userId, day.toString()).executeAsList().map { LogEntry(it.id, it.date, it.description, it.kcal, it.proteinG) }
        val eaten = meals.filter { it.status == MealStatus.EATEN }
        val expected = meals.filter { it.status != MealStatus.SKIPPED }
        return DayView(
            date = day.toString(),
            weekStart = weekStart.toString(),
            dayIndex = index,
            targets = profiles.state().targets,
            meals = meals,
            extras = extras,
            // A meal counts with its side (issue 234), which is eaten or skipped with it.
            eatenKcal = eaten.sumOf { it.totalKcal } + extras.sumOf { it.kcal },
            eatenProteinG = eaten.sumOf { it.totalProteinG } + extras.sumOf { it.proteinG },
            expectedKcal = expected.sumOf { it.totalKcal } + extras.sumOf { it.kcal },
            expectedProteinG = expected.sumOf { it.totalProteinG } + extras.sumOf { it.proteinG },
            // Extras are logged without fibre (issue 223 leaves them at none).
            eatenFibreG = eaten.sumOf { (it.fibreG ?: 0.0) + (it.side?.fibreG ?: 0.0) },
            expectedFibreG = expected.sumOf { (it.fibreG ?: 0.0) + (it.side?.fibreG ?: 0.0) },
        )
    }

    fun log(entry: LogEntry): DayView {
        val day = parseDate(entry.date)
        require(entry.description.isNotBlank()) { "description must not be blank" }
        require(entry.kcal in 0.0..10000.0 && entry.proteinG in 0.0..1000.0) { "kcal and proteinG must be plausible, non-negative numbers" }
        db.writing { q.insertLog(userId, day.toString(), entry.description.trim(), entry.kcal, entry.proteinG) }
        return day(day.toString())
    }

    fun deleteLog(id: Long) = db.writing { q.deleteLog(userId, id) }

    fun weights(): WeightLog {
        val entries = WeightTrend.withTrend(
            q.selectWeights(userId).executeAsList().map { WeightEntry(it.date, it.kg) },
            dayOf = { LocalDate.parse(it).toEpochDay() },
        )
        val loss = WeightTrend.lossPerWeek(entries.map { Weighing(LocalDate.parse(it.date).toEpochDay(), it.kg) })
        val profile = profiles.profile()
        val targets = profiles.state().targets
        return WeightLog(
            entries = entries,
            actualKgPerWeek = loss,
            suggestion = if (loss != null && profile != null && targets != null) WeightTrend.suggest(profile, targets, loss) else null,
        )
    }

    /**
     * Records a weighing (a second one for the same day replaces the first). The newest weighing is
     * the profile's weight, which the targets follow: a weighing for an earlier day leaves the profile alone.
     */
    fun setWeight(date: String, kg: Double): WeightLog {
        val day = parseDate(date)
        require(kg in 30.0..300.0) { "kg must be between 30 and 300" }
        db.writing {
            q.upsertWeight(userId, day.toString(), kg)
            if (isNewest(day)) followNewestWeighing()
        }
        return weights()
    }

    /**
     * Removes a weighing. Removing the newest one takes its effect back: the profile's weight becomes
     * that of the newest weighing left. Removing an older one changes nothing. When it was the only
     * one the profile keeps its weight: what it was before the first weighing is not stored.
     */
    fun deleteWeight(date: String): WeightLog {
        val day = parseDate(date)
        db.writing {
            val wasNewest = isNewest(day)
            q.deleteWeight(userId, day.toString())
            if (wasNewest) followNewestWeighing()
        }
        return weights()
    }

    /** The day of the newest weighing; null when there is none. */
    fun lastWeighed(): LocalDate? = q.selectWeights(userId).executeAsList().lastOrNull()?.let { LocalDate.parse(it.date) }

    /** Whether there is a weighing on [day] and none after it. */
    private fun isNewest(day: LocalDate): Boolean =
        q.selectWeights(userId).executeAsList().lastOrNull()?.date == day.toString()

    /** Makes the newest weighing the profile's weight, when there is one and a profile. */
    private fun followNewestWeighing() {
        val newest = q.selectWeights(userId).executeAsList().lastOrNull() ?: return
        profiles.profile()?.takeIf { it.weightKg != newest.kg }?.let { profiles.save(it.copy(weightKg = newest.kg)) }
    }

    private fun parseDate(date: String): LocalDate = try {
        LocalDate.parse(date)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("date must be an ISO date such as 2026-11-16")
    }
}
