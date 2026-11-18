package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.NotificationFacts
import io.ktor.client.engine.HttpClientEngine
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

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
    UPDATE("update", "updates", true, 8, Msg.NOTIFY_UPDATE, Msg.NOTIFY_UPDATE_HINT),
    OFFERS_ENDING("offersEnding", "offersEnding", true, 8, Msg.NOTIFY_OFFERS_ENDING, Msg.NOTIFY_OFFERS_ENDING_HINT),
    PLAN_NEXT_WEEK("planNextWeek", "planNextWeek", true, 8, Msg.NOTIFY_PLAN_NEXT_WEEK, Msg.NOTIFY_PLAN_NEXT_WEEK_HINT),
    WEIGH_IN("weighIn", "weighIn", false, 8, Msg.NOTIFY_WEIGH_IN, Msg.NOTIFY_WEIGH_IN_HINT),
    DINNER_TODAY("dinnerToday", "dinnerToday", false, 15, Msg.NOTIFY_DINNER_TODAY, Msg.NOTIFY_DINNER_TODAY_HINT),
    MEALS_TO_MARK("mealsToMark", "mealsToMark", false, 20, Msg.NOTIFY_MEALS_TO_MARK, Msg.NOTIFY_MEALS_TO_MARK_HINT),
    ;

    companion object {
        /** The hour from which nothing is posted until the next morning's windows open. */
        const val QUIET_FROM = 22

        /** The kinds the server's facts are about: all but the update, which GitHub tells. */
        val fromServer = entries - UPDATE
    }
}

/** Whether the switch of [kind] is on; per device, as the other settings are. */
fun SettingsStorage.notificationOn(kind: NotificationKind): Boolean = get("notify.${kind.key}")?.let { it == "true" } ?: kind.defaultOn

fun SettingsStorage.saveNotificationOn(kind: NotificationKind, on: Boolean) = set("notify.${kind.key}", on.toString())

/** What was last told of [kind]: the version, the day or the week, as [DueNotification.toldAs] says; null when nothing was. */
private fun SettingsStorage.told(kind: NotificationKind): String? = get("notified.${kind.key}")?.takeIf { it.isNotBlank() }

/**
 * A notification to post: its [kind], its [title] and [text] in the app's language, what tapping it opens
 * (null: the app as it is), and what is remembered of it once it is posted, so it is told once: the version,
 * the server's day, or for "plan next week" the week.
 */
data class DueNotification(val kind: NotificationKind, val title: String, val text: String, val opening: Opening?, val toldAs: String)

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
    /** Whether a run now could post [kind]: its switch is on and the hour is in its window. */
    fun wanted(kind: NotificationKind): Boolean {
        val hour = clock.now().toLocalDateTime(zone).hour
        return storage.notificationOn(kind) && hour >= kind.fromHour && hour < NotificationKind.QUIET_FROM
    }

    /** Whether a run now could post anything from the server's facts, so that it is worth asking. */
    fun wantsFacts(): Boolean = NotificationKind.fromServer.any(::wanted)

    /** The notifications to post now, for the newer release [update] and the server's [facts] (each null when unknown), in [strings]. */
    fun due(facts: NotificationFacts?, update: AppUpdate?, strings: Strings): List<DueNotification> {
        val candidates = listOfNotNull(update?.let { updateNotification(it, strings) }) + facts?.let { dueNotifications(it, strings) }.orEmpty()
        return candidates.filter { wanted(it.kind) && !alreadyTold(it) }
    }

    /** Remembers that [posted] was posted, so that [due] does not offer it again. */
    fun told(posted: DueNotification) = storage.set("notified.${posted.kind.key}", posted.toldAs)

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
    DueNotification(NotificationKind.UPDATE, t(Msg.UPDATE_NOTIFICATION_TITLE, update.version), t(Msg.UPDATE_NOTIFICATION_TEXT), null, update.version)

/** The server's [facts] as notifications, one for each kind that is due; whether to post them is [NotificationPlanner.due]'s. */
private fun dueNotifications(facts: NotificationFacts, t: Strings): List<DueNotification> = listOfNotNull(
    facts.offersEnding.takeIf { it.isNotEmpty() }?.let { offers ->
        val ending = when {
            offers.all { it.endsOn == facts.date } -> Plural.OFFERS_END_TODAY
            offers.none { it.endsOn == facts.date } -> Plural.OFFERS_END_TOMORROW
            else -> Plural.OFFERS_END_TODAY_OR_TOMORROW
        }
        DueNotification(NotificationKind.OFFERS_ENDING, t.plural(ending, offers.size), offers.joinToString(", ") { it.name }, Opening(Route.TabRoute(Tab.SHOPPING)), facts.date)
    },
    facts.planNextWeek?.let { week ->
        DueNotification(NotificationKind.PLAN_NEXT_WEEK, t(Msg.PLAN_NOTIFICATION_TITLE), t(Msg.PLAN_NOTIFICATION_TEXT), Opening(Route.TabRoute(Tab.PLAN)), week)
    },
    facts.weighIn?.let { due ->
        DueNotification(NotificationKind.WEIGH_IN, t(Msg.WEIGH_IN_NOTIFICATION_TITLE), t.plural(Plural.LAST_WEIGH_IN, due.daysAgo), Opening(Route.TabRoute(Tab.PROGRESS)), facts.date)
    },
    facts.dinner?.let { dinner ->
        val meal = MealToCook(dinner.recipeId, dinner.portions, dinner.cookPortions, skipped = false, MealPlace(dinner.weekStart, dinner.day, MealSlot.DINNER), planned = true)
        DueNotification(
            NotificationKind.DINNER_TODAY, t(Msg.DINNER_NOTIFICATION_TITLE, dinner.title), t(Msg.DINNER_NOTIFICATION_TEXT, servingsText(dinner.cookPortions, t)),
            Opening(Route.Recipe(dinner.recipeId), meal), facts.date,
        )
    },
    facts.mealsToMark.takeIf { it > 0 }?.let { count ->
        DueNotification(NotificationKind.MEALS_TO_MARK, t.plural(Plural.MEALS_TO_MARK, count), t(Msg.MEALS_TO_MARK_NOTIFICATION_TEXT), Opening(Route.TabRoute(Tab.TODAY)), facts.date)
    },
)

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
    /** Whether the system lets the app post notifications now. */
    fun allowed(): Boolean

    /** Asks for the permission where the system needs asking; [answer] is told whether the app may post now. */
    fun ask(answer: (Boolean) -> Unit)

    /** Opens the system's notification settings of the app, where a refusal is undone. */
    fun openSettings()
}

/** What the Notifications card shows and changes: the switches in [storage] and the platform's [access]. */
class NotificationSettings(val storage: SettingsStorage, val access: NotificationAccess)
