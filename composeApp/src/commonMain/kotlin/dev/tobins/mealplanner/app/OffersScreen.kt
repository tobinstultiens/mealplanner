package dev.tobins.mealplanner.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.OfferSourceStatus
import dev.tobins.mealplanner.api.OffersState
import dev.tobins.mealplanner.api.Store
import kotlinx.coroutines.launch

/**
 * This week's store offers. By default only those for ingredients in the catalogue,
 * which are the ones that can end up in a plan.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OffersScreen(api: ApiClient, onBack: () -> Unit, onOpenPrices: () -> Unit) {
    val t = LocalStrings.current
    val today = remember { todayIso() }
    var state by remember { mutableStateOf<Load<OffersState>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var onlyMatched by rememberSaveable { mutableStateOf(true) }
    var store by rememberSaveable { mutableStateOf<Store?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        if (state !is Load.Ready) state = Load.Loading
        state = load { api.offers(today) }
    }

    SubScreen(t(Msg.OFFERS), onBack, snackbar, actions = {
        BarAction(AppIcons.Refresh, if (refreshing) t(Msg.FETCHING_OFFERS) else t(Msg.REFRESH_OFFERS), busy = refreshing, onClick = {
            refreshing = true
            scope.launch {
                (load { api.refreshOffers() } as? Load.Failed)?.let { snackbar.showSnackbar(t(Msg.REFRESH_FAILED, it.message)) }
                refreshing = false
                reload++
            }
        })
        OverflowMenu(MenuAction(AppIcons.Price, t(Msg.REGULAR_PRICES), onOpenPrices))
    }) { padding ->
        when (val current = state) {
            Load.Loading -> LoadingState(Modifier.padding(padding))
            is Load.Failed -> Column(Modifier.padding(padding)) {
                ErrorMessage(t(Msg.OFFERS_LOAD_FAILED), current.message) { reload++ }
            }
            is Load.Ready -> {
                val all = current.value.offers
                val shown = all
                    .filter { !onlyMatched || it.ingredientName != null }
                    .filter { store == null || it.store == store }
                    .sortedWith(compareBy({ it.ingredientName == null }, { it.ingredientName.orEmpty() }, { it.store }))
                LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = Spacing.ScreenEdge)) {
                    item {
                        Column(Modifier.padding(horizontal = Spacing.ScreenEdge), verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                            current.value.sources.forEach { SourceLine(it) }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                                FilterChip(
                                    selected = onlyMatched,
                                    onClick = { onlyMatched = !onlyMatched },
                                    label = { Text(t(Msg.MY_INGREDIENTS, all.count { it.ingredientName != null })) },
                                )
                                Store.entries.forEach { s ->
                                    FilterChip(
                                        selected = store == s,
                                        onClick = { store = if (store == s) null else s },
                                        label = { Text(if (s == Store.AH) "AH" else s.label) },
                                    )
                                }
                            }
                        }
                    }
                    if (shown.isEmpty()) {
                        item {
                            if (all.isEmpty()) EmptyState(Spot.NoOffers, t(Msg.NO_OFFERS_YET))
                            else EmptyState(AppIcons.Offer, t(Msg.NO_OFFERS_MATCH))
                        }
                    }
                    items(shown, key = { "${it.store}|${it.url ?: it.title}|${it.validFrom}" }) { offer ->
                        OfferRow(offer)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceLine(status: OfferSourceStatus) {
    val t = LocalStrings.current
    val fetched = status.fetchedAt?.let { dayMonthLabel(it.take(10), t.locale) }
    val error = status.error
    when {
        error != null -> ErrorBanner(
            if (fetched == null) t(Msg.SOURCE_COULD_NOT_FETCH, status.store.label) else t(Msg.SOURCE_LAST_FAILED, status.store.label, fetched),
            error,
        )
        fetched != null -> Text(
            t.plural(Plural.SOURCE_FETCHED, status.offerCount, status.store.label, fetched),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Text(
            t(Msg.SOURCE_NOT_FETCHED, status.store.label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OfferRow(offer: Offer) {
    val t = LocalStrings.current
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.ScreenEdge, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ProductPicture(offer.imageUrl, offer.title)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            offer.ingredientName?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(offer.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(offer.shortText(t), offer.packSize, t(Msg.UNTIL, dayMonthLabel(offer.validUntil, t.locale))).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
