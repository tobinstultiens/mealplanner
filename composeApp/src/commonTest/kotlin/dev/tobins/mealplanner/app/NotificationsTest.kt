package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.DinnerToday
import dev.tobins.mealplanner.api.EndingOffer
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.NotificationFacts
import dev.tobins.mealplanner.api.PrepPastLastDay
import dev.tobins.mealplanner.api.WeighInDue
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** What the background check posts (issue 225): by switch, window and what was told already; the server's conditions are its own tests. */
@OptIn(ExperimentalTime::class)
class NotificationsTest {
    private val storage = MemorySettingsStorage()
    private val en = Strings.English

    /** A planner whose device clock shows [time] (`HH:MM`) on [day]; in UTC, the zone every platform knows. */
    private fun at(time: String, day: String = "2026-11-21") = NotificationPlanner(
        storage,
        object : Clock {
            override fun now() = Instant.parse("${day}T$time:00Z")
        },
        TimeZone.UTC,
    )

    /** Every kind due on Saturday 21 November. */
    private val all = NotificationFacts(
        date = "2026-11-21",
        offersEnding = listOf(EndingOffer("Chicken thighs", "2026-11-21"), EndingOffer("Quark", "2026-11-21"), EndingOffer("Yoghurt", "2026-11-21")),
        planNextWeek = "2026-11-23",
        weighIn = WeighInDue("2026-11-12", 9),
        dinner = DinnerToday(12, "Chicken tikka", 1.5, 2.25, "2026-11-16", 5),
        mealsToMark = 2,
        prepsPastLastDay = listOf(PrepPastLastDay(7, 14, "Chili con carne", "2026-11-17", "2026-11-20", left = 1)),
    )
    private val update = AppUpdate("0.1.9", "", "", "")

    private fun allOn() = NotificationKind.entries.forEach { storage.saveNotificationOn(it, true) }

    private fun NotificationPlanner.kinds(facts: NotificationFacts? = all, newer: AppUpdate? = update) = due(facts, newer, en).map { it.kind }

    @Test
    fun theFirstThreeKindsAndPreppedFoodToThrowAwayAreOnUntilSwitched() {
        val on = listOf(NotificationKind.UPDATE, NotificationKind.OFFERS_ENDING, NotificationKind.PLAN_NEXT_WEEK, NotificationKind.THROW_AWAY)
        assertEquals(on, NotificationKind.entries.filter(storage::notificationOn))
        assertEquals(on, at("21:00").kinds())
        storage.saveNotificationOn(NotificationKind.OFFERS_ENDING, false)
        storage.saveNotificationOn(NotificationKind.MEALS_TO_MARK, true)
        assertEquals(
            listOf(NotificationKind.UPDATE, NotificationKind.PLAN_NEXT_WEEK, NotificationKind.MEALS_TO_MARK, NotificationKind.THROW_AWAY),
            at("21:00").kinds(),
        )
    }

    @Test
    fun eachKindWaitsForItsWindowAndNothingComesAtNight() {
        allOn()
        assertEquals(emptyList(), at("07:59").kinds())
        val morning = listOf(NotificationKind.UPDATE, NotificationKind.OFFERS_ENDING, NotificationKind.PLAN_NEXT_WEEK, NotificationKind.WEIGH_IN)
        assertEquals(morning + NotificationKind.THROW_AWAY, at("08:00").kinds())
        assertEquals(morning + NotificationKind.THROW_AWAY, at("14:59").kinds())
        assertEquals(morning + NotificationKind.DINNER_TODAY + NotificationKind.THROW_AWAY, at("15:00").kinds())
        assertEquals(NotificationKind.entries, at("20:00").kinds())
        assertEquals(NotificationKind.entries, at("21:59").kinds())
        assertEquals(emptyList(), at("22:00").kinds())
        assertEquals(emptyList(), at("03:00").kinds())
    }

