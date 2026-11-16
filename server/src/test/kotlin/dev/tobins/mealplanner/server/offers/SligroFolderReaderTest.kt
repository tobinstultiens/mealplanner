package dev.tobins.mealplanner.server.offers

import dev.tobins.mealplanner.pricing.PackSize
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fixture holds the text of eleven pages of two Sligro folders (the food folder of 2026-10-29 to 2026-11-16 and
 * the one of 2026-11-19 to 2026-11-30), copied from their `spreads.json` on 2026-11-16. Page numbers below count in
 * the fixture: 1 cover, 2 fish, 3 bread, 4 sauces, 5 liqueurs, 6 wine, 7 cookware (folder 15); 8 fish, 9 dairy,
 * 10 sparkling wine, 11 spirits (folder 16).
 */
class SligroFolderReaderTest {
    private val reading = SligroFolderReader.read(fixture("sligro-folder-pages.json"))

    private fun page(number: Int) = reading.offers.filter { it.page == number }

    private fun unread(number: Int) = reading.unread.filter { it.page == number }.map { it.articleNumber }

    @Test
    fun readsThePeriodFromTheCover() {
        assertEquals(FolderPeriod(15, LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 16)), reading.period)
    }

    @Test
    fun aPeriodWithinOneMonthAndOneOverNewYearAreRead() {
        fun period(text: String) = SligroFolderReader.period(listOf(text))
        assertEquals(LocalDate.of(2026, 11, 3), period("NR 16 Geldig van dinsdag 3 t/m maandag 30 november 2026")?.from)
        val overNewYear = period("NR 24 Geldig van donderdag 17 december t/m maandag 4 januari 2027")
        assertEquals(LocalDate.of(2026, 12, 17), overNewYear?.from)
        assertEquals(LocalDate.of(2027, 1, 4), overNewYear?.until)
        assertNull(period("no cover here"), "without a period the reading is unusable")
    }

    @Test
    fun findsTheFoldersOnTheActionsPage() {
        val html = """<a href="https://folder.sligro.nl/foodvers-15-2026">x</a> <a href="https://folder.sligro.nl/wijn-15-2026?x=1">y</a>
            <a href="https://folder.sligro.nl/foodvers-15-2026">again</a>"""
        assertEquals(listOf("foodvers-15-2026", "wijn-15-2026"), SligroFolderReader.folderNames(html))
        assertEquals("https://folder.sligro.nl/foodvers-15-2026/spreads.json", SligroFolderReader.spreadsUrl("foodvers-15-2026"))
    }

    @Test
    fun readsTwoPricesOnOneLineAndSeveralPacksUnderOneHeading() {
        val anchovies = page(2).filter { it.articleNumber in setOf("159235", "158032") }
        assertEquals(listOf("Pot 200 gram", "Pot 740 gram"), anchovies.map { it.pack })
        assertEquals(listOf(5.89 to 5.50, 18.25 to 16.50), anchovies.map { it.regularPrice to it.price })
        assertTrue(anchovies.all { it.name.endsWith("Medusa Ansjovisfilet op olie") }, "the second pack has the heading of the first")

        val shrimp = page(2).single { it.articleNumber == "030576" }
        assertEquals("Zak 800 gram (nettogewicht)", shrimp.pack, "the prices on the line below the pack are read too")
        assertEquals(15.75 to 12.99, shrimp.regularPrice to shrimp.price)
    }

    @Test
    fun givesVariantsOnePriceAndALabelEach() {
        val sandwiches = page(3).filter { it.name.startsWith("Semenzato Tramezzini") }
        assertEquals(listOf("Semenzato Tramezzini Volkoren", "Semenzato Tramezzini Tomaat", "Semenzato Tramezzini Wit"), sandwiches.map { it.name })
        assertTrue(sandwiches.all { it.pack == "Pak 250 gram" && it.regularPrice == 2.65 && it.price == 1.99 })
        assertEquals(listOf("230204", "230203", "230201", "230200"), page(3).filter { "Rustique" in it.name }.map { it.articleNumber })
    }

    @Test
    fun readsABigPriceSplitInTwoOnlyNextToAPrintedOne() {
        val liqueur = page(5).single { it.articleNumber == "441167" }
        assertEquals("Fles 70 cl", liqueur.pack)
        assertEquals(10.49 to 7.99, liqueur.regularPrice to liqueur.price)

        val wine = page(6).single { it.articleNumber == "213689" }
        assertEquals(7.99 to 5.99, wine.regularPrice to wine.price, "'5.' and '99' with the regular price between them")
    }

    @Test
    fun refusesWhatItCannotPairWithCertainty() {
        // A price range for the regular price ("33.49 - 36.49"): several packs, no one regular price.
        assertEquals(listOf("796821", "958177", "150855", "369810"), unread(4).filter { it in setOf("796821", "958177", "150855", "369810") })
        // The big price printed before the entry, and "Per kilo" without a price.
        assertTrue("009947" in unread(2))
        // A single printed price on a page with big prices may be the struck-through regular price: 159.00 is not what is paid.
        assertTrue(page(7).none { it.articleNumber == "237706" })
        assertTrue("237706" in unread(7))
        // A big price alone could belong to the entry beside it: the cream is not 13.50.
        assertTrue(page(9).none { it.articleNumber == "189738" })
    }

    @Test
    fun refusesColumnsWhosePricesAreTangled() {
        // Two Moet and Chandon wines and a Vieille France champagne come with their prices in another order than their article numbers.
        assertTrue(page(10).none { it.articleNumber in setOf("282597", "189517", "571700", "718925", "189519", "029854", "189520") })
        // Bokma and Dujardin share a heading block but have a price each, in two lines of their own.
        assertTrue(page(11).none { it.articleNumber == "251708" || it.articleNumber == "255752" })
    }

    @Test
    fun neverYieldsAPriceThatIsNotBelowTheRegularOne() {
        assertTrue(reading.offers.all { it.regularPrice == null || it.regularPrice > it.price })
        assertTrue(reading.offers.all { it.name.isNotBlank() && it.pack.isNotBlank() })
    }

    @Test
    fun knowsTheSectionOfAPage() {
        assertEquals("FOOD", page(2).first().section, "the cover with the period names the section; it holds until the next one")
        assertNull(SligroFolderReader.page("Soep\nArt.nr. 123456\nPot 1 kilo 5.00 4.00", 1, null).first.single().section)
    }

    @Test
    fun readsPageTextsFromSpreads() {
        val texts = SligroFolderReader.pageTexts("""[{"pages":[{"text":"a"},{"text":"b"}]},{"pages":[{"text":"c"},{"id":3}]}]""")
        assertEquals(listOf("a", "b", "c", ""), texts)
    }

    /** What [PackSize] makes of the wholesale pack descriptions of the folders; the second value is what the text means. */
    @Test
    fun packSizeReadsMostWholesalePacksButNotAll() {
        fun grams(text: String) = PackSize.grams(text)
        fun millilitres(text: String) = PackSize.millilitres(text)
        fun pieces(text: String) = PackSize.pieces(text)

        // Read as meant.
        assertEquals(800.0, grams("Zak 800 gram (nettogewicht)"))
        assertEquals(4400.0, grams("Doos 40 x 110 gram"))
        assertEquals(11400.0, grams("Can 11,4 kilo"))
        assertEquals(60, pieces("Emmer 60 stuks"))
        assertEquals(1, pieces("Per stuk"))
        assertEquals(750.0, millilitres("Fles 75 cl"))
        assertEquals(7200.0, millilitres("Krat 24 x 30 cl"))
        assertEquals(6000.0, millilitres("Doos 12 x 50 cl"))
        assertEquals(6000.0, millilitres("Krimp 4 x 1,5 liter"))

        // Misread: a number in between hides the count, so only the last size is taken.
        assertEquals(500.0, millilitres("Tray 12 blikken x 50 cl"), "means 6000 ml")
        assertEquals(2640.0, millilitres("Tray 3 x 8 x 33 cl"), "means 7920 ml")
        assertEquals(25.0, grams("Bak ca. 40 x ca. 25 gram"), "means 1000 g")
        assertEquals(750.0, millilitres("6 dezelfde flessen à 75 cl, per fles"), "a price per bottle, in a box of six")
        assertEquals(2000.0, grams("Ca. 1-2 kilo, per kilo"), "a price per kilo, not a pack of 2 kilo")

        // Not read at all.
        assertNull(grams("Per kilo"))
        assertNull(millilitres("Literfles"))
        assertNull(grams("Fles 1 liter".replace("liter", "ltr")))
    }
}
