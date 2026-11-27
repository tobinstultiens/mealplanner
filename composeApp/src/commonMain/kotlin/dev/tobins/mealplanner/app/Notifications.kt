package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.DinnerToday
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.NotificationFacts
import dev.tobins.mealplanner.api.PrepPastLastDay
import dev.tobins.mealplanner.app.accounts.LoginExchange
import dev.tobins.mealplanner.app.accounts.session
import dev.tobins.mealplanner.app.plan.notifications
import dev.tobins.mealplanner.app.recipes.MealPlace
import dev.tobins.mealplanner.app.recipes.MealToCook
import dev.tobins.mealplanner.app.ui.dayLabel
import dev.tobins.mealplanner.app.ui.daysBetween
import dev.tobins.mealplanner.app.ui.mondayOf
import dev.tobins.mealplanner.app.ui.servingsText
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/*
 * The Android app's notifications (issue 225). The server says what is due (`GET /api/notifications`);
 * what decides whether to tell is here, in common code, so that it is tested without Android: the
 * kind's switch, what was told already and the hour. Android only schedules the check, asks, and posts.
 */

/**
 * The kinds of notification, each with its own switch in Settings ([defaultOn] until the user touches it)
 * and its own Android notification channel ([channel]), so that the system's settings list the same kinds.
 * The update's channel keeps the id it had before the others came, and with it what the user set for it.
 * [fromHour] is when on the device's clock the kind's window opens; all close at [QUIET_FROM].
 */
enum class NotificationKind(val key: String, val channel: String, val defaultOn: Boolean, val fromHour: Int, val title: Msg, val hint: Msg) {
    UPDATE("update", "updates", true, 8, AppMsg.NOTIFY_UPDATE, AppMsg.NOTIFY_UPDATE_HINT),
    OFFERS_ENDING("offersEnding", "offersEnding", true, 8, AppMsg.NOTIFY_OFFERS_ENDING, AppMsg.NOTIFY_OFFERS_ENDING_HINT),
    PLAN_NEXT_WEEK("planNextWeek", "planNextWeek", true, 8, AppMsg.NOTIFY_PLAN_NEXT_WEEK, AppMsg.NOTIFY_PLAN_NEXT_WEEK_HINT),
    WEIGH_IN("weighIn", "weighIn", false, 8, AppMsg.NOTIFY_WEIGH_IN, AppMsg.NOTIFY_WEIGH_IN_HINT),
    DINNER_TODAY("dinnerToday", "dinnerToday", false, 15, AppMsg.NOTIFY_DINNER_TODAY, AppMsg.NOTIFY_DINNER_TODAY_HINT),
    MEALS_TO_MARK("mealsToMark", "mealsToMark", false, 20, AppMsg.NOTIFY_MEALS_TO_MARK, AppMsg.NOTIFY_MEALS_TO_MARK_HINT),

    // On: an account that never made a prep has no batch to tell of, so in effect it is on for those that have (issue 264).
    THROW_AWAY("throwAway", "throwAway", true, 8, AppMsg.NOTIFY_THROW_AWAY, AppMsg.NOTIFY_THROW_AWAY_HINT),

    // On: only the owner's account is told of requests for an account (issue 314), and its switch shows to no other.
    ACCOUNT_REQUESTS("accountRequests", "accountRequests", true, 8, AppMsg.NOTIFY_ACCOUNT_REQUESTS, AppMsg.NOTIFY_ACCOUNT_REQUESTS_HINT),
    ;

    companion object {
        /** The hour from which nothing is posted until the next morning's windows open. */
        const val QUIET_FROM = 22

        /** The kinds the server's facts are about: all but the update, which GitHub tells. */
        val fromServer = entries - UPDATE

        /** The kinds Settings has a switch for: "Account requests" only for the account that [approvesRequests]. */
        fun shown(approvesRequests: Boolean): List<NotificationKind> = if (approvesRequests) entries else entries - ACCOUNT_REQUESTS
    }
}