    @Test
    fun theServerIsAskedOnlyWhenOneOfItsKindsCouldBePosted() {
        assertTrue(at("09:00").wantsFacts(), "offers ending and plan next week are on")
        assertFalse(at("23:00").wantsFacts())
        storage.saveNotificationOn(NotificationKind.OFFERS_ENDING, false)
        storage.saveNotificationOn(NotificationKind.PLAN_NEXT_WEEK, false)
        assertTrue(at("09:00").wantsFacts(), "prepped food to throw away is on")
        storage.saveNotificationOn(NotificationKind.THROW_AWAY, false)
        assertFalse(at("09:00").wantsFacts(), "only the update is on, which GitHub tells")
        storage.saveNotificationOn(NotificationKind.MEALS_TO_MARK, true)
        assertFalse(at("19:59").wantsFacts())
        assertTrue(at("20:00").wantsFacts())
    }

    @Test
    fun withoutFactsOnlyTheUpdateIsTold() {
        allOn()
        assertEquals(listOf(NotificationKind.UPDATE), at("21:00").kinds(facts = null))
        assertEquals(emptyList(), at("21:00").kinds(facts = null, newer = null))
        assertEquals(emptyList(), at("21:00").kinds(facts = NotificationFacts("2026-11-21"), newer = null), "nothing due")
    }

    /** Posts what is due at [time] on [day] and says which kinds that were. */
    private fun postAt(time: String, day: String, facts: NotificationFacts, newer: AppUpdate? = update): List<NotificationKind> {
        val planner = at(time, day)
        return planner.due(facts, newer, en).onEach(planner::told).map { it.kind }
    }

    @Test
    fun eachKindIsToldOnceForItsDayWeekOrVersion() {
        allOn()
        assertEquals(NotificationKind.entries, postAt("21:00", "2026-11-21", all))
        assertEquals(emptyList(), postAt("21:30", "2026-11-21", all), "all told today")

        // Sunday: the day's kinds again; the week to plan and the version were told; the weigh-in waits a week; Friday's
        // batch is past its last day for longer than a day.
        val sunday = all.copy(date = "2026-11-22", weighIn = WeighInDue("2026-11-12", 10))
        assertEquals(listOf(NotificationKind.OFFERS_ENDING, NotificationKind.DINNER_TODAY, NotificationKind.MEALS_TO_MARK), postAt("21:00", "2026-11-22", sunday))
        assertEquals(listOf(NotificationKind.UPDATE), postAt("21:30", "2026-11-22", sunday, AppUpdate("0.2.0", "", "", "")), "a newer version")

        // Six days after the weigh-in was told it is not told again; seven days after it is.
        val later = NotificationFacts("2026-11-27", weighIn = WeighInDue("2026-11-12", 15))
        assertEquals(emptyList(), postAt("09:00", "2026-11-27", later, newer = null))
        assertEquals(listOf(NotificationKind.WEIGH_IN), postAt("09:00", "2026-11-28", later.copy(date = "2026-11-28", weighIn = WeighInDue("2026-11-12", 16)), newer = null))

        // The next Saturday: another week to plan.
        assertEquals(listOf(NotificationKind.PLAN_NEXT_WEEK), postAt("09:00", "2026-11-28", NotificationFacts("2026-11-28", planNextWeek = "2026-11-30"), newer = null))
    }

    @Test
    fun theVersionTheOldUpdateCheckToldIsNotToldAgain() {
        at("09:00").toldBefore(NotificationKind.UPDATE, "0.1.9")
        assertEquals(emptyList(), postAt("09:00", "2026-11-21", NotificationFacts("2026-11-21"), update), "0.1.9 was told before the upgrade")
        assertEquals(listOf(NotificationKind.UPDATE), postAt("09:00", "2026-11-22", NotificationFacts("2026-11-22"), AppUpdate("0.2.0", "", "", "")))
        // Taken over only while nothing was told since: an older version from the old check changes nothing.
        at("09:00").toldBefore(NotificationKind.UPDATE, "0.1.9")
        assertEquals(emptyList(), postAt("10:00", "2026-11-22", NotificationFacts("2026-11-22"), AppUpdate("0.2.0", "", "", "")))
    }

