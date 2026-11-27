package dev.tobins.mealplanner.app.shopping

import dev.tobins.mealplanner.api.ShoppingAdviceGroup
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.OfferExclusion
import dev.tobins.mealplanner.api.OfferExclusions
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.app.AppFront
import dev.tobins.mealplanner.app.MemorySettingsStorage
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.logOut
import dev.tobins.mealplanner.app.prices.ExclusionChange
import dev.tobins.mealplanner.app.prices.PricesMsg
import dev.tobins.mealplanner.app.prices.key
import dev.tobins.mealplanner.app.ui.Load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The shopping list's state as the app keeps it, across leaving the screen, closing the app and changing week. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShoppingSessionTest {
    private val monday = "2026-11-16"

    /**
     * A server that answers after [latency] ms and remembers what it was sent, in order. [answer]
     * replaces the answer to a tick and [listAnswer] the one to fetching a list; a tick of a key
     * in [hanging] is never answered, and while [unreachable] every request fails at once, as
     * without a network. [asked] has every request as it went out.
     */
    private class FakeServer(var latency: Long = 2000) : ShoppingServer {
        val lists = mutableMapOf<String, ShoppingList>()
        val ticks = mutableListOf<Triple<String, String, Boolean>>()

        /** The grams sent with each tick the server took, with its key. */
        val sentGrams = mutableListOf<Pair<String, Double?>>()
        val asked = mutableListOf<String>()
        var answer: ((String, Boolean) -> Load<ShoppingList>)? = null
        var listAnswer: Load<ShoppingList>? = null
        val hanging = mutableSetOf<String>()
        val onHand = mutableListOf<Long>()
        var unreachable = false

        fun listFor(week: String): ShoppingList = lists.getOrPut(week) {
            ShoppingList(week, listOf("a", "b", "c").map { ShoppingItem(it, it, 100.0, null, "Vegetables", listOf("Soup"), onHand = false, checked = false) }, staples = emptyList())
        }

        /** Another device on the same login ticks [key]. */
        fun tickElsewhere(week: String, key: String, checked: Boolean) {
            lists[week] = listFor(week).withTicks(mapOf(key to checked))
        }

        override suspend fun list(weekStart: String): Load<ShoppingList> {
            asked += "list $weekStart"
            if (unreachable) return Load.Failed("Unable to resolve host")
            delay(latency)
            return listAnswer ?: Load.Ready(listFor(weekStart))
        }

        /** What the phone showed of each row, as sent with its tick: null when nothing was sent. */
        val sentShown = mutableListOf<Pair<String, ShoppingCheck.Shown?>>()

        /** True for a server from before ticks said what they showed: it refuses a request with the field (400) and stores nothing. */
        var refusesShown = false

        override suspend fun check(weekStart: String, key: String, checked: Boolean, grams: Double?, shown: ShoppingCheck.Shown?): Load<ShoppingList> {
            asked += "check $weekStart $key"
            if (unreachable) return Load.Failed("Unable to resolve host")
            if (key in hanging) awaitCancellation()
            delay(latency)
            if (refusesShown && shown != null) return Load.Failed("Encountered an unknown key 'shown'", 400)
            sentShown += key to shown
            ticks += Triple(weekStart, key, checked)
            sentGrams += key to grams
            answer?.let { return it(key, checked) }
            lists[weekStart] = listFor(weekStart).withTicks(mapOf(key to checked))
            return Load.Ready(listFor(weekStart))
        }

        /** False for a server from before ticking again and using up in one step: it has no such routes. */
        var oneStep = true

        /** Replaces the answer to ticking again or using up, as a refusal. */
        var oneStepAnswer: Load<ShoppingList>? = null

        override suspend fun tickAgain(weekStart: String, key: String): Load<ShoppingList>? {
            asked += "again $weekStart $key"
            if (!oneStep) return null
            delay(latency)
            return oneStepAnswer ?: Load.Ready(listFor(weekStart))
        }

        override suspend fun useUp(weekStart: String, key: String): Load<ShoppingList>? {
            asked += "use up $weekStart $key"
            if (!oneStep) return null
            delay(latency)
            oneStepAnswer?.let { return it }
            lists[weekStart] = listFor(weekStart).let { list -> list.copy(noLongerNeeded = list.noLongerNeeded.filter { it.key != key }) }
            return Load.Ready(listFor(weekStart))
        }

        override suspend fun putOnHand(ingredientId: Long): Load<Unit> {
            asked += "on hand $ingredientId"
            delay(latency)
            onHand += ingredientId
            return Load.Ready(Unit)
        }

        override suspend fun dismiss(weekStart: String, group: ShoppingAdviceGroup, dismissed: Boolean): Load<ShoppingList> {
            delay(latency)
            lists[weekStart] = listFor(weekStart).copy(buyAheadDismissed = dismissed)
            return Load.Ready(listFor(weekStart))
        }

        /** The names of own items added, as they came in; [addAnswer] replaces the answer, as a refusal. */
        val added = mutableListOf<String>()
        var addAnswer: Load<ShoppingList>? = null

        override suspend fun addExtra(weekStart: String, name: String): Load<ShoppingList> {
            asked += "add $weekStart $name"
            if (unreachable) return Load.Failed("Unable to resolve host")
            delay(latency)
            addAnswer?.let { return it }
            added += name
            val own = ShoppingItem("x:${added.size}", name, null, null, "Other", emptyList(), onHand = false, checked = false)
            lists[weekStart] = listFor(weekStart).let { it.copy(items = it.items + own) }
            return Load.Ready(listFor(weekStart))
        }

        override suspend fun removeExtra(weekStart: String, id: Long): Load<ShoppingList> {
            asked += "remove $weekStart $id"
            delay(latency)
            lists[weekStart] = listFor(weekStart).let { list -> list.copy(items = list.items.filter { it.key != "x:$id" }) }
            return Load.Ready(listFor(weekStart))
        }

        /** The account's "Not this ingredient" list, the newest first; [exclusionAnswer] replaces the answer to a change, as a refusal. */
        val excluded = mutableListOf<OfferExclusion>()
        var exclusionAnswer: Load<OfferExclusions>? = null

        override suspend fun setExcluded(change: ExclusionChange): Load<OfferExclusions> {
            asked += "${if (change.excluded) "exclude" else "take back"} ${change.title}"
            if (unreachable) return Load.Failed("Unable to resolve host")
            delay(latency)
            exclusionAnswer?.let { return it }
            excluded.removeAll { it.key == change.key }
            if (change.excluded) excluded.add(0, change.asExclusion())
            return Load.Ready(OfferExclusions(excluded.toList()))
        }

        override suspend fun exclusions(): Load<OfferExclusions> {
            asked += "exclusions"
            if (unreachable) return Load.Failed("Unable to resolve host")
            delay(latency)
            return Load.Ready(OfferExclusions(excluded.toList()))
        }
    }

    private val storage = MemorySettingsStorage()
    private val server = FakeServer()

    private fun TestScope.session(shoppingMode: Boolean = false, today: () -> String = { "2026-11-18" }) =
        ShoppingSession(backgroundScope, server, ShoppingCache(storage), today, shoppingMode = { shoppingMode })

    /** Lets [ms] pass and runs what is due then. */
    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    /** Lets a minute pass, time for every request; advanceUntilIdle does not wait for the background scope the sessions run in. */
    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    private fun ShoppingSession.shown(): ShoppingList = (state as Load.Ready).value

    private fun ShoppingSession.checked() = shown().items.map { it.checked }

    private fun pending(week: String = monday) = ShoppingCache(storage).pending(week)

    /** A row bought for 800 g whose meals need 1.4 kg now, as the server has it. */
    private val needsMore = ShoppingItem("a", "a", 1400.0, null, "Meat", emptyList(), onHand = false, checked = true, boughtGrams = 800.0)

    /** The list of [monday] with only [items]. */
    private fun listWith(vararg items: ShoppingItem) = ShoppingList(monday, items.toList(), staples = emptyList())

    @Test
    fun tickingARowThatNeedsMoreAgainIsOneRequest() = runTest {
        server.lists[monday] = listWith(needsMore)
        val session = session()
        session.resume()
        settle()
        assertEquals(600.0, session.shown().items.single().moreNeeded()?.grams)

        // The server remembers the amount of this moment.
        server.lists[monday] = listWith(needsMore.copy(boughtGrams = 1400.0))
        session.tickAgain("a")
        settle()

        assertEquals("again $monday a", server.asked.single { it.startsWith("again") || it.startsWith("check") })
        assertNull(session.shown().items.single().moreNeeded())
        assertTrue(session.shown().items.single().done)
    }

    @Test
    fun aServerFromBeforeTickingAgainIsSentAnUnTickAndATick() = runTest {
        // It remembers what a tick said: un-ticking forgets it, ticking stores the amount of that moment.
        server.oneStep = false
        server.lists[monday] = listWith(needsMore)
        server.answer = { _, checked -> Load.Ready(listWith(if (checked) needsMore.copy(boughtGrams = 1400.0) else needsMore.copy(checked = false, boughtGrams = null))) }
        val session = session()
        session.resume()
        settle()

        session.tickAgain("a")
        settle()

        assertEquals(listOf(false, true), server.ticks.map { it.third })
        assertNull(session.shown().items.single().moreNeeded())
        assertTrue(session.shown().items.single().done)
        assertEquals(emptyList(), session.notices.toList())
    }

    @Test
    fun whenTheTickAfterTheUnTickFailsTheRowShowsWhatTheServerHas() = runTest {
        server.oneStep = false
        server.lists[monday] = listWith(needsMore)
        val session = session()
        session.resume()
        settle()

        // The un-tick goes through, the tick does not: the server has the row un-ticked.
        server.answer = { _, checked ->
            if (checked) {
                Load.Failed("Internal server error", 500)
            } else {
                server.lists[monday] = listWith(needsMore.copy(checked = false, boughtGrams = null))
                Load.Ready(server.listFor(monday))
            }
        }
        session.tickAgain("a")
        settle()

        assertEquals(listOf(ShoppingMsg.TICK_FAILED to "Internal server error"), session.notices.map { it.msg to it.detail })
        assertEquals("list $monday", server.asked.last())
        val row = session.shown().items.single()
        assertEquals(false to null, row.checked to row.boughtGrams)
    }

    @Test
    fun whenTickingAgainIsRefusedTheListIsFetchedAgain() = runTest {
        server.lists[monday] = listWith(needsMore)
        val session = session()
        session.resume()
        settle()

        // Another phone un-ticked it in the meantime.
        server.lists[monday] = listWith(needsMore.copy(checked = false, boughtGrams = null))
        server.oneStepAnswer = Load.Failed("No tick of a in the week of $monday", 404)
        session.tickAgain("a")
        settle()

        assertEquals(listOf(ShoppingMsg.TICK_FAILED to "No tick of a in the week of $monday"), session.notices.map { it.msg to it.detail })
        assertEquals(emptyList(), server.ticks, "a refusal of the route is no reason to un-tick and tick")
        assertFalse(session.shown().items.single().checked)
    }

    /** Bought broccoli that no meal needs any more. */
    private val broccoli = ShoppingItem("i7", "Broccoli", null, null, "Vegetables", emptyList(), onHand = false, checked = true, boughtGrams = 500.0)

    @Test
    fun usingUpABoughtRowIsOneRequestAndTheRowLeavesTheGroup() = runTest {
        server.lists[monday] = server.listFor(monday).copy(noLongerNeeded = listOf(broccoli))
        val session = session()
        session.resume()
        settle()
        session.useUp("i7")
        session.useUp("t:cling film")
        settle()
        assertEquals(listOf("use up $monday i7"), server.asked.filter { it.startsWith("use up") || it.startsWith("on hand") }, "only a catalogue ingredient can be put on hand")
        assertEquals(emptyList(), session.shown().noLongerNeeded)
        assertEquals(emptyList(), server.onHand)
    }

    @Test
    fun aServerFromBeforeUsingUpIsAskedToPutTheIngredientOnHandAndTheListIsFetched() = runTest {
        server.oneStep = false
        val session = session()
        session.resume()
        settle()
        session.useUp("i7")
        settle()
        assertEquals(listOf(7L), server.onHand)
        assertEquals(listOf("use up $monday i7", "on hand 7", "list $monday"), server.asked.takeLast(3))
    }

    @Test
    fun whenUsingUpFailsItSaysSoAndTheListIsFetchedAgain() = runTest {
        server.lists[monday] = server.listFor(monday).copy(noLongerNeeded = listOf(broccoli))
        val session = session()
        session.resume()
        settle()
        server.oneStepAnswer = Load.Failed("The meals of the week of $monday need i7 again", 409)
        // The plan changed back on another phone: broccoli is a row of the list again.
        server.lists[monday] = server.listFor(monday).let { it.copy(items = it.items + broccoli, noLongerNeeded = emptyList()) }
        session.useUp("i7")
        settle()
        assertEquals(listOf(ShoppingMsg.USE_UP_FAILED to "The meals of the week of $monday need i7 again"), session.notices.map { it.msg to it.detail })
        assertEquals("list $monday", server.asked.last())
        assertTrue(session.shown().items.any { it.key == "i7" })
        assertEquals(emptyList(), server.onHand)
    }

    @Test
    fun removingABoughtRowTakesItOffTheListAtOnce() = runTest {
        val gone = ShoppingItem("i7", "Broccoli", null, null, "Other", emptyList(), onHand = false, checked = true, boughtGrams = 500.0)
        server.lists[monday] = server.listFor(monday).copy(noLongerNeeded = listOf(gone))
        val session = session()
        session.resume()
        settle()
        assertEquals(listOf("i7"), session.shown().noLongerNeeded.map { it.key })

        session.tick("i7", false)
        runCurrent()
        assertEquals(emptyList(), session.shown().noLongerNeeded)
    }

    @Test
    fun ticksQueuedWhenTheScreenIsLeftStillReachTheServerInOrder() = runTest {
        val session = session()
        // What the screen runs in its own scope, which leaving it cancels.
        val screen = CoroutineScope(coroutineContext + Job())
        screen.launch { session.resume() }
        settle()

        screen.launch {
            session.tick("a", true)
            session.tick("b", true)
            session.tick("c", true)
        }
        runCurrent()
        assertEquals(listOf(true, true, true), session.checked(), "shown at once")
        assertEquals(emptyList(), server.ticks, "the first request is still unanswered")
        screen.cancel()

        settle()
        assertEquals(listOf("a", "b", "c").map { Triple(monday, it, true) }, server.ticks)
        assertEquals(listOf(true, true, true), session.checked())
        assertEquals(emptyMap(), pending())
    }

    @Test
    fun aTickOfAnAppThatWasClosedIsSentByTheNextSessionOnOpen() = runTest {
        val first = session()
        first.resume()
        settle()
        first.tick("b", true)
        advanceTimeBy(1000)
        first.close()
        settle()
        assertEquals(emptyList(), server.ticks, "the app was killed before the request was answered")

        val next = session()
        next.resume()
        assertEquals(listOf(false, true, false), next.checked(), "the stored copy with the tick, before the server answered")
        settle()
        assertEquals(listOf(Triple(monday, "b", true)), server.ticks)
        assertEquals(listOf(false, true, false), next.checked())
        assertEquals(emptyMap(), pending())
    }

    @Test
    fun aStoredTickTheServerRefusesIsDroppedAndTheListStillLoads() = runTest {
        ShoppingCache(storage).apply {
            save(server.listFor(monday), "earlier")
            choose(monday, "a", true)
        }
        server.answer = { _, _ -> Load.Failed("No such item", 404) }
        val session = session()
        session.resume()
        settle()
        assertEquals(listOf(false, false, false), session.checked())
        assertNull(session.offlineSince)
        assertEquals(emptyMap(), pending())
        assertEquals(listOf(ShoppingMsg.TICK_FAILED to "No such item"), session.notices.map { it.msg to it.detail }, "said the next time the list shows")
        session.shown(session.notices.single())
        assertEquals(emptyList(), session.notices.toList())
    }

    @Test
    fun aRequestThatNeverAnswersIsGivenUpAfterTheTimeLimitAndHoldsUpNothing() = runTest {
        val session = session()
        session.resume()
        settle()
        server.hanging += "a"
        session.tick("a", true)
        session.tick("b", true)
        advanceTimeBy(ShoppingSession.TIME_LIMIT.inWholeMilliseconds - 1)
        assertNull(session.offlineSince, "still waiting")

        advanceTimeBy(2)
        runCurrent()
        assertNotNull(session.offlineSince, "no answer counts as no connection")
        assertEquals(mapOf("a" to true, "b" to true), pending(), "both kept for later; b was not held up behind a")
        assertEquals(listOf(true, true, false), session.checked())

        server.hanging.clear()
        session.retry()
        settle()
        assertEquals(listOf(Triple(monday, "a", true), Triple(monday, "b", true)), server.ticks)
        assertNull(session.offlineSince)
        assertEquals(emptyMap(), pending())
        assertEquals(listOf(true, true, false), session.checked())
    }

    @Test
    fun changingWeekNeverShowsAnotherWeeksList() = runTest {
        val session = session()
        val next = "2026-11-23"
        val later = "2026-11-30"
        fun check() {
            val state = session.state
            if (state is Load.Ready) assertEquals(session.weekStart, state.value.weekStart, "at ${testScheduler.currentTime} ms")
        }
        suspend fun stepThrough(ms: Long) = repeat((ms / 100).toInt()) {
            advanceTimeBy(100)
            runCurrent()
            check()
        }

        session.resume()
        stepThrough(2500)
        session.tick("a", true)
        session.open(next)
        check()
        assertEquals(Load.Loading, session.state, "nothing stored for next week: loading, not this week's rows")
        stepThrough(1000)
        session.open(later)
        stepThrough(500)
        session.open(next)
        stepThrough(1000)
        session.open(monday)
        check()
        assertTrue(session.shown().items.first().checked, "this week's stored copy at once, with its tick")
        stepThrough(20_000)
        assertEquals(monday, session.weekStart)
        assertEquals(listOf(true, false, false), session.checked())
    }

    @Test
    fun theWeekAndTheOpenedGroupsAreThereWhenTheScreenComesBack() = runTest {
        var today = "2026-11-18"
        val session = session { today }
        session.resume()
        settle()
        session.open("2026-11-23")
        session.toggle("done:Vegetables")
        session.scrollIndex = 12
        settle()

        // A recipe was opened; the screen shows again.
        session.resume()
        assertEquals("2026-11-23", session.weekStart)
        assertEquals(setOf("done:Vegetables"), session.expanded)
        assertEquals(12, session.scrollIndex)
        assertEquals("2026-11-23", session.shown().weekStart, "the list stays while it is fetched again")

        // Days later, by then the current week: stepping back to it means following the calendar again.
        today = "2026-11-25"
        session.open("2026-11-23")
        today = "2026-12-02"
        session.resume()
        assertEquals("2026-11-30", session.weekStart)
    }

    @Test
    fun aSessionMadeAgainAtItsPlaceShowsTheSamePlace() = runTest {
        val before = session()
        before.open("2026-11-23")
        before.toggle("elsewhere")
        before.scrollIndex = 7
        before.scrollOffset = 30
        // Android recreated the screen: the place was saved, the session is new.
        val after = ShoppingSession(backgroundScope, server, ShoppingCache(storage), { "2026-11-18" }, before.place)
        assertEquals(before.place, after.place)
        after.resume()
        assertEquals("2026-11-23", after.weekStart)
        settle()
        assertEquals("2026-11-23", after.shown().weekStart)
    }

    @Test
    fun withoutAConnectionATickStaysTickedAndGoesOutOnRetry() = runTest {
        val session = session()
        session.resume()
        settle()
        server.answer = { _, _ -> Load.Failed("Unable to resolve host") }
        session.tick("a", true)
        settle()
        assertEquals(listOf(true, false, false), session.checked(), "the row stays as chosen")
        assertNotNull(session.offlineSince, "the line that says the list is offline and ticks are sent later")
        assertEquals(mapOf("a" to true), pending())
        assertEquals(emptyList(), session.notices.toList())

        server.answer = null
        session.retry()
        settle()
        assertEquals(Triple(monday, "a", true), server.ticks.last())
        assertEquals(emptyMap(), pending())
        assertNull(session.offlineSince)
    }

    @Test
    fun anAdviceTickMadeOfflineGoesOutWithTheGramsAdvisedWhenItWasMade() = runTest {
        val rice = ShoppingItem("stock:i7", "Rice", 1000.0, null, "World", emptyList(), onHand = false, checked = false)
        server.lists[monday] = server.listFor(monday).copy(stockUp = listOf(rice))
        val session = session()
        session.resume()
        settle()
        server.unreachable = true
        session.tick("a", true)
        session.tick("stock:i7", true)
        settle()

        // Back online after the offers changed: the server's list no longer advises rice, and the
        // answer to the first tick says so before the rice tick goes out.
        server.lists[monday] = server.listFor(monday).copy(stockUp = emptyList())
        server.unreachable = false
        session.retry()
        settle()
        assertEquals(listOf("a" to null, "stock:i7" to 1000.0), server.sentGrams)

        // Un-ticking says nothing of an amount.
        session.tick("stock:i7", false)
        settle()
        assertEquals("stock:i7" to null, server.sentGrams.last())
    }

    /** A kilo of chicken, €10, with a quarter off at Jumbo until the 17th: it saves €2.50 on the 16th and nothing on the 18th. */
    private val chicken = ShoppingItem(
        "i1", "Chicken", 1000.0, null, "Meat", listOf("Curry"), onHand = false, checked = false, regularPrice = 10.0,
        offers = listOf(Offer(Store.JUMBO, "Kipfilet 1 kg", "25% korting", packSize = "1 kg", paidShare = 0.75, validFrom = monday, validUntil = "2026-11-17")),
        toComeGrams = 1000.0,
    )

    @Test
    fun anOfflineTickSendsWhatTheRowShowedWhenItWasTicked() = runTest {
        server.lists[monday] = listWith(chicken)
        var day = monday
        val session = session(today = { day })
        session.resume()
        settle()
        server.unreachable = true
        session.tick("i1", true)
        settle()
        assertEquals(emptyList(), server.sentShown, "nothing went out")

        // Sent the day after the offer ended: it says what was showing on Monday.
        day = "2026-11-18"
        server.unreachable = false
        session.retry()
        settle()
        val shown = ShoppingCheck.Shown(grams = 1000.0, saving = 2.5, savingStore = Store.JUMBO, tickedOn = monday)
        assertEquals(listOf<Pair<String, ShoppingCheck.Shown?>>("i1" to shown), server.sentShown)
        assertEquals(emptyMap(), pending())
        assertNull(ShoppingCache(storage).shown(monday, "i1"), "forgotten once the server has the tick")
    }

    @Test
    fun aTickSentAtOnceSendsNothingOfWhatItShowed() = runTest {
        server.lists[monday] = listWith(chicken)
        val session = session(today = { monday })
        session.resume()
        settle()
        session.tick("i1", true)
        settle()
        assertEquals(listOf<Pair<String, ShoppingCheck.Shown?>>("i1" to null), server.sentShown)
    }

    @Test
    fun aTickWithoutAnAnswerKeepsWhatItShowedUntilItIsSent() = runTest {
        server.lists[monday] = listWith(chicken)
        val session = session(today = { monday })
        session.resume()
        settle()
        server.hanging += "i1"
        session.tick("i1", true)
        settle()
        // The app is closed and opened later: what the row showed is stored with the tick.
        assertEquals(2.5, ShoppingCache(storage).shown(monday, "i1")?.saving)
        assertEquals(mapOf("i1" to true), pending())
    }

    @Test
    fun anUnTickAndAnOwnItemSendNothingOfWhatTheyShowed() = runTest {
        server.lists[monday] = listWith(chicken.copy(checked = true), chicken.copy(key = "x:1", name = "Coffee"))
        val session = session(today = { monday })
        session.resume()
        settle()
        server.unreachable = true
        session.tick("i1", false)
        session.tick("x:1", true)
        settle()
        assertNull(ShoppingCache(storage).shown(monday, "i1"))
        assertNull(ShoppingCache(storage).shown(monday, "x:1"))
    }

    @Test
    fun aServerFromBeforeItGetsTheLateTickWithoutWhatItShowed() = runTest {
        server.lists[monday] = listWith(chicken)
        server.refusesShown = true
        var day = monday
        val session = session(today = { day })
        session.resume()
        settle()
        server.unreachable = true
        session.tick("i1", true)
        settle()
        day = "2026-11-18"
        server.unreachable = false
        session.retry()
        settle()
        assertEquals(listOf<Pair<String, ShoppingCheck.Shown?>>("i1" to null), server.sentShown, "taken the second time, as it was before")
        assertEquals(emptyMap(), pending())
        assertEquals(0, session.stuckTicks)
        assertTrue(session.notices.isEmpty())
    }

    @Test
    fun aStoredTickFromBeforeItGoesOutAsBefore() = runTest {
        server.lists[monday] = listWith(chicken)
        // An entry of an earlier version of the app: no shown.
        val list = kotlinx.serialization.json.Json.encodeToString(listWith(chicken))
        storage.set("shopping.$monday", """{"list":$list,"savedAt":"2026-11-16T10:00:00Z","pending":{"i1":true}}""")
        storage.set("shopping.weeks", monday)
        val session = session()
        session.resume()
        settle()
        assertEquals(listOf<Pair<String, ShoppingCheck.Shown?>>("i1" to null), server.sentShown)
    }

    @Test
    fun aServerErrorKeepsTheTickForLaterToo() = runTest {
        val session = session()
        session.resume()
        settle()
        server.answer = { _, _ -> Load.Failed("Bad gateway", 502) }
        session.tick("a", true)
        settle()
        assertEquals(listOf(true, false, false), session.checked())
        assertEquals(mapOf("a" to true), pending())
        assertNotNull(session.offlineSince)
    }

    @Test
    fun aRefusedTickGoesBackAndIsReported() = runTest {
        val session = session()
        session.resume()
        settle()
        server.answer = { _, _ -> Load.Failed("No such item", 404) }
        session.tick("a", true)
        assertEquals(listOf(true, false, false), session.checked())
        settle()
        assertEquals(listOf(false, false, false), session.checked(), "back as the server has it")
        assertEquals(listOf(ShoppingMsg.TICK_FAILED to "No such item"), session.notices.map { it.msg to it.detail })
        assertEquals(emptyMap(), pending())
        assertNull(session.offlineSince)
    }

    @Test
    fun afterClosingNothingIsSentOrStoredSoLoggingOutLeavesNothingBehind() = runTest {
        val session = session()
        session.resume()
        settle()
        session.tick("a", true)
        session.tick("b", true)
        runCurrent()
        session.close()
        storage.logOut()
        settle()
        assertEquals(emptyList(), server.ticks)
        assertNull(ShoppingCache(storage).load(monday))

        val nextAccount = session()
        nextAccount.resume()
        assertEquals(Load.Loading, nextAccount.state, "nothing of the previous account is shown")
        settle()
        assertEquals(listOf(false, false, false), nextAccount.checked())
        assertEquals(emptyList(), server.ticks)
    }

    @Test
    fun hidingAdviceGoesInTurnAndAFailureIsReported() = runTest {
        val session = session()
        session.resume()
        settle()
        session.tick("a", true)
        session.dismiss(ShoppingAdviceGroup.BUY_AHEAD, true)
        settle()
        assertTrue(session.shown().buyAheadDismissed)
        assertEquals(listOf(true, false, false), session.checked(), "the answer to the tick came first")

        val offline = ShoppingSession(backgroundScope, object : ShoppingServer by server {
            override suspend fun dismiss(weekStart: String, group: ShoppingAdviceGroup, dismissed: Boolean) = Load.Failed("Server down", 503)
        }, ShoppingCache(storage), { "2026-11-18" })
        offline.resume()
        settle()
        offline.dismiss(ShoppingAdviceGroup.BUY_AHEAD, false)
        settle()
        assertEquals(listOf(ShoppingMsg.ADVICE_CHANGE_FAILED to "Server down"), offline.notices.map { it.msg to it.detail })
    }

    private fun lists() = server.asked.count { it.startsWith("list") }

    @Test
    fun anotherDevicesTickShowsWithinOneRefreshAndATapJustBeforeTheAnswerIsNotFlippedBack() = runTest {
        val session = session(shoppingMode = true)
        session.inFront(true)
        session.resume()
        wait(3000)
        assertEquals(listOf(false, false, false), session.checked())
        assertEquals(1, lists(), "coming to the front and opening the screen fetch the list once")

        server.tickElsewhere(monday, "c", true)
        wait(ShoppingSession.REFRESH_IN_SHOP.inWholeMilliseconds - 3000 + server.latency)
        assertEquals(listOf(false, false, true), session.checked(), "one interval later, without any action")

        // The next refresh goes out at 60 s; "a" is tapped while its answer is on the way.
        wait(29_000)
        assertEquals(3, lists())
        session.tick("a", true)
        repeat(100) {
            wait(100)
            assertEquals(listOf(true, false, true), session.checked(), "at ${testScheduler.currentTime} ms")
        }
        assertEquals(Triple(monday, "a", true), server.ticks.last())
        assertEquals(emptyMap(), pending())
    }

    @Test
    fun outsideShoppingModeTheListIsFetchedEveryTwoMinutes() = runTest {
        val session = session()
        session.inFront(true)
        session.resume()
        wait(ShoppingSession.REFRESH.inWholeMilliseconds - 1)
        assertEquals(1, lists())
        wait(1)
        assertEquals(2, lists())
    }

    @Test
    fun offlineTheListReconnectsByItselfAndSendsTheStoredTicks() = runTest {
        val session = session()
        session.inFront(true)
        session.resume()
        wait(3000)
        server.unreachable = true
        session.tick("a", true)
        runCurrent()
        assertNotNull(session.offlineSince)
        assertEquals(mapOf("a" to true), pending())

        server.unreachable = false
        // The check at 30 s sends the tick, then fetches the list: two answers later the list is online.
        wait(ShoppingSession.RECONNECT.inWholeMilliseconds - 3000 + 2 * server.latency)
        assertNull(session.offlineSince, "the offline line goes without a tap on Retry")
        assertEquals(listOf(Triple(monday, "a", true)), server.ticks)
        assertEquals(emptyMap(), pending())
        assertEquals(listOf(true, false, false), session.checked())
    }

    @Test
    fun offlineOnlyTheReconnectAttemptGoesOutOncePerInterval() = runTest {
        val session = session(shoppingMode = true)
        session.inFront(true)
        session.resume()
        wait(3000)
        server.unreachable = true
        session.tick("a", true)
        session.tick("b", true)
        runCurrent()
        server.asked.clear()

        wait(10 * ShoppingSession.RECONNECT.inWholeMilliseconds)
        assertEquals(List(10) { "check $monday a" }, server.asked, "the first stored tick, which finds no connection, and nothing else")
        assertNotNull(session.offlineSince)
        assertEquals(listOf(true, true, false), session.checked())
    }

    @Test
    fun nothingIsFetchedByItselfWhileTheScreenIsNotShownOrTheAppIsBehind() = runTest {
        val session = session(shoppingMode = true)
        session.inFront(true)
        wait(10 * 60_000)
        assertEquals(listOf("list $monday"), server.asked, "the current week once, so it is on the phone for the shop")
        assertNotNull(ShoppingCache(storage).load(monday))

        session.resume()
        wait(3000)
        session.inFront(false)
        val behind = server.asked.size
        wait(10 * 60_000)
        assertEquals(behind, server.asked.size, "behind another app")

        session.inFront(true)
        wait(3000)
        assertEquals(behind + 1, server.asked.size, "fetched again on coming back")
        session.leave()
        val left = server.asked.size
        wait(10 * 60_000)
        assertEquals(left, server.asked.size, "on another screen")

        // The plan changed on the Plan tab: the current week is fetched for the shop, unless the screen shows.
        session.prefetch()
        wait(3000)
        assertEquals(left + 1, server.asked.size)
        session.resume()
        session.prefetch()
        wait(3000)
        assertEquals(left + 2, server.asked.size, "the screen's own fetch only")
    }

    @Test
    fun followingTheFrontStopsTheChecksBehindAndFetchesOnceOnReturn() = runTest {
        val session = session(shoppingMode = true)
        val front = AppFront(inFront = true)
        backgroundScope.launch { session.follow(front, loggedIn = true) }
        session.resume()
        wait(3000)
        val shown = server.asked.size

        front.report(false)
        wait(10 * 60_000)
        assertEquals(shown, server.asked.size, "nothing while behind")

        front.report(true)
        wait(3000)
        assertEquals(shown + 1, server.asked.size, "fetched once on coming back")
    }

    @Test
    fun loggedOutTheFrontAsksNothing() = runTest {
        val session = session(shoppingMode = true)
        backgroundScope.launch { session.follow(AppFront(inFront = true), loggedIn = false) }
        wait(10 * 60_000)
        assertEquals(emptyList(), server.asked)
    }

    @Test
    fun theChecksStopWhenTheSessionCloses() = runTest(timeout = 10.seconds) {
        // In the test's own scope, which ends only when every coroutine in it has: a check left running fails the test.
        val session = ShoppingSession(this, server, ShoppingCache(storage), { "2026-11-18" }, shoppingMode = { true })
        session.inFront(true)
        session.resume()
        wait(5 * 60_000)
        val asked = server.asked.size
        assertTrue(asked >= 10, "fetched every 30 s")
        session.close()
        wait(10 * 60_000)
        assertEquals(asked, server.asked.size)
    }

    @Test
    fun storedTicksOfLastWeekAreSentOnStartWhileThisWeekIsShown() = runTest {
        val lastWeek = "2026-11-09"
        ShoppingCache(storage).apply {
            save(server.listFor(monday), "earlier")
            choose(monday, "b", true)
            save(server.listFor(lastWeek), "earlier")
            choose(lastWeek, "a", true)
        }
        val session = session()
        session.inFront(true)
        session.resume()
        settle()
        assertEquals(listOf(Triple(lastWeek, "a", true), Triple(monday, "b", true)), server.ticks, "oldest week first")
        assertEquals(emptyMap(), pending(lastWeek))
        assertEquals(monday, session.weekStart)
        assertEquals(listOf(false, true, false), session.checked())
    }

    @Test
    fun withoutAnAnswerToAStoredTickTheOfflineLineDoesNotWaitForTheListAsWell() = runTest {
        ShoppingCache(storage).apply {
            save(server.listFor(monday), "earlier")
            choose(monday, "b", true)
        }
        server.hanging += "b"
        val session = session()
        session.resume()
        assertEquals(listOf(false, true, false), session.checked(), "the stored copy at once")
        wait(ShoppingSession.TIME_LIMIT.inWholeMilliseconds)
        assertNotNull(session.offlineSince, "after one time limit, not two")
        assertEquals(listOf("check $monday b"), server.asked)
    }

    @Test
    fun aStoredTickTheServerKeepsFailingOnStaysStoredAndTheListStillLoads() = runTest {
        ShoppingCache(storage).apply {
            save(server.listFor(monday), "earlier")
            choose(monday, "b", true)
        }
        // So the list from the server can be told from the stored copy.
        server.tickElsewhere(monday, "c", true)
        server.answer = { _, _ -> Load.Failed("Internal server error", 500) }
        val session = session()
        session.inFront(true)
        session.resume()
        wait(2 * server.latency)
        assertEquals(listOf("check $monday b", "list $monday"), server.asked)
        assertEquals(listOf(false, true, true), session.checked(), "the server's list, with the stored tick laid over it")
        assertNull(session.offlineSince)
        assertEquals(mapOf("b" to true), pending(), "kept for later")
        assertEquals(emptyList(), session.notices.toList())

        server.asked.clear()
        wait(10 * ShoppingSession.RECONNECT.inWholeMilliseconds)
        assertEquals(List(2) { listOf("check $monday b", "list $monday") }.flatten(), server.asked, "with each refresh, not every 30 s")
        assertEquals(mapOf("b" to true), pending())
        assertEquals(listOf(false, true, true), session.checked())
    }

    @Test
    fun aStoredTickWithNoAnswerAtAllStillHoldsTheListBackAfterAServerError() = runTest {
        val lastWeek = "2026-11-09"
        ShoppingCache(storage).apply {
            save(server.listFor(lastWeek), "earlier")
            choose(lastWeek, "a", true)
            save(server.listFor(monday), "earlier")
            choose(monday, "b", true)
        }
        server.answer = { _, _ -> Load.Failed("Internal server error", 500) }
        server.hanging += "b"
        val session = session()
        session.resume()
        wait(server.latency + ShoppingSession.TIME_LIMIT.inWholeMilliseconds - 1)
        assertNull(session.offlineSince, "still waiting for the second tick")
        wait(1)
        assertNotNull(session.offlineSince, "after one time limit, not two")
        assertEquals(listOf("check $lastWeek a", "check $monday b"), server.asked, "the list is not waited for as well")
        assertEquals(listOf(false, true, false), session.checked())
        assertEquals(mapOf("a" to true), pending(lastWeek))
        assertEquals(mapOf("b" to true), pending())
    }

    @Test
    fun aListTheServerRefusesIsNotAskedForAgainByItself() = runTest {
        server.listAnswer = Load.Failed("No such week", 404)
        val session = session(shoppingMode = true)
        session.inFront(true)
        session.resume()
        wait(server.latency)
        assertEquals(Load.Failed("No such week", 404), session.state)
        wait(10 * ShoppingSession.RECONNECT.inWholeMilliseconds)
        assertEquals(1, lists(), "not by the checks, even in shopping mode")

        session.retry()
        wait(server.latency)
        assertEquals(2, lists(), "Retry asks again")
        session.leave()
        session.resume()
        wait(server.latency)
        assertEquals(3, lists(), "and so does showing the screen again")
        wait(10 * ShoppingSession.RECONNECT.inWholeMilliseconds)
        assertEquals(3, lists())

        // A server error is not a refusal: that list is tried again by itself.
        server.listAnswer = Load.Failed("Bad gateway", 502)
        session.retry()
        wait(server.latency)
        assertEquals(Load.Failed("Bad gateway", 502), session.state)
        wait(ShoppingSession.RECONNECT.inWholeMilliseconds)
        assertEquals(5, lists())
    }

    @Test
    fun aServerErrorKeepsTheListShownWithTheOfflineLineAndIsTriedAgain() = runTest {
        val session = session()
        session.inFront(true)
        session.resume()
        wait(3000)
        server.listAnswer = Load.Failed("Bad gateway", 502)
        session.retry()
        wait(3000)
        assertEquals(listOf(false, false, false), session.checked(), "the list stays")
        assertNotNull(session.offlineSince)

        server.listAnswer = null
        wait(ShoppingSession.RECONNECT.inWholeMilliseconds)
        assertNull(session.offlineSince)
    }

    @Test
    fun aRequestAnsweredAfterARefusedRenewalLoggedOutStoresNothingForTheNextAccount() = runTest {
        val session = session()
        session.resume()
        settle()
        session.tick("a", true)
        session.retry()
        wait(1000)
        // The identity provider refused to renew the login: the app logs out before the session is closed.
        storage.logOut()
        settle()
        assertEquals(Triple(monday, "a", true), server.ticks.single(), "the request was under way")
        assertNull(ShoppingCache(storage).load(monday), "neither its answer nor the list fetched after it was stored")
        assertEquals(emptyMap(), pending())

        val nextAccount = session()
        nextAccount.inFront(true)
        nextAccount.resume()
        assertEquals(Load.Loading, nextAccount.state, "nothing of the previous account is shown")
        settle()
        assertEquals(1, server.ticks.size, "no tick of the previous account is sent again")
    }

    @Test
    fun addingAnOwnItemSendsTheTrimmedNameAndShowsTheListWithUnsentTicksLaidOver() = runTest {
        val session = session()
        session.resume()
        settle()
        // A tick the server has not heard of yet, as after a lost connection.
        ShoppingCache(storage).choose(monday, "b", true)

        var added = 0
        session.add("  Coffee ") { added++ }
        assertTrue(session.adding)
        settle()

        assertEquals(listOf("Coffee"), server.added)
        assertEquals(1, added)
        assertFalse(session.adding)
        assertEquals(listOf("a", "b", "c", "Coffee"), session.shown().items.map { it.name })
        assertEquals(listOf(false, true, false, false), session.checked(), "the waiting tick is still laid over the answer")
    }

    @Test
    fun aSecondTapWhileAnItemIsOnItsWayAddsNothing() = runTest {
        val session = session()
        session.resume()
        settle()

        session.add("Coffee") {}
        session.add("Coffee") {}
        settle()

        assertEquals(listOf("Coffee"), server.added)
    }

    @Test
    fun aRefusedItemShowsTheServersMessageAndDoesNotCallBack() = runTest {
        val session = session()
        session.resume()
        settle()
        server.addAnswer = Load.Failed("Too many open items", 409)

        var added = false
        session.add("Coffee") { added = true }
        settle()

        assertFalse(added, "the field keeps its text")
        assertFalse(session.adding, "and can be used again")
        assertEquals(listOf(ShoppingMsg.EXTRA_ADD_FAILED to "Too many open items"), session.notices.map { it.msg to it.detail })
        assertEquals(listOf("a", "b", "c"), session.shown().items.map { it.name })
    }

    @Test
    fun offlineNothingIsAddedOrRemovedAndTheNoticeSaysWhy() = runTest {
        val session = session()
        session.resume()
        settle()
        server.unreachable = true
        session.tick("a", true)
        settle()
        assertNotNull(session.offlineSince)
        assertFalse(session.canChangeExtras)
        server.asked.clear()

        var added = false
        session.add("Coffee") { added = true }
        session.remove(1)
        settle()

        assertFalse(added)
        assertTrue(server.asked.none { it.startsWith("add") || it.startsWith("remove") })
        assertEquals(listOf(ShoppingMsg.EXTRA_NEEDS_CONNECTION, ShoppingMsg.EXTRA_NEEDS_CONNECTION), session.notices.map { it.msg })
    }

    @Test
    fun removingAnOwnItemShowsTheListWithoutItAndDropsItsWaitingTick() = runTest {
        val session = session()
        session.resume()
        settle()
        session.add("Coffee") {}
        settle()
        ShoppingCache(storage).choose(monday, "x:1", true)

        session.remove(1)
        settle()

        assertEquals(listOf("a", "b", "c"), session.shown().items.map { it.name })
        assertEquals(emptyMap(), pending())
        assertEquals(1L, ShoppingItem("x:1", "Coffee", null, null, "Other", emptyList(), onHand = false, checked = false).extraId)
        assertNull(ShoppingItem("a", "a", null, null, "Other", emptyList(), onHand = false, checked = false).extraId)
    }

    @Test
    fun withoutAConnectionTickingAgainSaysItIsNotSavedAndAsksNothing() = runTest {
        server.lists[monday] = listWith(needsMore)
        val session = session()
        session.resume()
        settle()
        server.unreachable = true
        session.tick("b", true)
        settle()
        assertNotNull(session.offlineSince, "the line says ticks are sent later")
        server.asked.clear()

        session.tickAgain("a")
        settle()

        // Not the generic "could not save the tick" beside a line that promises ticks are sent later.
        assertEquals(listOf(ShoppingMsg.TICK_AGAIN_NEEDS_CONNECTION), session.notices.map { it.msg })
        assertEquals(emptyList(), server.asked, "the server cannot answer")
        assertEquals(emptyMap(), pending().filterKeys { it == "a" }, "ticking again is not kept for later")
    }

    @Test
    fun tickingAgainThatGetsNoAnswerSaysItNeedsAConnectionAndKeepsTheTechnicalTextBehindDetails() = runTest {
        server.lists[monday] = listWith(needsMore)
        val session = session()
        session.resume()
        settle()

        server.oneStepAnswer = Load.Failed("unexpected end of stream on http://192.168.1.2:8080/...", technical = true, lostConnection = true)
        session.tickAgain("a")
        settle()

        val notice = session.notices.single()
        assertEquals(ShoppingMsg.TICK_AGAIN_NEEDS_CONNECTION, notice.msg)
        assertEquals("unexpected end of stream on http://192.168.1.2:8080/...", notice.technical)
        assertFalse(Strings.English(notice.msg, notice.detail).contains("http"), "a plain sentence")
    }

    @Test
    fun aFailureWithATechnicalTextSaysItPlainlyAndKeepsTheTextForDetails() = runTest {
        val session = session()
        session.resume()
        settle()
        server.addAnswer = Load.Failed("unexpected end of stream on http://192.168.1.2:8080/...", technical = true, lostConnection = true)

        session.add("Coffee") {}
        settle()

        val notice = session.notices.single()
        assertEquals("Could not add the item: the server could not be reached", Strings.English(notice.msg, notice.detail))
        assertEquals("unexpected end of stream on http://192.168.1.2:8080/...", notice.technical)
    }

    @Test
    fun aTechnicalFailureThatIsNoLostConnectionIsNotCalledOne() = runTest {
        val session = session()
        session.resume()
        settle()
        server.addAnswer = Load.Failed("Cannot read property of undefined", technical = true)

        session.add("Coffee") {}
        settle()

        val notice = session.notices.single()
        assertEquals("Could not add the item: something went wrong", Strings.English(notice.msg, notice.detail))
        assertEquals("Cannot read property of undefined", notice.technical)
    }

    @Test
    fun anAnswerTheAppCannotReadIsNoMissingAnswerSoTheListLoadsAndTheTickIsCounted() = runTest {
        ShoppingCache(storage).apply {
            save(server.listFor(monday), "earlier")
            choose(monday, "b", true)
        }
        server.tickElsewhere(monday, "c", true)
        server.answer = { _, _ -> Load.Failed("Unexpected JSON token at offset 0", technical = true, unreadable = true) }
        val session = session()
        session.inFront(true)
        session.resume()
        wait(2 * server.latency)

        assertEquals(listOf("check $monday b", "list $monday"), server.asked, "the list is not held back")
        assertNull(session.offlineSince)
        assertEquals(mapOf("b" to true), pending(), "kept for later")
        assertEquals(1, session.stuckTicks, "the quiet hint that it has not reached the server")

        // Once the server takes it, the hint goes.
        server.answer = null
        wait(10 * ShoppingSession.RECONNECT.inWholeMilliseconds)
        assertEquals(emptyMap(), pending())
        assertEquals(0, session.stuckTicks)
    }

    @Test
    fun aStoredTickThatKeepsGettingAServerErrorIsCountedAndNoAnswerIsNot() = runTest {
        ShoppingCache(storage).apply {
            save(server.listFor(monday), "earlier")
            choose(monday, "b", true)
        }
        server.answer = { _, _ -> Load.Failed("Internal server error", 500) }
        val session = session()
        session.resume()
        wait(2 * server.latency)
        assertEquals(1, session.stuckTicks)

        // Nothing answering is the offline line's business, not this hint's.
        server.answer = { _, _ -> Load.Failed("Unable to resolve host") }
        session.retry()
        settle()
        assertEquals(0, session.stuckTicks)
        assertNotNull(session.offlineSince)
    }

    /** Jumbo's curry sauce, which the server matched to the curry powder (issue 316). */
    private val helaCurry = Offer(Store.JUMBO, "Hela curry", "25% korting", validFrom = "2026-11-16", validUntil = "2026-11-22", paidShare = 0.75)

    /** This week's list with the curry powder's row, on offer with [helaCurry], and a row to tick. */
    private fun curryList() = listWith(
        ShoppingItem("i3", "Curry powder", 50.0, null, "Spices", emptyList(), onHand = false, checked = false, offers = listOf(helaCurry), regularPrice = 2.0),
        ShoppingItem("a", "a", 100.0, null, "Vegetables", emptyList(), onHand = false, checked = false),
    )

    private fun ShoppingSession.curryOffers() = shown().items.first().offers

    private fun ShoppingSession.excludeCurry(excluded: Boolean = true) = exclusions.choose(Store.JUMBO, "Hela curry", 3, "Curry powder", excluded)

    @Test
    fun notThisIngredientTakesTheDealOffAtOnceAndGoesOutInTurnBeforeTheListIsFetched() = runTest {
        server.lists[monday] = curryList()
        val session = session()
        session.resume()
        settle()
        assertEquals(listOf(helaCurry), session.curryOffers())
        server.asked.clear()

        session.excludeCurry()

        assertEquals(emptyList(), session.curryOffers(), "gone at once, before the server answers")
        settle()
        assertEquals(listOf("exclude Hela curry", "list $monday"), server.asked)
        assertEquals(listOf("Hela curry"), session.exclusions.known.map { it.title })
        assertEquals(emptyList(), session.exclusions.waiting)
        // The server's list of before still has the deal; what the server answered keeps it off.
        assertEquals(emptyList(), session.curryOffers())
    }

    @Test
    fun anExclusionMadeOfflineSurvivesClosingTheAppAndGoesOutBeforeTheTicksWhenTheConnectionIsBack() = runTest {
        server.lists[monday] = curryList()
        val first = session()
        first.resume()
        settle()
        server.unreachable = true
        first.excludeCurry()
        first.tick("a", true)
        settle()
        assertNotNull(first.offlineSince)
        assertEquals(emptyList(), first.curryOffers())
        assertEquals(1, ShoppingCache(storage).waitingExclusions().size)
        first.close()

        // The app was closed; opened again without a connection, the deal stays off.
        val second = session()
        second.resume()
        settle()
        assertEquals(emptyList(), second.curryOffers())

        server.unreachable = false
        server.asked.clear()
        second.retry()
        settle()
        assertEquals(listOf("exclude Hela curry", "check $monday a", "list $monday"), server.asked)
        assertEquals(emptyList(), ShoppingCache(storage).waitingExclusions())
        assertEquals(listOf("Hela curry"), server.excluded.map { it.title })
        assertEquals(emptyList(), second.curryOffers())
    }

    @Test
    fun undoneBeforeItWasSentTheDealIsBackAtOnceAndOnlyTheTakeBackGoesOut() = runTest {
        server.lists[monday] = curryList()
        val session = session()
        session.resume()
        settle()
        server.unreachable = true
        session.excludeCurry()
        session.excludeCurry(excluded = false)

        assertEquals(listOf(helaCurry), session.curryOffers())
        assertEquals(listOf(false), ShoppingCache(storage).waitingExclusions().map { it.excluded })
        settle()

        server.unreachable = false
        server.asked.clear()
        session.retry()
        settle()
        // Sent anyway: an exclusion whose answer was lost may have reached the server.
        assertEquals(listOf("take back Hela curry", "list $monday"), server.asked)
        assertEquals(listOf(helaCurry), session.curryOffers())
    }

    @Test
    fun anExclusionTheServerRefusesIsDroppedAndReportedAndTheDealComesBack() = runTest {
        server.lists[monday] = curryList()
        val session = session()
        session.resume()
        settle()
        // The ingredient was deleted on another device in the meantime.
        server.exclusionAnswer = Load.Failed("No ingredient with id 3", 404)

        session.excludeCurry()
        settle()

        assertEquals(listOf(PricesMsg.EXCLUSION_FAILED to "No ingredient with id 3"), session.notices.map { it.msg to it.detail })
        assertEquals(emptyList(), ShoppingCache(storage).waitingExclusions())
        assertEquals(listOf(helaCurry), session.curryOffers())
    }

    @Test
    fun anExclusionTheServerFailsOnStaysForLaterAndTheListStillLoads() = runTest {
        server.lists[monday] = curryList()
        val session = session()
        session.resume()
        settle()
        server.exclusionAnswer = Load.Failed("Internal server error", 500)

        session.excludeCurry()
        settle()

        assertNull(session.offlineSince)
        assertEquals(1, ShoppingCache(storage).waitingExclusions().size)
        assertEquals(emptyList(), session.curryOffers())
        assertEquals(emptyList(), session.notices.toList())
    }

    @Test
    fun theListInSettingsComesFromTheServerAfterWhatWaitsHere() = runTest {
        server.lists[monday] = curryList()
        // Another device on the account said the venison is not beef.
        server.excluded += OfferExclusion(Store.AH, "Excellent hertenbiefstuk", "excellent hertenbiefstuk", 4, "Beef steak", "2026-11-17T10:00:00Z")
        val session = session()
        server.unreachable = true
        session.excludeCurry()
        settle()
        assertEquals(listOf("Hela curry"), session.exclusions.inEffect.map { it.title })
        assertTrue(session.exclusions.isWaiting(session.exclusions.inEffect.single()))

        server.unreachable = false
        session.exclusions.refresh()
        settle()

        assertEquals(listOf("Hela curry", "Excellent hertenbiefstuk"), session.exclusions.inEffect.map { it.title })
        assertFalse(session.exclusions.inEffect.any { session.exclusions.isWaiting(it) })
        assertEquals(session.exclusions.known, ShoppingCache(storage).knownExclusions())
    }
}

