package dev.tobins.mealplanner.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import dev.tobins.mealplanner.api.ShoppingExtraInput
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingAdviceGroup
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.pricing.PackSize
import dev.tobins.mealplanner.pricing.PacksToBuy
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

/** `450 g`, `1.4 kg`: a weight the way the list writes it. */
fun weightText(grams: Double, locale: FormatLocale): String =
    if (grams >= 1000) "${formatAmount(grams / 1000, locale)} kg" else "${grams.roundToInt()} g"

/**
 * What to pick up for this item when bought as [offer]: [PackSize.toBuy] for the weight the recipes
 * need, or their pieces. What could not be weighed is not guessed at, so an item with other amounts
 * (or without a weight) gets nothing.
 */
fun ShoppingItem.packsToBuy(offer: Offer): PacksToBuy? = PackSize.toBuy(offer, grams, if (otherAmounts.isEmpty()) pieces else null)

/** `2 × 700 g`, `1 × 500 g · deal needs 2`, `2 × 3 pieces`: the packs to take and, when the deal wants more than the amount needs, how many it wants. */
fun PacksToBuy.text(strings: Strings): String {
    val size = packGrams?.let { weightText(it, strings.locale) } ?: strings.plural(Plural.PACK_PIECES, packPieces ?: 1)
    return if (dealFits) strings(Msg.PACKS_TO_TAKE, packs, size) else strings(Msg.PACKS_DEAL_NEEDS, packs, size, dealPacks)
}

/**
 * `3 × · 330 g`, `450 g`, `2 ×`, `200 g + 1 bag`, `250 ml`, or empty when the recipes give no
 * quantity. What could not be weighed ([ShoppingItem.otherAmounts]) follows the known weight; the
 * piece count is left out then, as it would count those amounts a second time.
 */
fun ShoppingItem.quantityText(locale: FormatLocale): String {
    val weight = (grams ?: knownGrams)?.let { weightText(it, locale) }
    if (otherAmounts.isNotEmpty()) return (listOfNotNull(weight) + otherAmounts).joinToString(" + ")
    // Pieces are rounded up: nobody buys 1.5 onions.
    return listOfNotNull(pieces?.let { "${ceil(it - 1e-9).toInt()} ×" }, weight).joinToString(" · ")
}

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

    // What the session has to tell, also from while the screen was not shown.
    val notice = session.notices.firstOrNull()
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        snackbar.showSnackbar(t(notice.msg, notice.detail))
        session.shown(notice)
    }

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
        val (done, open) = if (shoppingMode) items.partition { it.checked } else emptyList<ShoppingItem>() to items
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
            (load { accountShops.choose(chosen) } as? Load.Failed)?.let { snackbar.showSnackbar(t(Msg.SHOPS_SAVE_FAILED, it.message)) }
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
                ErrorMessage(t(Msg.SHOPPING_LOAD_FAILED), current.message) { retry() }
            }
            is Load.Ready -> {
                val list = current.value
                @Composable
                fun row(item: ShoppingItem, offer: Offer? = item.bestOffer(shops, today), suggested: Boolean = false) = ShoppingRow(
                    item,
                    offer,
                    shops,
                    today,
                    shoppingMode,
                    suggested,
                    onToggle = { if (shoppingMode) tickWithUndo(item) else tick(item.key, !item.checked) },
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
                        EmptyState(Spot.NothingToBuy, t(Msg.NOTHING_TO_BUY), Modifier.weight(1f))
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
                                    val hint = group.saving?.let { t(Msg.STORE_SAVING_HINT, formatEuro(it, t.locale)) } ?: t(Msg.TAP_TO_SHOP)
                                    GroupHeader(group.title(t), hint, onClick = { chooseStore(group.store) })
                                }
                                groupItems(group.store.name, group.items) { row(it, it.bestOffer(shops, today)) }
                            }
                            if (overview.anyStore.isNotEmpty()) {
                                item(key = "s:any") {
                                    GroupHeader(
                                        t.plural(Plural.ANY_STORE_ITEMS, overview.anyStore.size),
                                        t(Msg.FREE_TO_BUY),
                                        onClick = null,
                                    )
                                }
                                groupItems("any", overview.anyStore) { row(it, it.bestOffer(shops, today)) }
                            }
                        }
                        else -> {
                            val visit = storeVisit(list.items, store, shops, today, shoppingMode)
                            item(key = "visit") { ShopIAmIn(store, visit.saving) { chooseStore(null) } }
                            sectionItems(visit.sections) { row(it, it.bestOffer(shops, today)) }
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
 * The field and button that put an own item on the list. Enter or the button adds it; the field
 * then clears and keeps the focus for the next one. A refusal leaves the text for the user to
 * change. Without a connection the field is off and says why.
 */
@Composable
private fun AddItemRow(session: ShoppingSession) {
    val t = LocalStrings.current
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val enabled = session.canChangeExtras
    fun add() {
        val sent = text
        if (sent.isBlank()) return
        // Only what was sent is cleared: the user may have started the next item meanwhile.
        session.add(sent) {
            if (text == sent) text = ""
            focus.requestFocus()
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.ScreenEdge, vertical = 4.dp), verticalAlignment = Alignment.Top) {
        OutlinedTextField(
            text,
            { text = it.take(ShoppingExtraInput.MAX_NAME_LENGTH) },
            label = { Text(t(Msg.EXTRA_FIELD)) },
            supportingText = if (enabled) null else ({ Text(t(Msg.EXTRA_NEEDS_CONNECTION, "")) }),
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        // Level with the field, not with its supporting text.
        IconButton(onClick = ::add, enabled = enabled && text.isNotBlank() && !session.adding, modifier = Modifier.padding(top = 4.dp)) {
            Icon(AppIcons.Add, t(Msg.EXTRA_ADD))
        }
    }
}

/** The title of one part of the list: this week's, or an advice group's. */
@Composable
private fun PartTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(start = Spacing.ScreenEdge, end = Spacing.ScreenEdge, top = Spacing.InsideCard),
    )
}

