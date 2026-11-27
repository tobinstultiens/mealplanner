package dev.tobins.mealplanner.app.prices

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import dev.tobins.mealplanner.app.ApiClient
import dev.tobins.mealplanner.app.LocalStrings
import dev.tobins.mealplanner.app.RefreshNotice
import dev.tobins.mealplanner.app.ingredients.ProductPicture
import dev.tobins.mealplanner.app.rememberFrontReturns
import dev.tobins.mealplanner.app.rememberLoad
import dev.tobins.mealplanner.app.rememberToday
import dev.tobins.mealplanner.app.ui.AppIcons
import dev.tobins.mealplanner.app.ui.BarAction
import dev.tobins.mealplanner.app.ui.CommonMsg
import dev.tobins.mealplanner.app.ui.EmptyState
import dev.tobins.mealplanner.app.ui.ErrorBanner
import dev.tobins.mealplanner.app.ui.ErrorMessage
import dev.tobins.mealplanner.app.ui.Load
import dev.tobins.mealplanner.app.ui.LoadingState
import dev.tobins.mealplanner.app.ui.MenuAction
import dev.tobins.mealplanner.app.ui.OverflowMenu
import dev.tobins.mealplanner.app.ui.Spacing
import dev.tobins.mealplanner.app.ui.Spot
import dev.tobins.mealplanner.app.ui.SubScreen
import dev.tobins.mealplanner.app.ui.WarningBanner
import dev.tobins.mealplanner.app.ui.dayMonthLabel
import dev.tobins.mealplanner.app.ui.failureSentence
import dev.tobins.mealplanner.app.ui.load
import dev.tobins.mealplanner.app.ui.shortText
import kotlinx.coroutines.launch

