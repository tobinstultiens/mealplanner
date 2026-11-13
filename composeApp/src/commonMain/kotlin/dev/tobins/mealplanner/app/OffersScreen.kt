package dev.tobins.mealplanner.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OffersScreen(api: ApiClient, onBack: () -> Unit) {
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offers") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(enabled = !refreshing, onClick = {
                        refreshing = true
                        scope.launch {
                            (load { api.refreshOffers() } as? Load.Failed)?.let { snackbar.showSnackbar("Could not refresh: ${it.message}") }
                            refreshing = false
                            reload++
                        }
                    }) { Text(if (refreshing) "Fetching…" else "Refresh") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val current = state) {
            Load.Loading -> CenteredBox(Modifier.padding(padding)) { CircularProgressIndicator() }
            is Load.Failed -> Column(Modifier.padding(padding)) {
                ErrorMessage("Could not load the offers", current.message) { reload++ }
            }
            is Load.Ready -> {
                val all = current.value.offers
                val shown = all
                    .filter { !onlyMatched || it.ingredientName != null }
                    .filter { store == null || it.store == store }
                    .sortedWith(compareBy({ it.ingredientName == null }, { it.ingredientName.orEmpty() }, { it.store }))
                LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    item {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            current.value.sources.forEach { SourceLine(it) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = onlyMatched,
                                    onClick = { onlyMatched = !onlyMatched },
                                    label = { Text("My ingredients (${all.count { it.ingredientName != null }})") },
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
                            Text(
                                if (all.isEmpty()) "No offers fetched yet. Tap Refresh." else "No offers match these filters.",
                                modifier = Modifier.padding(16.dp),
                            )
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
    val text = when {
        status.error != null && status.fetchedAt == null -> "${status.store.label}: could not fetch (${status.error})"
        status.error != null -> "${status.store.label}: ${status.offerCount} offers, last fetch failed (${status.error})"
        status.fetchedAt != null -> "${status.store.label}: ${status.offerCount} offers, fetched ${dayLabel(status.fetchedAt!!.take(10))}"
        else -> "${status.store.label}: not fetched yet"
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (status.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun OfferRow(offer: Offer) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        offer.ingredientName?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(offer.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(offer.shortText(), offer.packSize, "until ${dayLabel(offer.validUntil)}").joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
