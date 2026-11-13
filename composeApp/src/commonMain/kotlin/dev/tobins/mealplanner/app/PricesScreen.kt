package dev.tobins.mealplanner.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.PricesState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The regular price per kg used for each ingredient, and the product it came from, so a
 * wrong match is easy to spot. Recipes are costed with these, minus this week's offers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PricesScreen(api: ApiClient, onBack: () -> Unit) {
    var state by remember { mutableStateOf<Load<PricesState>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        if (state !is Load.Ready) state = Load.Loading
        state = load { api.prices() }
        // A lookup runs on the server for a few minutes: check back until it is done.
        while ((state as? Load.Ready)?.value?.refreshing == true) {
            delay(5_000)
            (load { api.prices() } as? Load.Ready)?.let { state = it }
        }
    }
    val refreshing = (state as? Load.Ready)?.value?.refreshing == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Regular prices") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(enabled = !refreshing, onClick = {
                        scope.launch {
                            when (val started = load { api.refreshPrices() }) {
                                is Load.Ready -> {
                                    state = started
                                    reload++
                                }
                                is Load.Failed -> snackbar.showSnackbar("Could not start: ${started.message}")
                                Load.Loading -> {}
                            }
                        }
                    }) { Text(if (refreshing) "Looking up…" else "Refresh") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val current = state) {
            Load.Loading -> CenteredBox(Modifier.padding(padding)) { CircularProgressIndicator() }
            is Load.Failed -> Column(Modifier.padding(padding)) {
                ErrorMessage("Could not load the prices", current.message) { reload++ }
            }
            is Load.Ready -> {
                val prices = current.value
                LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    item {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                when {
                                    prices.refreshing -> "Looking up prices at Albert Heijn; this takes a few minutes."
                                    prices.fetchedAt == null -> "No prices looked up yet. Tap Refresh."
                                    else -> "${prices.prices.size} ingredients priced from Albert Heijn, looked up ${dayLabel(prices.fetchedAt!!.take(10))}."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "The cheapest few products that match each ingredient set its price per kg. " +
                                    "Offers are taken off this when costing recipes and choosing meals.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(prices.prices, key = { it.ingredientId }) { price ->
                        ListItem(
                            headlineContent = { Text("${price.ingredientName}: ${formatEuro(price.eurosPerKg)}/kg") },
                            supportingContent = {
                                Text(
                                    listOfNotNull(
                                        price.product,
                                        price.packSize,
                                        price.packPrice?.let { formatEuro(it) },
                                    ).joinToString(" · "),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                        HorizontalDivider()
                    }
                    if (prices.unpriced.isNotEmpty()) {
                        item {
                            Text(
                                "No product found, costed at a typical price: ${prices.unpriced.joinToString("; ")}.",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
