package dev.tobins.mealplanner.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import dev.tobins.mealplanner.api.Offer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.PriceVerdict

/**
 * The verdict's few words ([chipText]) under an offer's deal, or nothing while there is nothing to say. A real deal
 * is in the theme's primary colour and bold, every other verdict in the plain colour of small print: the words say
 * which it is, in dark mode and to a colour-blind eye as well, and only the one worth acting on stands out.
 */
@Composable
internal fun VerdictLabel(verdict: PriceVerdict?, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    val text = verdict?.chipText(LocalStrings.current) ?: return
    val good = verdict.tone == VerdictTone.GOOD
    Text(
        text,
        modifier,
        style = MaterialTheme.typography.labelMedium,
        color = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (good) FontWeight.Bold else FontWeight.Normal,
        textAlign = textAlign,
    )
}

/**
 * The price history of a shopping row's [offers] (issue 118), as the Offers screen opens it: each offer's deal and its
 * verdict at length ([VerdictDetail]) with the earlier offers. Opened from the deal's snackbar ("Price history"), as a
 * snackbar has no room for a list.
 */
@Composable
internal fun VerdictHistoryDialog(title: String, offers: List<Offer>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { VerdictHistory(offers, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onClose) { Text(LocalStrings.current(Msg.CLOSE)) } },
    )
}

/** The body of [VerdictHistoryDialog]: per offer its deal in a few words, then its verdict at length. */
@Composable
internal fun VerdictHistory(offers: List<Offer>, modifier: Modifier = Modifier) {
    val t = LocalStrings.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        offers.forEach { offer ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(offer.shortText(t), style = MaterialTheme.typography.titleSmall)
                offer.verdict?.let { VerdictDetail(it) }
            }
        }
    }
}

/**
 * The verdict at length: its line ([line]; "Too new to judge (2 of 56 days)" while the history is short), what else it
 * knows ([notes]), and the latest earlier offers of the pack, each with its dates, deal and value.
 */
@Composable
internal fun VerdictDetail(verdict: PriceVerdict, modifier: Modifier = Modifier) {
    val t = LocalStrings.current
    val small = MaterialTheme.typography.bodySmall
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(verdict.line(t), style = MaterialTheme.typography.bodyMedium)
        verdict.notes(t).forEach { Text(it, style = small, color = quiet) }
        if (verdict.earlier.isNotEmpty()) {
            Text(t(Msg.VERDICT_EARLIER), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium, color = quiet)
            verdict.earlier.forEach { Text(it.historyLine(verdict.measure, t), style = small, color = quiet) }
        }
    }
}