/**
 * When the background check must run so that every kind's window gets a run, whatever the phase of the periodic
 * check (which Android runs about every four hours, at times of its own choosing and so possibly never inside
 * the two hours of "Mark what you ate"). One extra run is due [MARGIN] after each hour a window opens; each
 * run asks for the next one, so the chain goes on while the app is used now and then and the periodic check
 * runs.
 */
@OptIn(ExperimentalTime::class)
object NotificationWindows {
    /** A run waits this long after the hour so that it is surely inside the window, and not at its very edge. */
    val MARGIN: Duration = 5.minutes

    /** The hours at which a window opens, in the order of the day. */
    val openingHours: List<Int> = NotificationKind.entries.map { it.fromHour }.distinct().sorted()

    /** The hour of the window the next run is for, and how long from now on the device's clock until it is due. */
    fun untilNextRun(clock: Clock = Clock.System, zone: TimeZone = TimeZone.currentSystemDefault()): Pair<Int, Duration> {
        val now = clock.now()
        val (hour, due) = nextRun(now, zone)
        return hour to due - now
    }

    /** The run after [now]: the hour of the window it is for, and the instant it is due, on the device's [zone]. */
    fun nextRun(now: Instant, zone: TimeZone): Pair<Int, Instant> {
        val today = now.toLocalDateTime(zone).date
        return (0..1).flatMap { days ->
            val day = today.plus(days, DateTimeUnit.DAY)
            openingHours.map { hour -> hour to LocalDateTime(day, LocalTime(hour, 0)).toInstant(zone) + MARGIN }
        }.first { it.second > now }
    }
}

/** Whether the switch of [kind] is on; per device, as the other settings are. */
fun SettingsStorage.notificationOn(kind: NotificationKind): Boolean = get("notify.${kind.key}")?.let { it == "true" } ?: kind.defaultOn

fun SettingsStorage.saveNotificationOn(kind: NotificationKind, on: Boolean) = set("notify.${kind.key}", on.toString())

private const val APPROVES_REQUESTS_KEY = "approvesRequests"

/**
 * Whether the account logged in on this device decides on requests for an account, as the server said last
 * ([dev.tobins.mealplanner.api.Me.approvesRequests]); false until it did. Per account, so [logOut] forgets it.
 */
fun SettingsStorage.approvesRequests(): Boolean = get(APPROVES_REQUESTS_KEY) == "true"

fun SettingsStorage.saveApprovesRequests(approves: Boolean) = set(APPROVES_REQUESTS_KEY, approves.toString())

/** Forgets the account's flag of [approvesRequests]; part of [logOut]. */
internal fun SettingsStorage.clearApprovesRequests() = remove(APPROVES_REQUESTS_KEY)

/**
 * Forgets the requests for an account told of that are not among the user names [waiting] (decided, or expired), so that
 * a user name that asks again after a rejection is told of again. Called with every list the server gave, by the
 * background check and by the owner's Requests card: a rejection made there is seen at once, not only by a check that
 * happens to run before the person asks again. The server's facts name the requests by user name only.
 */
fun SettingsStorage.forgetRequestsNotWaiting(waiting: List<String>) {
    val before = toldRequests(told(NotificationKind.ACCOUNT_REQUESTS))
    val still = before.filter { it in waiting }
    if (still.size != before.size) set("notified.${NotificationKind.ACCOUNT_REQUESTS.key}", still.joinToString(","))
}

/** What was last told of [kind]: the version, the day or the week, as [DueNotification.toldAs] says; null when nothing was. */
private fun SettingsStorage.told(kind: NotificationKind): String? = get("notified.${kind.key}")?.takeIf { it.isNotBlank() }