/** An advice group's title, what it is for and saves, and the button that hides it for the week. */
@Composable
private fun AdviceHeader(title: String, hint: String, onHide: () -> Unit) {
    val t = LocalStrings.current
    Column(Modifier.fillMaxWidth().padding(top = Spacing.InsideCard)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PartTitle(title, Modifier.weight(1f))
            TextButton(onClick = onHide, modifier = Modifier.padding(end = 4.dp, top = Spacing.Tight)) { Text(t(Msg.HIDE_ADVICE)) }
        }
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.ScreenEdge),
        )
    }
}

/** In place of a hidden advice group: that it is hidden, and the button that shows it again. */
@Composable
private fun HiddenAdvice(text: String, onShow: () -> Unit) {
    val t = LocalStrings.current
    Row(Modifier.fillMaxWidth().padding(start = Spacing.ScreenEdge, end = 4.dp, top = Spacing.Tight), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = onShow) { Text(t(Msg.SHOW_ADVICE)) }
    }
}

@Composable
private fun ShoppingModeSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val t = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().toggleable(on, role = Role.Switch, onValueChange = onChange).heightIn(min = 56.dp).padding(horizontal = Spacing.ScreenEdge),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(t(Msg.SHOPPING_MODE), style = MaterialTheme.typography.titleSmall)
            Text(
                t(Msg.SHOPPING_MODE_HINT),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = on, onCheckedChange = null)
    }
}

