package dev.tobins.mealplanner.app

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IconsTest {
    /** A typo in a copied path only shows when the icon is first drawn; build them all here instead. */
    @Test
    fun everyIconBuildsAtTheStandardSize() {
        assertTrue(AppIcons.all.isNotEmpty())
        AppIcons.all.forEach { icon ->
            assertEquals(24.dp, icon.defaultWidth, icon.name)
            assertEquals(24.dp, icon.defaultHeight, icon.name)
        }
        assertEquals(AppIcons.all.size, AppIcons.all.map { it.name }.toSet().size, "icon names are unique")
    }
}
