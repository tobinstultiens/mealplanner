package dev.tobins.mealplanner.server

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The zone the meal planner's days are in, whatever zone the server's container runs in. */
val SERVER_ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")

/** Today's date as the household sees it: one answer wherever the server needs "today". */
fun serverToday(clock: Clock = Clock.systemUTC()): LocalDate = serverDay(clock.instant())

/** The household's day an instant falls on. */
fun serverDay(instant: Instant): LocalDate = LocalDate.ofInstant(instant, SERVER_ZONE)