/** The section's title with its icon; a finished section shows a tick and opens or closes on tap. */
@Composable
private fun SectionHeaderRow(title: String, section: ShoppingSectionView, open: Boolean, onToggle: () -> Unit) {
    val t = LocalStrings.current
    // The icon is decoration: the title next to it is what a screen reader reads.
    Row(
        Modifier.fillMaxWidth()
            .then(if (section.finished) Modifier.toggleable(open, role = Role.Button, onValueChange = { onToggle() }).heightIn(min = 56.dp) else Modifier)
            .padding(start = Spacing.ScreenEdge, end = Spacing.ScreenEdge, top = Spacing.InsideCard, bottom = Spacing.Tight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(sectionIcon(title), contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        SectionHeader(t.sectionTitle(title), Modifier.weight(1f, fill = false))
        if (section.finished) {
            Text(
                t(Msg.TICKED_COUNT, section.done.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The collapsed "Done (n)" line at the end of a section. */
@Composable
private fun DoneGroupHeader(count: Int, open: Boolean, onToggle: () -> Unit) {
    val t = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().toggleable(open, role = Role.Button, onValueChange = { onToggle() }).heightIn(min = 56.dp).padding(horizontal = Spacing.ScreenEdge),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            Modifier.size(20.dp).rotate(if (open) 90f else 0f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(t(Msg.DONE_COUNT, count), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "By section" or "Per store": how the list is grouped. */
@Composable
private fun ViewSwitch(view: ShoppingView, onChange: (ShoppingView) -> Unit) {
    val t = LocalStrings.current
    Row(Modifier.padding(horizontal = Spacing.ScreenEdge), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShoppingView.entries.forEach { option ->
            FilterChip(selected = view == option, onClick = { onChange(option) }, label = { Text(t(option.msg())) })
        }
    }
}

/**
 * The stores the account shops at, which the planner uses too; the last one stays on, as the list
 * needs at least one. The note under them says so, and what the savings are measured against.
 */
@Composable
fun ShopsInUse(
    shops: Set<Store>,
    onChange: (Set<Store>) -> Unit,
    modifier: Modifier = Modifier.padding(horizontal = Spacing.ScreenEdge, vertical = Spacing.Tight),
) {
    val t = LocalStrings.current
    Column(modifier) {
        Text(t(Msg.STORES_I_SHOP_AT), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Store.entries.forEach { store ->
                FilterChip(
                    selected = store in shops,
                    onClick = { if (store !in shops) onChange(shops + store) else if (shops.size > 1) onChange(shops - store) },
                    label = { Text(store.shortName()) },
                )
            }
        }
        Text(t(Msg.SHOPS_NOTE), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A group's title with a hint; with an [onClick] it is a button that chooses the store. */
@Composable
private fun GroupHeader(title: String, hint: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = Spacing.ScreenEdge, vertical = Spacing.Tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            SectionHeader(title)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onClick != null) Icon(AppIcons.ChevronRight, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Which shop the list is for, what its offers save there, and the way back to all stores. */
@Composable
private fun ShopIAmIn(store: Store, saving: Double?, onAllStores: () -> Unit) {
    val t = LocalStrings.current
    Row(Modifier.fillMaxWidth().padding(start = Spacing.ScreenEdge, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t(Msg.SHOPPING_AT, store.shortName()), style = MaterialTheme.typography.titleSmall)
            Text(
                t(Msg.ITS_OFFERS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (saving != null) {
                Text(
                    t(Msg.VISIT_SAVING, formatEuro(saving, t.locale)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onAllStores) { Text(t(Msg.ALL_STORES)) }
    }
}

/** A collapsed group's title line with a chevron; tapping opens or closes it. */
@Composable
private fun ToggleHeader(title: String, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(open, role = Role.Button, onValueChange = { onToggle() }).heightIn(min = 56.dp).padding(horizontal = Spacing.ScreenEdge),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(AppIcons.ChevronRight, contentDescription = null, Modifier.size(20.dp).rotate(if (open) 90f else 0f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ShoppingRow(item: ShoppingItem, offer: Offer?, shops: Set<Store>, today: String, shoppingMode: Boolean, suggested: Boolean, onToggle: () -> Unit, onRemove: (() -> Unit)?, onOfferClick: () -> Unit) {
    val t = LocalStrings.current
    Row(
        Modifier.fillMaxWidth()
            .toggleable(item.checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = if (shoppingMode) 64.dp else 56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = item.checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        ProductPicture(item.pictureUrl(offer, shops), item.name, size = if (shoppingMode) 56.dp else 48.dp, dimmed = item.checked)
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 6.dp, bottom = 6.dp)) {
            Text(
                if (item.onHand) t(Msg.ON_HAND_MAYBE, item.name) else item.name,
                style = if (shoppingMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Stock-up advice is for no meal in particular; a provisional buy-ahead meal is only an example.
            if (item.recipes.isNotEmpty()) {
                Text(
                    item.recipes.joinToString().let { if (suggested) t(Msg.SUGGESTED_MEALS, it) else it },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // An own item has no amount and no offer; in shopping mode its row has the one tap target.
        if (onRemove != null) {
            if (!shoppingMode) {
                IconButton(onClick = onRemove) { Icon(AppIcons.Close, t(Msg.EXTRA_REMOVE, item.name)) }
            }
            return@Row
        }
        Column(Modifier.padding(horizontal = 12.dp).widthIn(max = 128.dp), horizontalAlignment = Alignment.End) {
            // Long amounts such as "200 g + 1 bag" wrap rather than squeeze the name.
            Text(item.quantityText(t.locale), style = MaterialTheme.typography.bodyMedium.numeric(), textAlign = TextAlign.End)
            if (offer != null) {
                item.packsToBuy(offer)?.let {
                    Text(
                        it.text(t),
                        style = MaterialTheme.typography.bodySmall.numeric(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
                // A tap shows the full deal; the rest of the row keeps ticking. The badge looks small
                // but its touch area is 48 dp, as Material asks.
                Box(
                    Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable(role = Role.Button, onClickLabel = t(Msg.SHOW_DEAL), onClick = onOfferClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        offer.badgeText(t, today),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        softWrap = false,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