    @Test
    fun aSwitchedOffKindIsNeitherPostedNorRememberedAsTold() {
        allOn()
        storage.saveNotificationOn(NotificationKind.MEALS_TO_MARK, false)
        assertFalse(NotificationKind.MEALS_TO_MARK in postAt("21:00", "2026-11-21", all))
        storage.saveNotificationOn(NotificationKind.MEALS_TO_MARK, true)
        assertEquals(listOf(NotificationKind.MEALS_TO_MARK), postAt("21:30", "2026-11-21", all), "turned on the same evening: told then")
    }

    private fun notice(kind: NotificationKind, facts: NotificationFacts = all, strings: Strings = en): DueNotification {
        allOn()
        return at("21:00").due(facts, update, strings).single { it.kind == kind }
    }

    @Test
    fun theTextsSayWhatIsDueInTheAppsLanguage() {
        assertEquals("Meal planner 0.1.9 is available", notice(NotificationKind.UPDATE).title)
        val offers = notice(NotificationKind.OFFERS_ENDING)
        assertEquals("3 offers for your list end today", offers.title)
        assertEquals("Chicken thighs, Quark, Yoghurt", offers.text)
        val tomorrow = all.copy(offersEnding = listOf(EndingOffer("Quark", "2026-11-22")))
        assertEquals("1 offer for your list ends tomorrow", notice(NotificationKind.OFFERS_ENDING, tomorrow).title)
        val both = all.copy(offersEnding = listOf(EndingOffer("Quark", "2026-11-21"), EndingOffer("Feta", "2026-11-22")))
        assertEquals("2 offers for your list end today or tomorrow", notice(NotificationKind.OFFERS_ENDING, both).title)
        assertEquals("Next week has no plan yet" to "Plan the week.", notice(NotificationKind.PLAN_NEXT_WEEK).let { it.title to it.text })
        assertEquals("Time for a weigh-in" to "The last one was 9 days ago", notice(NotificationKind.WEIGH_IN).let { it.title to it.text })
        assertEquals("Tonight: Chicken tikka" to "2.25 servings to cook", notice(NotificationKind.DINNER_TODAY).let { it.title to it.text })
        assertEquals("2 meals of today are not marked yet", notice(NotificationKind.MEALS_TO_MARK).title)

        val nl = Strings.Dutch
        assertEquals("3 aanbiedingen voor je lijst eindigen vandaag", notice(NotificationKind.OFFERS_ENDING, strings = nl).title)
        assertEquals("1 aanbieding voor je lijst eindigt morgen", notice(NotificationKind.OFFERS_ENDING, tomorrow, nl).title)
        assertEquals("Vanavond: Chicken tikka" to "2,25 porties om te koken", notice(NotificationKind.DINNER_TODAY, strings = nl).let { it.title to it.text })
        assertEquals("Meal planner 0.1.9 is beschikbaar", notice(NotificationKind.UPDATE, strings = nl).title)
        assertEquals("Volgende week heeft nog geen plan" to "Plan de week.", notice(NotificationKind.PLAN_NEXT_WEEK, strings = nl).let { it.title to it.text })
    }

    @Test
    fun eachOpensItsScreen() {
        assertEquals(null, notice(NotificationKind.UPDATE).opening, "the app, which offers the update")
        assertEquals(Opening(Route.TabRoute(Tab.SHOPPING)), notice(NotificationKind.OFFERS_ENDING).opening)
        assertEquals(Opening(Route.TabRoute(Tab.PLAN), week = "2026-11-23"), notice(NotificationKind.PLAN_NEXT_WEEK).opening, "the Plan, on next week")
        assertEquals(Opening(Route.TabRoute(Tab.PROGRESS)), notice(NotificationKind.WEIGH_IN).opening)
        assertEquals(Opening(Route.TabRoute(Tab.TODAY)), notice(NotificationKind.MEALS_TO_MARK).opening)
        // The recipe from that meal, with its servings to cook and the way to mark it eaten.
        val meal = MealToCook(12, 1.5, 2.25, skipped = false, MealPlace("2026-11-16", 5, MealSlot.DINNER), planned = true)
        assertEquals(Opening(Route.Recipe(12), meal), notice(NotificationKind.DINNER_TODAY).opening)
        assertEquals(Opening(Route.TabRoute(Tab.PLAN), week = "2026-11-16"), notice(NotificationKind.THROW_AWAY).opening, "the Plan, on the week it was made")
    }

