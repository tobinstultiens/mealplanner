package dev.tobins.mealplanner.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
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
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

/** `3 × · 330 g`, `450 g`, `2 ×`, or empty when the recipes give no quantity. */
fun ShoppingItem.quantityText(locale: FormatLocale = FormatLocale.device): String = listOfNotNull(
    // Pieces are rounded up: nobody buys 1.5 onions.
    pieces?.let { "${ceil(it - 1e-9).toInt()} ×" },
    grams?.let { if (it >= 1000) "${formatAmount(it / 1000, locale)} kg" else "${it.roundToInt()} g" },
).joinToString(" · ")

/**
 * What to buy for a week's plan, grouped by shop section in the order you walk the shop, with
 * items to tick off. The server sends the items in that order, so grouping keeps it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingScreen(api: ApiClient, cache: ShoppingCache, settings: SettingsStorage, onOpenPantry: () -> Unit, onOpenOffers: () -> Unit) {
    var weekStart by rememberSaveable { mutableStateOf(mondayOf(todayIso())) }
    var state by remember { mutableStateOf<Load<ShoppingList>>(Load.Loading) }
    // Set when showing the stored copy because the server could not be reached.
    var offlineSince by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var shoppingMode by remember { mutableStateOf(settings.shoppingMode()) }
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
                    snackbar.showSnackbar("Could not save the tick: ${result.message}")
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
                "${item.name} ${if (item.checked) "back on the list" else "ticked off"}",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (answer == SnackbarResult.ActionPerformed) tick(item.key, item.checked)
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
                        label = "Week of ${dayMonthLabel(weekStart)}",
                        previous = "Previous week",
                        next = "Next week",
                        onPrevious = { weekStart = plusDays(weekStart, -7) },
                        onNext = { weekStart = plusDays(weekStart, 7) },
                    )
                },
                actions = {
                    BarAction(AppIcons.Offer, "Offers", onOpenOffers)
                    BarAction(AppIcons.Pantry, "Pantry", onOpenPantry)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val current = state) {
            Load.Loading -> LoadingState(Modifier.padding(padding))
            is Load.Failed -> Column(Modifier.padding(padding)) {
                ErrorMessage("Could not load the shopping list", current.message) { reload++ }
            }
            is Load.Ready -> {
                val list = current.value
                @Composable
                fun row(item: ShoppingItem) = ShoppingRow(
                    item,
                    shoppingMode,
                    onToggle = { if (shoppingMode) tickWithUndo(item) else tick(item.key, !item.checked) },
                    onOfferClick = {
                        scope.launch {
                            snackbar.currentSnackbarData?.dismiss()
                            snackbar.showSnackbar(item.offers.distinctBy { it.store to it.deal }.joinToString("\n") { "${it.shortText()} (${it.title})" })
                        }
                    },
                )
                if (list.items.isEmpty()) {
                    EmptyState(Spot.NothingToBuy, "Nothing to buy: no meals are planned for this week.", Modifier.padding(padding))
                    return@Scaffold
                }
                LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = Spacing.ScreenEdge)) {
                    item {
                        ShoppingModeSwitch(shoppingMode) {
                            shoppingMode = it
                            settings.saveShoppingMode(it)
                        }
                    }
                    offlineSince?.let { since ->
                        item {
                            Row(Modifier.padding(horizontal = Spacing.ScreenEdge), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    // Lists saved by older versions hold the time as text, not as an instant.
                                    "Offline: the list as of ${runCatching { instantLabel(since) }.getOrDefault(since)}. Ticks are saved and sent later.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { reload++ }) { Text("Retry") }
                            }
                        }
                    }
                    item {
                        Text(
                            shoppingProgress(list.items),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = Spacing.ScreenEdge, vertical = 8.dp),
                        )
                    }
                    shoppingSections(list.items, shoppingMode).forEach { section ->
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
                    if (list.toTaste.isNotEmpty()) {
                        item {
                            Text(
                                "To taste (check you have them): ${list.toTaste.joinToString()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                            )
                        }
                    }
                    if (list.staples.isNotEmpty()) {
                        item {
                            Text(
                                "Staples this plan uses (not on the list): ${list.staples.joinToString()}",
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
    Row(
        Modifier.fillMaxWidth().toggleable(on, role = Role.Switch, onValueChange = onChange).heightIn(min = 56.dp).padding(horizontal = Spacing.ScreenEdge),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Shopping mode", style = MaterialTheme.typography.titleSmall)
            Text(
                "Big rows, the screen stays on, ticked items step aside",
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
    // The icon is decoration: the title next to it is what a screen reader reads.
    Row(
        Modifier.fillMaxWidth()
            .then(if (section.finished) Modifier.toggleable(open, role = Role.Button, onValueChange = { onToggle() }).heightIn(min = 56.dp) else Modifier)
            .padding(start = Spacing.ScreenEdge, end = Spacing.ScreenEdge, top = Spacing.InsideCard, bottom = Spacing.Tight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(sectionIcon(title), contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        SectionHeader(title, Modifier.weight(1f, fill = false))
        if (section.finished) {
            Text(
                "✓ ${section.done.size} ticked",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The collapsed "Done (n)" line at the end of a section. */
@Composable
private fun DoneGroupHeader(count: Int, open: Boolean, onToggle: () -> Unit) {
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
        Text("Done ($count)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ShoppingRow(item: ShoppingItem, shoppingMode: Boolean, onToggle: () -> Unit, onOfferClick: () -> Unit) {
    val offer = item.bestOffer()
    Row(
        Modifier.fillMaxWidth()
            .toggleable(item.checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = if (shoppingMode) 64.dp else 56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = item.checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                item.name + if (item.onHand) "  (on hand?)" else "",
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
            Text(item.quantityText(), style = MaterialTheme.typography.bodyMedium.numeric(), softWrap = false)
            if (offer != null) {
                // A tap shows the full deal; the rest of the row keeps ticking.
                Text(
                    offer.badgeText(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    softWrap = false,
                    modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable(role = Role.Button, onClickLabel = "Show the deal", onClick = onOfferClick)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}
