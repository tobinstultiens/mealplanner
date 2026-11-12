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
    private val plans: PlanRepository,
    private val profiles: ProfileRepository,
) {
    private val q = db.trackingQueries

    fun day(date: String): DayView {
        val day = parseDate(date)
        val (weekStart, index, meals) = plans.mealsOn(day)
        val extras = q.selectLogForDate(day.toString()).executeAsList().map { LogEntry(it.id, it.date, it.description, it.kcal, it.proteinG) }
        val eaten = meals.filter { it.status == MealStatus.EATEN }
        val expected = meals.filter { it.status != MealStatus.SKIPPED }
        return DayView(
            date = day.toString(),
            weekStart = weekStart.toString(),
            dayIndex = index,
            targets = profiles.state().targets,
            meals = meals,
            extras = extras,
            eatenKcal = eaten.sumOf { it.kcal } + extras.sumOf { it.kcal },
            eatenProteinG = eaten.sumOf { it.proteinG } + extras.sumOf { it.proteinG },
            expectedKcal = expected.sumOf { it.kcal } + extras.sumOf { it.kcal },
            expectedProteinG = expected.sumOf { it.proteinG } + extras.sumOf { it.proteinG },
        )
    }

    fun log(entry: LogEntry): DayView {
        val day = parseDate(entry.date)
        require(entry.description.isNotBlank()) { "description must not be blank" }
        require(entry.kcal in 0.0..10000.0 && entry.proteinG in 0.0..1000.0) { "kcal and proteinG must be plausible, non-negative numbers" }
        q.insertLog(day.toString(), entry.description.trim(), entry.kcal, entry.proteinG)
        return day(day.toString())
    }

    fun deleteLog(id: Long) = q.deleteLog(id)

    fun weights(): WeightLog {
        val entries = WeightTrend.withTrend(
            q.selectWeights().executeAsList().map { WeightEntry(it.date, it.kg) },
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

    /** Records a weighing. The newest weighing also becomes the profile's weight, which the targets follow. */
    fun setWeight(date: String, kg: Double): WeightLog {
        val day = parseDate(date)
        require(kg in 30.0..300.0) { "kg must be between 30 and 300" }
        db.transaction {
            q.upsertWeight(day.toString(), kg)
            val newest = q.selectWeights().executeAsList().last()
            profiles.profile()?.takeIf { it.weightKg != newest.kg }?.let { profiles.save(it.copy(weightKg = newest.kg)) }
        }
        return weights()
    }

    fun deleteWeight(date: String): WeightLog {
        q.deleteWeight(parseDate(date).toString())
        return weights()
    }

    private fun parseDate(date: String): LocalDate = try {
        LocalDate.parse(date)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("date must be an ISO date such as 2026-11-16")
    }
}
