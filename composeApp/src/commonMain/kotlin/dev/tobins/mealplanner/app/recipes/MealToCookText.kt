package dev.tobins.mealplanner.app.recipes

import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.ui.CommonMsg
import dev.tobins.mealplanner.app.ui.dayLabel
import dev.tobins.mealplanner.app.ui.formatAmount
import dev.tobins.mealplanner.app.ui.servingsText

/**
 * What a planned meal tells the recipe opened from it, in a sentence: how much to cook now and for
 * whom, or that there is nothing to cook (skipped, or the leftovers of yesterday's dinner). A prepped batch's meal
 * on its made-on day cooks the batch for the later meals, also when itself skipped; the later meals were made then.
 */
fun MealToCook.sentence(strings: Strings): String {
    val locale = strings.locale
    return when {
        prepMadeOn != null && cookPortions > 0.0 -> strings(
            CommonMsg.COOK_PREP, servingsText(cookPortions, strings),
            formatAmount(if (skipped) 0.0 else portions, locale), formatAmount(cookPortions - if (skipped) 0.0 else portions, locale),
        )
        prepMadeOn != null && !skipped -> strings(CommonMsg.NOTHING_TO_COOK_PREPPED, dayLabel(prepMadeOn, locale))
        skipped -> strings(CommonMsg.NOTHING_TO_COOK_SKIPPED)
        cookPortions <= 0.0 -> strings(CommonMsg.NOTHING_TO_COOK_LEFTOVERS)
        cookPortions > portions + 1e-9 -> strings(
            CommonMsg.COOK_WITH_LEFTOVERS, servingsText(cookPortions, strings),
            formatAmount(portions, locale), formatAmount(cookPortions - portions, locale),
        )
        else -> strings(CommonMsg.COOK_SERVINGS, servingsText(cookPortions, strings))
    }
}