/**
 * This week's store offers. By default only those for ingredients in the catalogue,
 * which are the ones that can end up in a plan. An offer the server judged by its price history says
 * so under its deal; a tap on an offer of one of the account's ingredients opens the verdict at length
 * ([VerdictDetail]) and "Not this ingredient" ([exclusions]), whose exclusions are laid over the offers
 * shown. [initiallyOpen] are the titles of offers open from the start, for the screenshots.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun OffersScreen(
    api: ApiClient,
    exclusions: OfferExclusionQueue,
    onBack: () -> Unit,
    onOpenPrices: () -> Unit,
    initiallyOpen: Set<String> = emptySet(),
) {
    val t = LocalStrings.current
    val today = rememberToday()
    val loaded = rememberLoad<OffersState>("offers")
    var state by loaded
    var reload by remember { mutableIntStateOf(0) }
    val frontReturns = rememberFrontReturns()
    val listState = rememberLazyListState()
    var refreshing by remember { mutableStateOf(false) }
    var onlyMatched by rememberSaveable { mutableStateOf(true) }
    var store by rememberSaveable { mutableStateOf<Store?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Fetched again once the server has an exclusion made here: it may match the offer to another ingredient.
    LaunchedEffect(loaded, reload, frontReturns, today, exclusions.known) { loaded.refresh { api.offers(today) } }

    SubScreen(t(CommonMsg.OFFERS), onBack, snackbar, notice = { RefreshNotice(loaded, aboveSystemBar = true) { reload++ } }, actions = {
        BarAction(AppIcons.Refresh, if (refreshing) t(PricesMsg.FETCHING_OFFERS) else t(PricesMsg.REFRESH_OFFERS), busy = refreshing, onClick = {
            refreshing = true
            scope.launch {
                (load { api.refreshOffers() } as? Load.Failed)?.let { snackbar.showSnackbar(failureSentence(it, PricesMsg.REFRESH_FAILED)) }
                refreshing = false
                reload++
            }
        })
        OverflowMenu(MenuAction(AppIcons.Price, t(PricesMsg.REGULAR_PRICES), onOpenPrices))
    }) { padding ->
        when (val current = state) {
            Load.Loading -> LoadingState(Modifier.padding(padding))
            is Load.Failed -> Column(Modifier.padding(padding)) {
                ErrorMessage(t(PricesMsg.OFFERS_LOAD_FAILED), current) { reload++ }
            }
            is Load.Ready -> {
                val all = current.value.withoutExcluded(exclusions.inEffect).offers
                val shown = all
                    .filter { !onlyMatched || it.ingredientName != null }
                    .filter { store == null || it.store == store }
                    .sortedWith(compareBy({ it.ingredientName == null }, { it.ingredientName.orEmpty() }, { it.store }))
                LazyColumn(Modifier.padding(padding).fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = Spacing.ScreenEdge)) {
                    item {
                        Column(Modifier.padding(horizontal = Spacing.ScreenEdge), verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                            current.value.sources.forEach { SourceLine(it) }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                                FilterChip(
                                    selected = onlyMatched,
                                    onClick = { onlyMatched = !onlyMatched },
                                    label = { Text(t(PricesMsg.MY_INGREDIENTS, all.count { it.ingredientName != null })) },
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
                            if (all.isEmpty()) EmptyState(Spot.NoOffers, t.onWeb(PricesMsg.NO_OFFERS_YET, PricesMsg.NO_OFFERS_YET_BROWSER))
                            else EmptyState(AppIcons.Offer, t(PricesMsg.NO_OFFERS_MATCH))
                        }
                    }
                    items(shown, key = { "${it.store}|${it.url ?: it.title}|${it.validFrom}" }) { offer ->
                        OfferRow(offer, initiallyOpen = offer.title in initiallyOpen) { id, name ->
                            exclusions.chooseWithUndo(scope, snackbar, t, offer.store, offer.title, id, name, excluded = true)
                        }
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
    Column {
        when {
            error != null -> ErrorBanner(
                if (fetched == null) t(PricesMsg.SOURCE_COULD_NOT_FETCH, status.store.label) else t(PricesMsg.SOURCE_LAST_FAILED, status.store.label, fetched),
                error,
            )
            fetched != null -> Text(
                t.plural(PricesPlural.SOURCE_FETCHED, status.offerCount, status.store.label, fetched),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Text(
                t(PricesMsg.SOURCE_NOT_FETCHED, status.store.label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        status.warning?.let { WarningBanner(t(PricesMsg.SOURCE_WARNING, status.store.label), it) }
    }
}

/**
 * One offer. One of the account's ingredients opens and closes on a tap: under it its verdict at length, and "Not this
 * ingredient" ([onExclude], given the ingredient's id and name).
 */
@Composable
private fun OfferRow(offer: Offer, initiallyOpen: Boolean, onExclude: (Long, String) -> Unit) {
    val t = LocalStrings.current
    val verdict = offer.verdict
    val ingredientId = offer.ingredientId
    val ingredientName = offer.ingredientName
    val canOpen = verdict != null || ingredientId != null
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    val opens = if (!canOpen) Modifier else Modifier.clickable(onClickLabel = t(if (open) PricesMsg.OFFER_HIDE else PricesMsg.OFFER_SHOW)) { open = !open }
    Column(opens.fillMaxWidth().padding(horizontal = Spacing.ScreenEdge, vertical = 8.dp)) {
        OfferLine(offer)
        if (open && canOpen) {
            // Under the text, past the picture.
            Column(Modifier.padding(start = 48.dp + 12.dp, top = 6.dp)) {
                verdict?.let { VerdictDetail(it) }
                if (ingredientId != null && ingredientName != null) NotThisIngredientButton { onExclude(ingredientId, ingredientName) }
            }
        }
    }
}

@Composable
private fun OfferLine(offer: Offer) {
    val t = LocalStrings.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ProductPicture(offer.imageUrl, offer.title)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            offer.ingredientName?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(offer.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(offer.shortText(t), offer.packSize, t(PricesMsg.UNTIL, dayMonthLabel(offer.validUntil, t.locale))).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VerdictLabel(offer.verdict)
        }
    }
}