    // --- Prepped food to throw away (issue 264)

    private val chili = PrepPastLastDay(7, 14, "Chili con carne", "2026-11-17", "2026-11-20", left = 1)
    private val salad = PrepPastLastDay(9, 15, "Lentil salad", "2026-11-18", "2026-11-20", side = true, left = 1, unmarked = 1, firstUnmarked = "2026-11-19")

    /** Saturday 21 November's facts with nothing due but [batches] past their last day. */
    private fun pastLastDay(vararg batches: PrepPastLastDay, date: String = "2026-11-21") = NotificationFacts(date, prepsPastLastDay = batches.toList())

    private fun throwAway(vararg batches: PrepPastLastDay, strings: Strings = en): Pair<String, String> =
        notice(NotificationKind.THROW_AWAY, pastLastDay(*batches), strings).let { it.title to it.text }

    @Test
    fun preppedFoodToThrowAwaySaysWhatWasLeftAndWhatWasNotMarked() {
        assertEquals("Throw away 1 portion of Chili con carne" to "It kept until Fri 20 Nov.", throwAway(chili))
        assertEquals("Gooi 1 portie Chili con carne weg" to "Het was goed tot vr 20 nov.", throwAway(chili, strings = Strings.Dutch))
        assertEquals(
            "Throw away 2 portions of Chili con carne" to "It kept until Fri 20 Nov. 1 portion of Chili con carne was not marked: eaten, or to throw away?",
            throwAway(chili.copy(left = 2, unmarked = 1, firstUnmarked = "2026-11-19")),
        )
        // Only meals not marked: the app cannot tell whether they were eaten, so it asks.
        assertEquals(
            "Prepped food to throw away" to "2 portions of Chili con carne were not marked: eaten, or to throw away? It kept until Fri 20 Nov.",
            throwAway(chili.copy(left = 0, unmarked = 2, firstUnmarked = "2026-11-18")),
        )
        assertEquals(
            "Geprept eten om weg te gooien" to "2 porties Chili con carne staan nog open: gegeten, of weggooien? Het was goed tot vr 20 nov.",
            throwAway(chili.copy(left = 0, unmarked = 2, firstUnmarked = "2026-11-18"), strings = Strings.Dutch),
        )
        // A side taken from a batch is a side in the box, and several batches go into one notification.
        assertEquals(
            "Throw away 1 side of Lentil salad" to "It kept until Fri 20 Nov. 1 side of Lentil salad was not marked: eaten, or to throw away?",
            throwAway(salad),
        )
        assertEquals(
            "Prepped food to throw away" to
                "Throw away 1 portion of Chili con carne. Throw away 1 side of Lentil salad. 1 side of Lentil salad was not marked: eaten, or to throw away? " +
                "They kept until Fri 20 Nov.",
            throwAway(chili, salad),
        )
        assertEquals(
            "Geprept eten om weg te gooien" to
                "Gooi 1 portie Chili con carne weg. Gooi 1 bijgerecht Lentil salad weg. 1 bijgerecht Lentil salad staat nog open: gegeten, of weggooien? " +
                "Ze waren goed tot vr 20 nov.",
            throwAway(chili, salad, strings = Strings.Dutch),
        )
    }

    @Test
    fun aTapOpensThePlanWhereAMealIsToMarkOrElseWhereTheBatchWasMade() {
        val madeLastWeek = chili.copy(madeOn = "2026-11-15")
        assertEquals(Opening(Route.TabRoute(Tab.PLAN), week = "2026-11-09"), notice(NotificationKind.THROW_AWAY, pastLastDay(madeLastWeek)).opening)
        val unmarked = madeLastWeek.copy(prepId = 8, unmarked = 1, firstUnmarked = "2026-11-17")
        assertEquals(Opening(Route.TabRoute(Tab.PLAN), week = "2026-11-16"), notice(NotificationKind.THROW_AWAY, pastLastDay(madeLastWeek, unmarked)).opening, "the day to mark")
    }