/**
 * A notification to post: its [kind], its [title] and [text] in the app's language, what tapping it opens
 * (null: the app as it is), and what is remembered of it once it is posted, so it is told once: the version,
 * the server's day, for "plan next week" the week, or for prepped food to throw away the server's day with every
 * batch told of on it ([toldBatches]).
 *
 * [tag] tells it apart from others of its kind in the tray (Android's notification tag, issue 263). Null for a kind whose
 * newer notification makes the older one stale (yesterday's dinner, an older version), which it then replaces. Food to
 * throw away is told per batch, so its tag names the batches it tells of: a batch told later the same day goes next to
 * the first, and the same batches posted again update theirs.
 */
data class DueNotification(val kind: NotificationKind, val title: String, val text: String, val opening: Opening?, val toldAs: String, val tag: String? = null) {
    /**
     * The request code of what a tap opens: one per kind and [tag], since Android hands every notification whose intent has
     * the same code the extras of the last one. Without a tag, the kind's own, as it always was.
     */
    val requestCode: Int get() = tag?.let { NotificationKind.entries.size + (31 * kind.ordinal + it.hashCode()).mod(1 shl 24) } ?: kind.ordinal
}

/**
 * Decides which notifications a run of the background check posts: those whose switch is on, whose window
 * the device's [clock] is in, and that were not told already (once per version, day, week, or for the
 * weigh-in once per [WEIGH_IN_EVERY_DAYS] days). Nothing between [NotificationKind.QUIET_FROM] and 08:00.
 */
@OptIn(ExperimentalTime::class)
class NotificationPlanner(
    private val storage: SettingsStorage,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    /**
     * Whether a run now could post [kind]: its switch is on and the hour is in its window. Requests for an account only
     * count for the account that decides on them ([approvesRequests]): another account cannot see that switch, so it
     * could never switch it off, and the app would ask the server every run for nothing.
     */
    fun wanted(kind: NotificationKind): Boolean {
        val hour = clock.now().toLocalDateTime(zone).hour
        if (kind == NotificationKind.ACCOUNT_REQUESTS && !storage.approvesRequests()) return false
        return storage.notificationOn(kind) && hour >= kind.fromHour && hour < NotificationKind.QUIET_FROM
    }

    /** Whether a run now could post anything from the server's facts, so that it is worth asking. */
    fun wantsFacts(): Boolean = NotificationKind.fromServer.any(::wanted)

    /** The notifications to post now, for the newer release [update] and the server's [facts] (each null when unknown), in [strings]. */
    fun due(facts: NotificationFacts?, update: AppUpdate?, strings: Strings): List<DueNotification> {
        facts?.let { forgetDecidedRequests(it.accountRequests) }
        val candidates = listOfNotNull(update?.let { updateNotification(it, strings) }) +
            facts?.let {
                val told = toldBatches(storage.told(NotificationKind.THROW_AWAY), it.date)
                dueNotifications(it, told, toldRequests(storage.told(NotificationKind.ACCOUNT_REQUESTS)), strings)
            }.orEmpty()
        return candidates.filter { wanted(it.kind) && !alreadyTold(it) }
    }

    /**
     * Forgets the requests for an account told of that no longer wait ([waiting]: decided, or expired), so that a user name
     * that asks again after a rejection is told of again. Not only when something is posted: such a request may come back
     * before any other new one does.
     */
    private fun forgetDecidedRequests(waiting: List<String>) = storage.forgetRequestsNotWaiting(waiting)

    /** Remembers that [posted] was posted, so that [due] does not offer it again. */
    fun told(posted: DueNotification) = storage.set("notified.${posted.kind.key}", posted.toldAs)

    /**
     * Takes over what was told of [kind] before this planner kept it ([toldAs], as [DueNotification.toldAs]), unless
     * something was told since: the daily update check this replaces kept the version it told elsewhere, and an
     * update told then must not be told again after the upgrade.
     */
    fun toldBefore(kind: NotificationKind, toldAs: String) {
        if (storage.told(kind) == null) storage.set("notified.${kind.key}", toldAs)
    }

    private fun alreadyTold(due: DueNotification): Boolean {
        val last = storage.told(due.kind) ?: return false
        if (due.kind != NotificationKind.WEIGH_IN) return last == due.toldAs
        return runCatching { daysBetween(last, due.toldAs) }.getOrNull()?.let { it < WEIGH_IN_EVERY_DAYS } ?: false
    }

    companion object {
        /** The weigh-in is told again this many days after it was told, while it is still due. */
        const val WEIGH_IN_EVERY_DAYS = 7
    }
}

