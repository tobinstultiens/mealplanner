package dev.tobins.mealplanner.app

import androidx.compose.foundation.background
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingAdviceGroup
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.Store
import kotlinx.coroutines.launch

/**
 * What to buy for a week's plan, grouped by shop section in the order you walk the shop, with
 * items to tick off. The server sends the items in that order, so grouping keeps it. A second
 * view groups by the store to buy at, among the account's [accountShops], with what each store's
 * offers save; one store can be chosen as the shop you are in.
 *
 * Below this week's part comes the server's advice, each group in walking order and ticked off
 * the same way: what to buy ahead for next week, and what to stock up on. Each group can be
 * hidden for the week.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShoppingScreen(
    session: ShoppingSession,
    settings: SettingsStorage,
    accountShops: AccountShops,
    onOpenPantry: () -> Unit,
    onOpenOffers: () -> Unit,
) {
    val t = LocalStrings.current
    val today = todayIso()
    // The week, the list with its ticks, the opened groups and the scroll position live in the
    // session, which the app keeps: leaving the screen for a recipe or another tab loses none of it.
    val weekStart = session.weekStart
    val state = session.state
    val offlineSince = session.offlineSince
    val expanded = session.expanded
    var reload by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var shoppingMode by remember { mutableStateOf(settings.shoppingMode()) }
    var view by remember { mutableStateOf(settings.shoppingView()) }
    var chosenStore by remember { mutableStateOf(settings.shoppingStore()) }
    val shops = accountShops.stores
    val listState = rememberLazyListState(session.scrollIndex, session.scrollOffset)
    DisposableEffect(listState) {
        onDispose {
            session.scrollIndex = listState.firstVisibleItemIndex
            session.scrollOffset = listState.firstVisibleItemScrollOffset
        }
    }
    KeepScreenOn(shoppingMode)
    // The session keeps the list current while the screen shows, and stops when it goes.
    DisposableEffect(session) {
        session.resume()
        onDispose { session.leave() }
    }

    // What the session has to tell, also from while the screen was not shown. A technical text
    // stays behind "Details", as in an error banner.
    val notice = session.notices.firstOrNull()
    var technicalDetails by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        val answer = snackbar.showSnackbar(
            t(notice.msg, notice.detail),
            actionLabel = notice.technical?.let { t(Msg.DETAILS) },
            duration = if (notice.technical != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (answer == SnackbarResult.ActionPerformed) technicalDetails = notice.technical
        session.shown(notice)
    }
    technicalDetails?.let { DetailsDialog(t(Msg.DETAILS), it) { technicalDetails = null } }

    /** Sets one item's tick: shown at once, the server hears of it in the background. */
    fun tick(key: String, checked: Boolean) = session.tick(key, checked)

    /** In shopping mode a mis-tap is one tap from undone. */
    fun tickWithUndo(item: ShoppingItem) {
        tick(item.key, !item.checked)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val answer = snackbar.showSnackbar(
                if (item.checked) t(Msg.BACK_ON_LIST, item.name) else t(Msg.TICKED_OFF, item.name),
                actionLabel = t(Msg.UNDO),
                duration = SnackbarDuration.Short,
            )
            if (answer == SnackbarResult.ActionPerformed) tick(item.key, item.checked)
        }
    }

    fun chooseStore(store: Store?) {
        chosenStore = store
        settings.saveShoppingStore(store)
        // The list changes completely; start at its top.
        scope.launch { listState.scrollToItem(0) }
    }

    /** One group's rows; with shopping mode the ticked ones wait in a collapsed "Done" line. */
    fun LazyListScope.groupItems(id: String, items: List<ShoppingItem>, row: @Composable (ShoppingItem) -> Unit) {
        val (done, open) = if (shoppingMode) items.partition { it.nothingLeftToBuy } else emptyList<ShoppingItem>() to items
        items(open, key = { it.key }) { row(it) }
        if (done.isNotEmpty()) {
            val doneOpen = "done:$id" in expanded
            item(key = "d:$id") { DoneGroupHeader(done.size, doneOpen) { session.toggle("done:$id") } }
            if (doneOpen) items(done, key = { it.key }) { row(it) }
        }
    }

    /**
     * The shop sections in walking order, each with its icon and, in shopping mode, its Done group.
     * [group] tells the advice groups' sections apart from this week's, which have the same titles.
     */
    fun LazyListScope.sectionItems(sections: List<ShoppingSectionView>, group: String = "", row: @Composable (ShoppingItem) -> Unit) {
        sections.forEach { section ->
            val title = section.title
            val id = group + title
            val sectionOpen = !section.finished || id in expanded
            item(key = "h:$id") {
                SectionHeaderRow(title, section, sectionOpen) { session.toggle(id) }
            }
            items(section.open, key = { it.key }) { item -> row(item) }
            if (section.finished && sectionOpen) {
                items(section.done, key = { it.key }) { item -> row(item) }
            } else if (section.done.isNotEmpty() && !section.finished) {
                val doneOpen = "done:$id" in expanded
                item(key = "d:$id") {
                    DoneGroupHeader(section.done.size, doneOpen) { session.toggle("done:$id") }
                }
                if (doneOpen) items(section.done, key = { it.key }) { item -> row(item) }
            }
        }
    }

    /** Hides one advice group for the week, or shows it again; the list comes back from the server. */
    fun dismiss(group: ShoppingAdviceGroup, dismissed: Boolean) = session.dismiss(group, dismissed)

    /**
     * One advice group below this week's list: its title with what it is for and saves, a way to
     * hide it, and its items; while hidden, a line to show it again. At a chosen [store] only the
     * items on offer there, by section. Across the stores, [byStore] groups them like this week's
     * items: under the store whose offer they are, otherwise by section.
     */
    fun LazyListScope.adviceGroup(texts: AdviceTexts, list: ShoppingList, store: Store?, byStore: Boolean, row: @Composable (ShoppingItem) -> Unit) {
        val key = "a:${texts.group.path}"
        if (texts.dismissed(list)) {
            item(key = key) { HiddenAdvice(t(texts.hidden)) { dismiss(texts.group, false) } }
            return
        }
        val shown = adviceAt(texts.items(list), store, shops, today)
        if (shown.isEmpty()) return
        item(key = key) { AdviceHeader(t(texts.title), adviceHint(texts, shown, shops, today, t, provisional = list.buyAheadProvisional)) { dismiss(texts.group, true) } }
        val id = "${texts.group.path}:"
        if (byStore) {
            storeOverview(shown, shops, today).stores.forEach { group ->
                item(key = "$id${group.store.name}") {
                    GroupHeader(group.title(t), group.saving?.let { t(Msg.ADVICE_SAVING, formatEuro(it, t.locale)) }.orEmpty(), onClick = null)
                }
                groupItems(id + group.store.name, group.items, row)
            }
        } else {
            sectionItems(shoppingSections(shown, shoppingMode), group = id, row = row)
        }
    }

    // Another device may have changed the stores; without a connection the last known ones stay.
    LaunchedEffect(accountShops, reload) { load { accountShops.sync() } }

    /** Shown at once; when the server can't be told, the chips go back and say why. */
    fun chooseShops(chosen: Set<Store>) {
        scope.launch {
            (load { accountShops.choose(chosen) } as? Load.Failed)?.let { snackbar.showSnackbar(failureSentence(it, Msg.SHOPS_SAVE_FAILED)) }
        }
    }

    /** Tries the server again: the list, and the stores in case another device changed them. */
    fun retry() {
        session.retry()
        reload++
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    PeriodStepper(
                        label = t(Msg.WEEK_OF, dayMonthLabel(weekStart, t.locale)),
                        previous = t(Msg.PREVIOUS_WEEK),
                        next = t(Msg.NEXT_WEEK),
                        onPrevious = { session.open(plusDays(weekStart, -7)) },
                        onNext = { session.open(plusDays(weekStart, 7)) },
                    )
                },
                actions = {
                    BarAction(AppIcons.Offer, t(Msg.OFFERS), onOpenOffers)
                    BarAction(AppIcons.Pantry, t(Msg.PANTRY), onOpenPantry)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val current = state) {
            Load.Loading -> LoadingState(Modifier.padding(padding))
            is Load.Failed -> Column(Modifier.padding(padding)) {
                ErrorMessage(t(Msg.SHOPPING_LOAD_FAILED), current) { retry() }
            }
            is Load.Ready -> {
                val list = current.value
                @Composable
                fun row(item: ShoppingItem, offer: Offer? = item.chipOffer(shops, today), suggested: Boolean = false) = ShoppingRow(
                    item,
                    offer,
                    shops,
                    today,
                    shoppingMode,
                    suggested,
                    onToggle = {
                        // A row that needs more than was bought is ticked again for the new amount, not undone.
                        if (item.moreNeeded() != null) session.tickAgain(item.key)
                        else if (shoppingMode) tickWithUndo(item) else tick(item.key, !item.checked)
                    },
                    onRemove = item.extraId?.let { id -> { session.remove(id) } },
                    onOfferClick = {
                        scope.launch {
                            snackbar.currentSnackbarData?.dismiss()
                            snackbar.showSnackbar(item.dealText(shops, today, t))
                        }
                    },
                )
                if (list.items.isEmpty()) {
                    Column(Modifier.padding(padding)) {
                        AddItemRow(session)
                        EmptyState(Spot.NothingToBuy, nothingToBuyText(list, t), Modifier.weight(1f))
                    }
                    return@Scaffold
                }
                LazyColumn(Modifier.padding(padding).fillMaxSize(), listState, contentPadding = PaddingValues(bottom = Spacing.ScreenEdge)) {
                    item {
                        ShoppingModeSwitch(shoppingMode) {
                            shoppingMode = it
                            settings.saveShoppingMode(it)
                        }
                    }
                    item { ViewSwitch(view) { view = it; settings.saveShoppingView(it) } }
                    item(key = "add") { AddItemRow(session) }
                    offlineSince?.let { since ->
                        item {
                            Row(Modifier.padding(horizontal = Spacing.ScreenEdge), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    // Lists saved by older versions hold the time as text, not as an instant.
                                    t(Msg.OFFLINE_LIST, runCatching { instantLabel(since, t) }.getOrDefault(since)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { retry() }) { Text(t(Msg.RETRY)) }
                            }
                        }
                    }
                    if (session.stuckTicks > 0) {
                        item(key = "stuck-ticks") {
                            Text(
                                t.plural(Plural.TICKS_NOT_SENT, session.stuckTicks),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Spacing.ScreenEdge),
                            )
                        }
                    }
                    item {
                        Text(
                            shoppingProgress(list.items, t),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = Spacing.ScreenEdge, vertical = 8.dp),
                        )
                    }
                    val store = chosenStore?.takeIf { view == ShoppingView.STORE && it in shops }
                    if (list.hasAdvice(store, shops, today)) item(key = "this-week") { PartTitle(t(Msg.FOR_THIS_WEEK)) }
                    when {
                        view == ShoppingView.SECTION -> sectionItems(shoppingSections(list.items, shoppingMode)) { row(it) }
                        store == null -> {
                            val overview = storeOverview(list.items, shops, today)
                            item { ShopsInUse(shops, ::chooseShops) }
                            overview.stores.forEach { group ->
                                item(key = "s:${group.store.name}") {
                                    val browser = runsInBrowser()
                                    val hint = group.saving?.let { t(if (browser) Msg.STORE_SAVING_HINT_BROWSER else Msg.STORE_SAVING_HINT, formatEuro(it, t.locale)) }
                                        ?: t(if (browser) Msg.TAP_TO_SHOP_BROWSER else Msg.TAP_TO_SHOP)
                                    GroupHeader(group.title(t), hint, onClick = { chooseStore(group.store) })
                                }
                                groupItems(group.store.name, group.items) { row(it, it.chipOffer(shops, today)) }
                            }
                            if (overview.anyStore.isNotEmpty()) {
                                item(key = "s:any") {
                                    GroupHeader(
                                        t.plural(Plural.ANY_STORE_ITEMS, overview.anyStore.size),
                                        t(Msg.FREE_TO_BUY),
                                        onClick = null,
                                    )
                                }
                                groupItems("any", overview.anyStore) { row(it, it.chipOffer(shops, today)) }
                            }
                            // Last, under a count of their own: the headers above count what the progress line counts.
                            if (overview.covered.isNotEmpty()) {
                                item(key = "s:covered") {
                                    GroupHeader(t.plural(Plural.NOTHING_LEFT_ITEMS, overview.covered.size), t(Msg.NOTHING_LEFT_HINT), onClick = null)
                                }
                                groupItems("covered", overview.covered) { row(it, it.chipOffer(shops, today)) }
                            }
                        }
                        else -> {
                            val visit = storeVisit(list.items, store, shops, today, shoppingMode)
                            item(key = "visit") { ShopIAmIn(store, visit.saving) { chooseStore(null) } }
                            sectionItems(visit.sections) { row(it, it.chipOffer(shops, today)) }
                            if (visit.elsewhere.isNotEmpty()) {
                                val open = "elsewhere" in expanded
                                item(key = "elsewhere") {
                                    ToggleHeader(t(Msg.CHEAPER_ELSEWHERE, visit.elsewhere.size), open) { session.toggle("elsewhere") }
                                }
                                if (open) items(visit.elsewhere, key = { it.item.key }) { row(it.item, it.offer) }
                            }
                        }
                    }
                    if (list.toTaste.isNotEmpty()) {
                        item {
                            Text(
                                t(Msg.TO_TASTE, list.toTaste.joinToString()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                            )
                        }
                    }
                    if (list.staples.isNotEmpty()) {
                        item {
                            Text(
                                t(Msg.STAPLES_NOTE, list.staples.joinToString()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(Spacing.InsideCard),
                            )
                        }
                    }
                    if (list.noLongerNeeded.isNotEmpty()) {
                        item(key = "no-longer-needed") { NoLongerNeededHeader() }
                        items(list.noLongerNeeded, key = { "n:${it.key}" }) { item ->
                            NoLongerNeededRow(item, onUseUp = { session.useUp(item.key) }, onRemove = { tick(item.key, false) })
                        }
                    }
                    AdviceTexts.entries.forEach { texts ->
                        adviceGroup(texts, list, store, byStore = view == ShoppingView.STORE && store == null) { item ->
                            row(item, suggested = texts == AdviceTexts.BUY_AHEAD && list.buyAheadProvisional)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Why [list], with no rows, has nothing to buy: no meals planned, or only meals of a prepped batch made in an earlier week,
 * which that week's list bought ([ShoppingList.preppedBoughtIn], issue 263), while the Plan shows them in this week.
 */
fun nothingToBuyText(list: ShoppingList, t: Strings): String =
    list.preppedBoughtIn?.let { t(Msg.NOTHING_TO_BUY_PREPPED, dayMonthLabel(it, t.locale)) } ?: t(Msg.NOTHING_TO_BUY)
