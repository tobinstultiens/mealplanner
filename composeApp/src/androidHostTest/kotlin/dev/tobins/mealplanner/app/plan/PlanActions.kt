package dev.tobins.mealplanner.app.plan

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode

/*
 * The Plan in host tests: the one way to wait for it and to open a meal's menu (issue 306).
 *
 * The Plan fills in from separate answers: the week, then the share in the box (a card between the plan's head and the
 * days), the standard meals and my recipes (these two only change menus). A test that scrolled to a day by its index in
 * the list, or picked a button by where it is drawn, opened another day's menu whenever the box card came after it
 * looked: the index then named the day after, and a heading scrolled just above the list is still in the tree with its
 * bounds clipped to 0, so "the first button below the heading" was the next day's. Here a day's button is the one in
 * that day's card, and a week with a box card is used once the card is there, as nothing moves the days after it.
 */

/** Waits until the Plan shows its week, and with [boxCard] the card of the share in the box, the last answer that moves the days. */
internal fun ComposeTestRule.waitForPlan(boxCard: Boolean = false) {
    waitForText("Prep a recipe…")
    if (boxCard) waitForText("A portion in the box")
}

/** The swap button of the [slot] meal ("lunch", "dinner") in the card of the day headed [day], wherever the list is scrolled. */
internal fun swapButtonOf(day: String, slot: String): SemanticsMatcher =
    hasContentDescription("Swap $slot") and hasAnyAncestor(hasAnyChild(hasText(day)))

/**
 * Opens the swap menu of the [slot] meal of the day headed [day]: the list is lazy, so it is scrolled to the day, then
 * to the button, which is tapped where it is then. Call it once [waitForPlan] has returned.
 */
internal fun ComposeTestRule.openSwapMenu(day: String, slot: String) {
    onNode(hasScrollToIndexAction()).performScrollToNode(hasText(day))
    onNode(swapButtonOf(day, slot)).performScrollTo().performClick()
    // Every meal's menu has this entry, greyed or not.
    waitForText("Adjust what was in it")
}

/**
 * Waits until a change made on the Plan is done, including what follows it (asking for the standard meals again, the
 * skip sheet): the meals' buttons are greyed while it runs. A tap that starts a change greys them at once, so this
 * cannot return before the change has started.
 */
internal fun ComposeTestRule.waitUntilPlanIsIdle() = waitUntil(timeoutMillis = 10_000) {
    val swaps = onAllNodes(hasContentDescription("Swap ", substring = true)).fetchSemanticsNodes()
    swaps.isNotEmpty() && swaps.none { SemanticsProperties.Disabled in it.config }
}

private fun ComposeTestRule.waitForText(text: String) =
    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