private fun updateNotification(update: AppUpdate, t: Strings) =
    DueNotification(NotificationKind.UPDATE, t(AppMsg.UPDATE_NOTIFICATION_TITLE, update.version), t(AppMsg.UPDATE_NOTIFICATION_TEXT), null, update.version)

/**
 * The server's [facts] as notifications, one for each kind that is due, leaving out the batches past their last day
 * told of already ([told]) and the requests for an account told of already ([toldRequests]); whether to post them is
 * [NotificationPlanner.due]'s.
 */
private fun dueNotifications(facts: NotificationFacts, told: Set<Long>, toldRequests: Set<String>, t: Strings): List<DueNotification> = listOfNotNull(
    facts.offersEnding.takeIf { it.isNotEmpty() }?.let { offers ->
        val ending = when {
            offers.all { it.endsOn == facts.date } -> AppPlural.OFFERS_END_TODAY
            offers.none { it.endsOn == facts.date } -> AppPlural.OFFERS_END_TOMORROW
            else -> AppPlural.OFFERS_END_TODAY_OR_TOMORROW
        }
        DueNotification(NotificationKind.OFFERS_ENDING, t.plural(ending, offers.size), offers.joinToString(", ") { it.name }, Opening(Route.TabRoute(Tab.SHOPPING)), facts.date)
    },
    facts.planNextWeek?.let { week ->
        DueNotification(NotificationKind.PLAN_NEXT_WEEK, t(AppMsg.PLAN_NOTIFICATION_TITLE), t(AppMsg.PLAN_NOTIFICATION_TEXT), Opening(Route.TabRoute(Tab.PLAN), week = week, planning = true), week)
    },
    facts.weighIn?.let { due ->
        DueNotification(NotificationKind.WEIGH_IN, t(AppMsg.WEIGH_IN_NOTIFICATION_TITLE), t.plural(AppPlural.LAST_WEIGH_IN, due.daysAgo), Opening(Route.TabRoute(Tab.PROGRESS)), facts.date)
    },
    facts.dinner?.let { dinner -> dinnerNotification(dinner, facts.date, t) },
    facts.mealsToMark.takeIf { it > 0 }?.let { count ->
        DueNotification(NotificationKind.MEALS_TO_MARK, t.plural(AppPlural.MEALS_TO_MARK, count), t(AppMsg.MEALS_TO_MARK_NOTIFICATION_TEXT), Opening(Route.TabRoute(Tab.TODAY)), facts.date)
    },
    throwAwayNotification(facts, told, t),
    accountRequestsNotification(facts.accountRequests, toldRequests, t),
)

/**
 * The owner's requests for an account (issue 314) when one of them was not told of yet ([told]): the title counts all that
 * wait, the text names the new ones, and a tap opens Settings, where they are decided on. It remembers the user names
 * waiting now, so each request is told of once; a newer one replaces it in the tray.
 */
private fun accountRequestsNotification(waiting: List<String>, told: Set<String>, t: Strings): DueNotification? {
    val new = waiting.filter { it !in told }.takeIf { it.isNotEmpty() } ?: return null
    return DueNotification(
        NotificationKind.ACCOUNT_REQUESTS,
        t.plural(AppPlural.ACCOUNT_REQUESTS_TITLE, waiting.size),
        t(AppMsg.ACCOUNT_REQUESTS_NOTIFICATION_TEXT, new.joinToString(", ")),
        Opening(Route.Settings),
        waiting.joinToString(","),
    )
}