    @Test
    fun aBatchIsToldInTheMorningAfterItsLastDayAndOnlyOnce() {
        val facts = pastLastDay(chili)
        assertEquals(emptyList(), postAt("07:59", "2026-11-21", facts, newer = null), "the morning window opens at 08:00")
        assertEquals(listOf(NotificationKind.THROW_AWAY), postAt("08:05", "2026-11-21", facts, newer = null))
        assertEquals(emptyList(), postAt("15:05", "2026-11-21", facts, newer = null), "told once")

        // Another batch the same day is told of alone; the one told before is not told again.
        val both = pastLastDay(chili, salad)
        val later = at("20:05").due(both, null, en).single()
        assertEquals("Throw away 1 side of Lentil salad", later.title)
        at("20:05").told(later)
        assertEquals(emptyList(), postAt("21:00", "2026-11-21", both, newer = null))

        // A batch whose last day was more than a day before is not told, whatever the server says.
        assertEquals(emptyList(), postAt("08:05", "2026-11-22", pastLastDay(chili.copy(prepId = 8), date = "2026-11-22"), newer = null))
        assertEquals(listOf(NotificationKind.THROW_AWAY), postAt("08:05", "2026-11-22", pastLastDay(chili.copy(prepId = 8, lastDay = "2026-11-21"), date = "2026-11-22"), newer = null))
    }

    @Test
    fun switchedOffNoBatchIsToldNorRemembered() {
        storage.saveNotificationOn(NotificationKind.THROW_AWAY, false)
        assertFalse(at("09:00").wanted(NotificationKind.THROW_AWAY))
        assertEquals(emptyList(), postAt("09:00", "2026-11-21", pastLastDay(chili), newer = null))
        storage.saveNotificationOn(NotificationKind.THROW_AWAY, true)
        assertEquals(listOf(NotificationKind.THROW_AWAY), postAt("09:30", "2026-11-21", pastLastDay(chili), newer = null), "turned on the same morning: told then")
    }

    @Test
    fun everyWindowGetsARunWhateverThePhaseOfThePeriodicOne() {
        assertEquals(listOf(8, 15, 20), NotificationWindows.openingHours)
        // Starting at any minute of a day, the chain of runs (each asking for the next) has one just after every opening, on the days after too.
        val start = Instant.parse("2026-11-21T00:00:00Z")
        for (minute in 0 until 24 * 60) {
            var now = start + minute.minutes
            val runs = mutableListOf<Instant>()
            while (now < start + 3.days) {
                now = NotificationWindows.nextRun(now, TimeZone.UTC).second
                runs += now
            }
            for (day in 1..2) {
                for (hour in NotificationWindows.openingHours) {
                    val open = Instant.parse("2026-11-2${1 + day}T${hour.toString().padStart(2, '0')}:00:00Z")
                    assertTrue(runs.any { it >= open && it < open + 1.hours }, "a run just after $open when starting at minute $minute")
                }
            }
        }
    }

    @Test
    fun aRunJustAfterAWindowOpensIsInsideItsWindow() {
        for (hour in NotificationWindows.openingHours) {
            val run = NotificationWindows.nextRun(Instant.parse("2026-11-21T${hour.toString().padStart(2, '0')}:00:00Z") - 1.minutes, TimeZone.UTC)
            assertEquals(hour, run.first)
            val planner = at(run.second.toString().substring(11, 16))
            NotificationKind.entries.filter { it.fromHour == hour }.forEach { storage.saveNotificationOn(it, true) }
            assertTrue(NotificationKind.entries.filter { it.fromHour == hour }.all(planner::wanted), "the window of $hour is open at its run")
        }
    }
}
