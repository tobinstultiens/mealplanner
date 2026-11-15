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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.Store
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

/** `3 × · 330 g`, `450 g`, `2 ×`, or empty when the recipes give no quantity. */
fun ShoppingItem.quantityText(locale: FormatLocale): String = listOfNotNull(
    // Pieces are rounded up: nobody buys 1.5 onions.
    pieces?.let { "${ceil(it - 1e-9).toInt()} ×" },
    grams?.let { if (it >= 1000) "${formatAmount(it / 1000, locale)} kg" else "${it.roundToInt()} g" },
).joinToString(" · ")

/**
 * What to buy for a week's plan, grouped by shop section in the order you walk the shop, with
 * items to tick off. The server sends the items in that order, so grouping keeps it. A second
 * view groups by the store to buy at, and one store can be chosen as the shop you are in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingScreen(api: ApiClient, cache: ShoppingCache, settings: SettingsStorage, onOpenPantry: () -> Unit, onOpenOffers: () -> Unit) {
    val t = LocalStrings.current
    var weekStart by rememberSaveable { mutableStateOf(mondayOf(todayIso())) }
    var state by remember { mutableStateOf<Load<ShoppingList>>(Load.Loading) }
    // Set when showing the stored copy because the server could not be reached.
    var offlineSince by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var shoppingMode by remember { mutableStateOf(settings.shoppingMode()) }
    var view by remember { mutableStateOf(settings.shoppingView()) }
    var chosenStore by remember { mutableStateOf(settings.shoppingStore()) }
    var shops by remember { mutableStateOf(settings.shops()) }
    val listState = rememberLazyListState()
    // Finished sections and Done groups the user opened; everything else stays collapsed.
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    KeepScreenOn(shoppingMode)

    /** Sets one item's tick: shown at once, then confirmed or corrected by the server's answer. */
    fun tick(key: String, checked: Boolean) {
        val list = (state as? Load.Ready)?.value ?: return
        state = Load.Ready(list.copy(items = list.items.map { if (it.key == key) it.copy(checked = checked) else it }))
        if (offlineSince != null) {
            cache.tickOffline(weekStart, key, checked)
            return
        }
        scope.launch {
            when (val result = load { api.setShoppingChecked(weekStart, key, checked) }) {
                is Load.Ready -> {
                    state = result
                    cache.save(result.value, nowIso())
                }
                // No connection: keep the tick and send it later.
                is Load.Failed -> if (result.status == null) {
                    cache.save((state as? Load.Ready)?.value ?: list, offlineSince ?: nowIso())
                    cache.tickOffline(weekStart, key, checked)
                    offlineSince = nowIso()
                } else {
                    state = Load.Ready(list)
                    snackbar.showSnackbar(t(Msg.TICK_FAILED, result.message))
                }
                Load.Loading -> Unit
            }
        }
    }

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
            item(key = "d:$id") { DoneGroupHeader(done.size, doneOpen) { expanded = expanded.toggled("done:$id") } }
            if (doneOpen) items(done, key = { it.key }) { row(it) }
        }
    }

    /** The shop sections in walking order, each with its icon and, in shopping mode, its Done group. */
    fun LazyListScope.sectionItems(sections: List<ShoppingSectionView>, row: @Composable (ShoppingItem) -> Unit) {
        sections.forEach { section ->
            val title = section.title
            val sectionOpen = !section.finished || title in expanded
            item(key = "h:$title") {
                SectionHeaderRow(title, section, sectionOpen) { expanded = expanded.toggled(title) }
            }
            items(section.open, key = { it.key }) { item -> row(item) }
            if (section.finished && sectionOpen) {
                items(section.done, key = { it.key }) { item -> row(item) }
            } else if (section.done.isNotEmpty() && !section.finished) {
                val doneOpen = "done:$title" in expanded
                item(key = "d:$title") {
                    DoneGroupHeader(section.done.size, doneOpen) { expanded = expanded.toggled("done:$title") }
                }
                if (doneOpen) items(section.done, key = { it.key }) { item -> row(item) }
            }
        }
    }

    LaunchedEffect(weekStart, reload) {
        if (state !is Load.Ready) state = Load.Loading
        // Ticks made offline go to the server first, so the fresh list includes them.
        val result = load {
            cache.pending(weekStart).forEach { (key, checked) -> api.setShoppingChecked(weekStart, key, checked) }
            cache.clearPending(weekStart)
            api.shoppingList(weekStart)
        }
        val stored = cache.load(weekStart)
        if (result is Load.Failed && result.status == null && stored != null) {
            state = Load.Ready(stored.first)
            offlineSince = stored.second
        } else {
            state = result
            offlineSince = null
            (result as? Load.Ready)?.let { cache.save(it.value, nowIso()) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    PeriodStepper(
                        label = t(Msg.WEEK_OF, dayMonthLabel(weekStart, t.locale)),
                        previous = t(Msg.PREVIOUS_WEEK),
                        next = t(Msg.NEXT_WEEK),
                        onPrevious = { weekStart = plusDays(weekStart, -7) },
                        onNext = { weekStart = plusDays(weekStart, 7) },
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
                ErrorMessage(t(Msg.SHOPPING_LOAD_FAILED), current.message) { reload++ }
            }
            is Load.Ready -> {
                val list = current.value
                @Composable
                fun row(item: ShoppingItem, offer: Offer? = item.bestOffer()) = ShoppingRow(
                    item,
                    offer,
                    shoppingMode,
                    onToggle = { if (shoppingMode) tickWithUndo(item) else tick(item.key, !item.checked) },
                    onOfferClick = {
                        scope.launch {
                            snackbar.currentSnackbarData?.dismiss()
                            snackbar.showSnackbar(item.offers.distinctBy { it.store to it.deal }.joinToString("\n") { "${it.shortText(t)} (${it.title})" })
                        }
                    },
                )
                if (list.items.isEmpty()) {
                    EmptyState(Spot.NothingToBuy, t(Msg.NOTHING_TO_BUY), Modifier.padding(padding))
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
                                TextButton(onClick = { reload++ }) { Text(t(Msg.RETRY)) }
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
                    when {
                        view == ShoppingView.SECTION -> sectionItems(shoppingSections(list.items, shoppingMode)) { row(it) }
                        store == null -> {
                            val overview = storeOverview(list.items, shops)
                            item { ShopsInUse(shops) { shops = it; settings.saveShops(it) } }
                            overview.stores.forEach { group ->
                                item(key = "s:${group.store.name}") {
                                    GroupHeader(group.title(t), t(Msg.TAP_TO_SHOP), onClick = { chooseStore(group.store) })
                                }
                                groupItems(group.store.name, group.items) { row(it, it.bestOffer(shops)) }
                            }
                            if (overview.anyStore.isNotEmpty()) {
                                item(key = "s:any") {
                                    GroupHeader(
                                        t.plural(Plural.ANY_STORE_ITEMS, overview.anyStore.size),
                                        t(Msg.FREE_TO_BUY),
                                        onClick = null,
                                    )
                                }
                                groupItems("any", overview.anyStore) { row(it, it.bestOffer(shops)) }
                            }
                        }
                        else -> {
                            val visit = storeVisit(list.items, store, shops, shoppingMode)
                            item(key = "visit") { ShopIAmIn(store) { chooseStore(null) } }
                            sectionItems(visit.sections) { row(it, it.bestOffer(shops)) }
                            if (visit.elsewhere.isNotEmpty()) {
                                val open = "elsewhere" in expanded
                                item(key = "elsewhere") {
                                    ToggleHeader(t(Msg.CHEAPER_ELSEWHERE, visit.elsewhere.size), open) { expanded = expanded.toggled("elsewhere") }
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
                }
            }
        }
    }
}

private fun Set<String>.toggled(entry: String) = if (entry in this) this - entry else this + entry

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

/** The stores this device shops at; the last one stays on, as the list needs at least one. */
@Composable
private fun ShopsInUse(shops: Set<Store>, onChange: (Set<Store>) -> Unit) {
    val t = LocalStrings.current
    Column(Modifier.padding(horizontal = Spacing.ScreenEdge, vertical = Spacing.Tight)) {
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

/** Which shop the list is for, with the way back to all stores. */
@Composable
private fun ShopIAmIn(store: Store, onAllStores: () -> Unit) {
    val t = LocalStrings.current
    Row(Modifier.fillMaxWidth().padding(start = Spacing.ScreenEdge, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t(Msg.SHOPPING_AT, store.shortName()), style = MaterialTheme.typography.titleSmall)
            Text(
                t(Msg.ITS_OFFERS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
private fun ShoppingRow(item: ShoppingItem, offer: Offer?, shoppingMode: Boolean, onToggle: () -> Unit, onOfferClick: () -> Unit) {
    val t = LocalStrings.current
    Row(
        Modifier.fillMaxWidth()
            .toggleable(item.checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = if (shoppingMode) 64.dp else 56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = item.checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        ProductPicture(item.imageUrl, item.name, size = if (shoppingMode) 56.dp else 48.dp, dimmed = item.checked)
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 6.dp, bottom = 6.dp)) {
            Text(
                if (item.onHand) t(Msg.ON_HAND_MAYBE, item.name) else item.name,
                style = if (shoppingMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.recipes.joinToString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(Modifier.padding(horizontal = 12.dp), horizontalAlignment = Alignment.End) {
            Text(item.quantityText(t.locale), style = MaterialTheme.typography.bodyMedium.numeric(), softWrap = false)
            if (offer != null) {
                // A tap shows the full deal; the rest of the row keeps ticking. The badge looks small
                // but its touch area is 48 dp, as Material asks.
                Box(
                    Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable(role = Role.Button, onClickLabel = t(Msg.SHOW_DEAL), onClick = onOfferClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        offer.badgeText(t),
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