/** The user names of the requests for an account told of, from what was remembered of the kind ([DueNotification.toldAs]: `ann,bob`). */
private fun toldRequests(told: String?): Set<String> = told?.split(',')?.filterTo(HashSet()) { it.isNotBlank() }.orEmpty()

/**
 * Tonight's [dinner] on the server's day [date]: what to cook, and a tap opens its recipe as the Plan does. A batch made
 * tonight is cooked for the prep's later meals too, not for tomorrow's lunch, which the recipe says as it does from the
 * Plan; a side made tonight next to a main with nothing to cook is named as the side (issue 263).
 */
private fun dinnerNotification(dinner: DinnerToday, date: String, t: Strings): DueNotification {
    val meal = MealToCook(
        dinner.recipeId, dinner.portions, dinner.cookPortions, skipped = false, MealPlace(dinner.weekStart, dinner.day, MealSlot.DINNER), planned = true,
        prepMadeOn = date.takeIf { dinner.prep },
    )
    val title = t(if (dinner.side) AppMsg.DINNER_NOTIFICATION_SIDE_TITLE else AppMsg.DINNER_NOTIFICATION_TITLE, dinner.title)
    val text = t(if (dinner.prep) AppMsg.DINNER_NOTIFICATION_PREP_TEXT else AppMsg.DINNER_NOTIFICATION_TEXT, servingsText(dinner.cookPortions, t))
    return DueNotification(NotificationKind.DINNER_TODAY, title, text, Opening(Route.Recipe(dinner.recipeId), meal), date)
}

/**
 * The batches of [facts] whose last day was the day before and that were not told of yet ([told]), as one notification
 * (issue 264): one batch with portions left says to throw them away in its title; otherwise the title is the kind's and
 * the text says it of each. A batch past its day for longer is left out, whatever the server says: the box was to go
 * the morning after. Tapping it opens the Plan where there is something to do (issue 263): on the week of the first meal
 * not marked, where it can be marked, else on that of the first skipped share left in the box, the one to throw away,
 * which may be the week after the batch was made; on the week the first batch was made only from a server that does not
 * say where its shares are.
 */
private fun throwAwayNotification(facts: NotificationFacts, told: Set<Long>, t: Strings): DueNotification? {
    val batches = facts.prepsPastLastDay.filter { it.prepId !in told && daysBetween(it.lastDay, facts.date) == 1 }
    val first = batches.firstOrNull() ?: return null
    val kept = t.plural(AppPlural.KEPT_UNTIL, batches.size, dayLabel(first.lastDay, t.locale))
    val throwAway = first.throwAwayText(t)
    val (title, text) = if (batches.size == 1 && throwAway != null) {
        throwAway to listOfNotNull(kept, first.unmarkedText(t)).joinToString(" ")
    } else {
        val sentences = batches.flatMap { listOfNotNull(it.throwAwayText(t)?.let { line -> "$line." }, it.unmarkedText(t)) }
        t(AppMsg.NOTIFY_THROW_AWAY) to (sentences + kept).joinToString(" ")
    }
    val day = batches.firstNotNullOfOrNull { it.firstUnmarked } ?: batches.firstNotNullOfOrNull { it.firstLeft } ?: first.madeOn
    val toldAs = facts.date + ":" + (told + batches.map { it.prepId }).sorted().joinToString(",")
    val tag = NotificationKind.THROW_AWAY.key + ":" + facts.date + ":" + batches.map { it.prepId }.sorted().joinToString(",")
    return DueNotification(NotificationKind.THROW_AWAY, title, text, Opening(Route.TabRoute(Tab.PLAN), week = mondayOf(day)), toldAs, tag)
}

/**
 * `Throw away 1 portion of Chili con carne`, or of sides, or `Throw away 1 portion and 2 sides of …` of a batch eaten both
 * ways (issue 269); null when nothing of the batch was left in the box.
 */
