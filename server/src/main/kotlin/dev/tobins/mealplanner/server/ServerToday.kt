package dev.tobins.mealplanner.server

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** The zone the meal planner's days are in, whatever zone the server's container runs in. */
val SERVER_ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")

/** Today's date as the household sees it: one answer wherever the server needs "today". */
fun serverToday(clock: Clock = Clock.systemUTC()): LocalDate = LocalDate.ofInstant(clock.instant(), SERVER_ZONE)
