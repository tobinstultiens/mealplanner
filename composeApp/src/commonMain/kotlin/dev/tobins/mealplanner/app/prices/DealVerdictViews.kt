package dev.tobins.mealplanner.app.prices

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
import dev.tobins.mealplanner.app.LocalStrings
import dev.tobins.mealplanner.app.shopping.dealHeading
import dev.tobins.mealplanner.app.shopping.productText
import dev.tobins.mealplanner.app.ui.CommonMsg

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
 * A shopping row's deals, as a tap on its chip opens them, under the row's [title]: per offer ([dealOffers]) the deal,
 * the product, its verdict at length with the earlier offers (issue 118) and, on a row of one of the account's
 * ingredients, "Not this ingredient" (issue 316). A dialog rather than a snackbar, as each offer needs a button of its
 * own: a row can have a right offer at one store and a wrong one at another.
 */
@Composable
internal fun DealDialog(title: String, offers: List<Offer>, today: String, onExclude: ((Offer) -> Unit)?, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { DealDetail(offers, today, onExclude, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onClose) { Text(LocalStrings.current(CommonMsg.CLOSE)) } },
    )
}

/** The body of [DealDialog]: per offer its deal ([dealHeading]), the product ([productText]), its verdict at length and [onExclude]'s button. */
@Composable
internal fun DealDetail(offers: List<Offer>, today: String, onExclude: ((Offer) -> Unit)?, modifier: Modifier = Modifier) {
    val t = LocalStrings.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        offers.forEach { offer ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(offer.dealHeading(today, t), style = MaterialTheme.typography.titleSmall)
                Text(offer.productText(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                offer.verdict?.let { VerdictDetail(it, Modifier.padding(top = 4.dp)) }
                onExclude?.let { exclude -> NotThisIngredientButton { exclude(offer) } }
            }
        }
    }
}

/** "Not this ingredient" under an offer (issue 316): one tap and no question, as the snackbar that follows has Undo. */
@Composable
internal fun NotThisIngredientButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(LocalStrings.current(CommonMsg.NOT_THIS_INGREDIENT)) }
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
            Text(t(PricesMsg.VERDICT_EARLIER), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium, color = quiet)
            verdict.earlier.forEach { Text(it.historyLine(verdict.measure, t), style = small, color = quiet) }
        }
    }
}