private fun PrepPastLastDay.throwAwayText(t: Strings): String? =
    sharesText(left, leftSides, AppPlural.THROW_AWAY_PORTIONS, AppPlural.THROW_AWAY_SIDES, AppMsg.THROW_AWAY_MIXED, t)

/** `1 portion of Chili con carne was not marked: eaten, or to throw away?`, or of sides, or both; null when every meal of it was marked. */
private fun PrepPastLastDay.unmarkedText(t: Strings): String? =
    sharesText(unmarked, unmarkedSides, AppPlural.UNMARKED_PORTIONS, AppPlural.UNMARKED_SIDES, AppMsg.UNMARKED_MIXED, t)

/**
 * [shares] of [this] batch, [sides] of them sides, in the words of [portions] when none is a side, of [ofSides] when all
 * are, and of [both] with each counted when some are; null for none. Each share is told as what it is, not as what most
 * of the batch is.
 */
private fun PrepPastLastDay.sharesText(shares: Int, sides: Int, portions: Plural, ofSides: Plural, both: Msg, t: Strings): String? = when {
    shares <= 0 -> null
    sides == 0 -> t.plural(portions, shares, title)
    sides == shares -> t.plural(ofSides, shares, title)
    else -> t(both, t.plural(AppPlural.PORTIONS, shares - sides), t.plural(AppPlural.SIDES, sides), title)
}

/**
 * The batches told of on the server's day [date], from what was remembered of the kind ([told], as
 * [DueNotification.toldAs] has it: `2026-11-21:12,14`). A batch is past its last day on one day only, so those told
 * of on another day are of no concern.
 */
private fun toldBatches(told: String?, date: String): Set<Long> {
    if (told == null || told.substringBefore(':') != date) return emptySet()
    return told.substringAfter(':').split(',').mapNotNullTo(HashSet()) { it.toLongOrNull() }
}

/** [askWhatIsDue], but only when [planner] says a run now could post something from the answer: otherwise nothing is sent. */
suspend fun askWhatIsDueIfWanted(
    planner: NotificationPlanner,
    storage: SettingsStorage,
    logins: LoginExchange,
    settings: ServerSettings,
    engine: HttpClientEngine? = null,
): NotificationFacts? = if (planner.wantsFacts()) askWhatIsDue(storage, logins, settings, engine) else null

/**
 * What the server says is due today, asked in the background with the stored login; null when logged out,
 * when there is no answer, and when the session changed while asking (logged out, or logged in again,
 * perhaps as someone else). It never shows anything and never ends a session: a failure tells nothing and
 * the next run asks again. Renewals go through [logins], one at a time with the screens' own, and quietly
 * ([LoginExchange.renew]), so call this on the thread [logins] runs on (the main one).
 */
suspend fun askWhatIsDue(storage: SettingsStorage, logins: LoginExchange, settings: ServerSettings, engine: HttpClientEngine? = null): NotificationFacts? {
    var used = storage.session() ?: return null
    val api = ApiClient(settings, used, engine) { refused -> logins.renew(settings.url, refused, quietly = true)?.also { used = it } }
    return try {
        api.notifications().takeIf { storage.session()?.accessToken == used.accessToken }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    } finally {
        api.close()
    }
}

/** What the Notifications card needs of the platform (Android only): whether the app may post, and asking for it. */
interface NotificationAccess {
    /**
     * Whether the system lets the app post notifications. Read again whenever the app comes back to the front
     * and when an [ask] is answered, so that a permission given or taken in the system's settings shows at once.
     */
    val allowed: StateFlow<Boolean>

    /** Asks for the permission where the system needs asking; [allowed] follows the answer. */
    fun ask()

    /** Opens the system's notification settings of the app, where a refusal is undone. */
    fun openSettings()
}

/** What the Notifications card shows and changes: the switches in [storage] and the platform's [access]. */
class NotificationSettings(val storage: SettingsStorage, val access: NotificationAccess)
