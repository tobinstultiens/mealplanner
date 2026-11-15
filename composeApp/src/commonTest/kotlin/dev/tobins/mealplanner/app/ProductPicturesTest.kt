package dev.tobins.mealplanner.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProductPicturesTest {
    private val ah = "https://static.ah.nl/dam/product/p.webp"
    private val jumbo = "https://www.jumbo.com/INTERSHOP/static/Week%2046/p.png"
    private val lidl = "https://www.lidl.nl/assets/gcp1.png"

    @Test
    fun theAppLoadsEveryStoresPictures() {
        listOf(ah, jumbo, lidl).forEach { assertEquals(it, loadablePictureUrl(it, browser = false)) }
    }

    @Test
    fun theBrowserCannotDrawJumbosPicturesButTheOthers() {
        assertNull(loadablePictureUrl(jumbo, browser = true))
        assertEquals(ah, loadablePictureUrl(ah, browser = true))
        assertEquals(lidl, loadablePictureUrl(lidl, browser = true))
    }

    @Test
    fun noAddressMeansNoPicture() {
        assertNull(loadablePictureUrl(null, browser = false))
        assertNull(loadablePictureUrl("  ", browser = false))
    }

    @Test
    fun theLetterOfAProductStandsInForItsPicture() {
        assertEquals("R", pictureInitial("rode paprika"))
        assertEquals("É", pictureInitial("  énkele boon"))
        assertEquals("5", pictureInitial("5 kruiden"))
        assertEquals("•", pictureInitial(""))
    }
}
