package dev.tobins.mealplanner.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.Store

/**
 * The field and button that put an own item on the list. Enter or the button adds it; the field
 * then clears and keeps the focus for the next one. A refusal leaves the text for the user to
 * change. Without a connection the field is off and says why.
 */
@Composable
internal fun AddItemRow(session: ShoppingSession) {
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
internal fun PartTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(start = Spacing.ScreenEdge, end = Spacing.ScreenEdge, top = Spacing.InsideCard),
    )
}

/** The title of the bought items no meal needs any more, with what the two buttons do. */
@Composable
internal fun NoLongerNeededHeader() {
    val t = LocalStrings.current
    Column(Modifier.fillMaxWidth().padding(top = Spacing.InsideCard)) {
        PartTitle(t(Msg.NO_LONGER_NEEDED_TITLE))
        Text(
            t(Msg.NO_LONGER_NEEDED_HINT),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.ScreenEdge),
        )
    }
}

/**
 * A bought item that no meal needs now: its name and what was bought, and two buttons. "Use it
 * up" puts it on hand (only for catalogue ingredients not on hand already), "Remove" takes it off.
 */
@Composable
internal fun NoLongerNeededRow(item: ShoppingItem, onUseUp: () -> Unit, onRemove: () -> Unit) {
    val t = LocalStrings.current
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.ScreenEdge, vertical = Spacing.Tight)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(item.boughtText(t.locale), style = MaterialTheme.typography.bodyMedium.numeric(), textAlign = TextAlign.End)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!item.onHand && item.key.startsWith("i")) TextButton(onClick = onUseUp) { Text(t(Msg.USE_IT_UP)) }
            TextButton(onClick = onRemove) { Text(t(Msg.REMOVE)) }
        }
    }
}

/** An advice group's title, what it is for and saves, and the button that hides it for the week. */
@Composable
internal fun AdviceHeader(title: String, hint: String, onHide: () -> Unit) {
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
internal fun HiddenAdvice(text: String, onShow: () -> Unit) {
    val t = LocalStrings.current
    Row(Modifier.fillMaxWidth().padding(start = Spacing.ScreenEdge, end = 4.dp, top = Spacing.Tight), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = onShow) { Text(t(Msg.SHOW_ADVICE)) }
    }
}

@Composable
internal fun ShoppingModeSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
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
internal fun SectionHeaderRow(title: String, section: ShoppingSectionView, open: Boolean, onToggle: () -> Unit) {
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
                // Done, not ticked: a row with nothing left to buy is done without a tick.
                t(Msg.SECTION_DONE, section.done.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The collapsed "Done (n)" line at the end of a section. */
@Composable
internal fun DoneGroupHeader(count: Int, open: Boolean, onToggle: () -> Unit) {
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
internal fun ViewSwitch(view: ShoppingView, onChange: (ShoppingView) -> Unit) {
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
internal fun GroupHeader(title: String, hint: String, onClick: (() -> Unit)?) {
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
internal fun ShopIAmIn(store: Store, saving: Double?, onAllStores: () -> Unit) {
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
internal fun ToggleHeader(title: String, open: Boolean, onToggle: () -> Unit) {
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
internal fun ShoppingRow(item: ShoppingItem, offer: Offer?, shops: Set<Store>, today: String, shoppingMode: Boolean, suggested: Boolean, onToggle: () -> Unit, onRemove: (() -> Unit)?, onOfferClick: () -> Unit) {
    val t = LocalStrings.current
    val more = item.moreNeeded()
    val toBuy = item.toBuyText(t)
    Row(
        Modifier.fillMaxWidth()
            .toggleable(item.done, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = if (shoppingMode) 64.dp else 56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = item.done, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        ProductPicture(item.pictureUrl(offer, shops), item.name, size = if (shoppingMode) 56.dp else 48.dp, dimmed = item.done)
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 6.dp, bottom = 6.dp)) {
            Text(
                item.nameText(t),
                style = if (shoppingMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                textDecoration = if (item.done) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Bought, and the plan changed since: say how much is still to buy.
            if (more != null) {
                Text(more.text(t), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            // Not ticked yet, and less than the week's need is left to buy: what is at home, or that meals are done, and what that leaves.
            if (toBuy != null) {
                Text(toBuy, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            // Stock-up advice is for no meal in particular; a provisional buy-ahead meal is only an example.
            if (item.sourceMeals.isNotEmpty()) {
                Text(
                    item.sourceMeals.joinToString().let { if (suggested) t(Msg.SUGGESTED_MEALS, it) else it },
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
            Text(item.amountText(t.locale), style = MaterialTheme.typography.bodyMedium.numeric(), textAlign = TextAlign.End)
            if (offer != null) {
                // Packs for what is still to buy; a ticked row has none, as what was bought is not known in packs.
                item.packsStillToBuy(offer)?.let {
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
