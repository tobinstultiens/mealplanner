package dev.tobins.mealplanner.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.shopping.ShopSection
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

    @Test
    fun everyShopSectionHasItsOwnIcon() {
        val icons = ShopSection.entries.map { sectionIcon(it.title) }
        assertEquals(ShopSection.entries.size, icons.map { it.name }.toSet().size, "one distinct icon per section")
        icons.forEach { assertTrue(it in AppIcons.all, "${it.name} is built by the all-icons check") }
    }

    @Test
    fun anUnknownSectionTitleGetsTheOtherIcon() {
        assertEquals(AppIcons.SectionOther, sectionIcon("Dairy"))
        assertEquals(AppIcons.SectionOther, sectionIcon(""))
        assertEquals(AppIcons.SectionFrozen, sectionIcon(ShopSection.FROZEN.title))
    }

    @Test
    fun theMarkAndEverySpotIllustrationBuild() {
        assertEquals(96.dp, Illustrations.Mark.defaultWidth)
        Spot.entries.forEach { spot ->
            val image = spot.build(Color.Black, Color.LightGray)
            assertEquals(96.dp, image.defaultWidth, spot.name)
            assertEquals(96.dp, image.defaultHeight, spot.name)
            assertEquals(spot.name, image.name)
        }
        assertEquals(4, Spot.entries.size)
    }
}
