package dev.tobins.mealplanner.server.offers

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The fixtures are trimmed copies of what the stores served on 2026-11-12; the Jumbo pictures and the
 * Lidl donut tile are from 2026-11-15.
 */
internal fun fixture(name: String): String =
    checkNotNull(OfferParsersTest::class.java.getResource("/offers/$name")) { "missing fixture $name" }.readText()

class OfferParsersTest {
    @Test
    fun ahMetadataGivesOnlyNationalFoodSections() {
        val paths = AhParser.sectionPaths(fixture("ah-metadata.json"))
        assertEquals(
            listOf("Vlees", "Zuivel%2C%20eieren"),
            paths.map { it.substringAfter("category=") },
            "the spotlight repeats other sections; drugstore and Etos are not food",
        )
        assertEquals(emptyList(), AhParser.sectionPaths("""{"periods":[]}"""))
    }

    @Test
    fun ahSectionReadsProductsAndBonusGroups() {
        val offers = AhParser.section(fixture("ah-section.json"))
        assertEquals(4, offers.size)

        val mince = offers[0]
        assertEquals(
            Offer(
                store = Store.AH,
                title = "AH Gemengd gehakt 500 gram",
                deal = "VOOR 3.49",
                price = 3.49,
                originalPrice = 4.59,
                category = "Vlees",
                validFrom = "2026-11-09",
                validUntil = "2026-11-15",
                url = "https://www.ah.nl/producten/product/wi561333",
            ),
            mince,
        )
        val chicken = offers[1]
        assertEquals("AH Excellent vrije uitloop kip van de versafdeling", chicken.title)
        assertEquals("25% korting", chicken.deal)
        assertNull(chicken.price, "a group has no single price")
        assertEquals("https://www.ah.nl/bonus/groep/824627", chicken.url)
        assertEquals(5.4 to 6.0, offers[2].price to offers[2].originalPrice)
    }

    @Test
    fun ahTakesTheNarrowestPictureAtLeast200Wide() {
        // The shape of AH's `images` list; not a recorded response: on 2026-11-15 AH's API refused every
        // anonymous token, so none could be recorded (docs/data-sources.md, "Product pictures").
        fun images(vararg widths: Int) = widths.joinToString(",", "[", "]") {
            """{"width":$it,"height":$it,"url":"https://static.ah.nl/dam/product/AHI_1?rendition=${it}x${it}_JPG"}"""
        }
        fun section(images: String) = """{"bonusGroupOrProducts":[
            {"product":{"title":"AH Kipfilet","bonusMechanism":"25% korting","bonusStartDate":"2026-11-16","bonusEndDate":"2026-11-22","images":$images}},
            {"bonusGroup":{"id":"1","segmentDescription":"Alle AH kip","bonusStartDate":"2026-11-16","bonusEndDate":"2026-11-22","images":$images}}]}"""

        val offers = AhParser.section(section(images(800, 48, 400, 200, 80)))
        assertEquals(List(2) { "https://static.ah.nl/dam/product/AHI_1?rendition=200x200_JPG" }, offers.map { it.imageUrl }, "products and groups")
        assertEquals("https://static.ah.nl/dam/product/AHI_1?rendition=80x80_JPG", AhParser.section(section(images(48, 80))).first().imageUrl, "else the widest")
        assertEquals(listOf(null, null), AhParser.section(section("[]")).map { it.imageUrl })
        assertEquals(List(4) { null }, AhParser.section(fixture("ah-section.json")).map { it.imageUrl }, "the recorded section lists none")
    }

    @Test
    fun ahSkipsVolumeDiscounts() {
        val body = """{"bonusGroupOrProducts":[{"product":{"title":"Cola 4-pack","bonusMechanism":"5% volume voordeel",
            "bonusStartDate":"2026-11-09","bonusEndDate":"2026-11-15"}}]}"""
        assertEquals(emptyList(), AhParser.section(body))
    }

    @Test
    fun jumboReadsPromotionsFromThePagePayloadOnce() {
        val offers = JumboParser.page(fixture("jumbo-page.html"))
        assertEquals(3, offers.size, "the fixture lists one promotion twice")

        val salmon = offers.single { it.title == "Jumbo Noorse gerookte zalm" }
        assertEquals("voor 4,99", salmon.deal)
        assertEquals(4.99, salmon.price)
        assertEquals("Per verpakking à 200 gram", salmon.packSize)
        assertEquals("2026-11-11" to "2026-11-17", salmon.validFrom to salmon.validUntil)
        assertEquals("https://www.jumbo.com/aanbiedingen/jumbo-noorse-gerookte-zalm/3022099", salmon.url)

        val wok = offers.first()
        assertEquals("1+1 gratis", wok.deal)
        assertNull(wok.price, "a multi-buy has no single price")
        assertEquals(emptyList(), JumboParser.page("<html>no payload</html>"))
    }

    @Test
    fun jumboReadsEachPromotionsPicture() {
        val offers = JumboParser.page(fixture("jumbo-page.html")).associateBy({ it.title }, { it.imageUrl })
        val week46 = "https://www.jumbo.com/INTERSHOP/static/WFS/Jumbo-Grocery-Site/-/Jumbo-Grocery/nl_NL/Images_Aanbiedingen/2026/Weekaanbiedingen/Week%2046"
        assertEquals("$week46/Weekaanbiedingen-Week-46_56.png", offers["Jumbo Noorse gerookte zalm"])
        assertEquals("$week46/Weekaanbiedingen-Week-46_40.png", offers["Alle Jumbo wokgroenten zak à 400 gram of Jumbo's woksauzen"])
    }

    @Test
    fun lidlReadsDatedFoodTiles() {
        val offers = LidlParser.page(fixture("lidl-page.html"))
        // Oranges have no store dates (a Lidl Plus discount) and roses are not food.
        assertEquals(listOf("Croky chips", "CROKY Flatchips", "Vierkante donut"), offers.map { it.title })

        val chips = offers.first()
        assertEquals(1.99, chips.price)
        assertEquals("300 g", chips.packSize)
        assertEquals("Hartige hapjes", chips.category)
        assertEquals("2026-11-09" to "2026-11-15", chips.validFrom to chips.validUntil)
        assertEquals("https://www.lidl.nl/p/croky-chips/p11563244", chips.url)
    }

    @Test
    fun lidlReadsTheTilesPicture() {
        val offers = LidlParser.page(fixture("lidl-page.html")).associateBy({ it.title }, { it.imageUrl })
        assertEquals("https://www.lidl.nl/assets/gcp7166ef147e4144c287b9e15763796ad4.png", offers["Vierkante donut"])
        assertNull(offers["Croky chips"], "a tile without a picture has none")
    }

    @Test
    fun onlyHttpsPicturesOnTheStoresImageHostsAreKept() {
        val kept = listOf(
            "https://static.ah.nl/dam/product/AHI_4354523130303733373134?revLabel=1&rendition=200x200_JPG&fileType=binary",
            "https://www.jumbo.com/INTERSHOP/static/WFS/Jumbo-Grocery-Site/-/Jumbo-Grocery/nl_NL/Images_Aanbiedingen/2026/Weekaanbiedingen/Week%2046/Weekaanbiedingen-Week-46_56.png",
            "https://www.lidl.nl/assets/gcp7166ef147e4144c287b9e15763796ad4.png",
        )
        kept.forEach { assertEquals(it, StoreImages.accept(it)) }
        val dropped = listOf(
            null, "", "not a url", "/assets/gcp1.png",
            "http://www.lidl.nl/assets/gcp1.png", // plain http
            "javascript:alert(1)", "data:image/png;base64,AAAA",
            "https://evil.example/assets/gcp1.png", // another host
            "https://www.lidl.nl.evil.example/assets/gcp1.png",
            "https://www.lidl.nl/c/aanbiedingen/a10008785", // the store's site, but not a picture
            "https://www.lidl.nl/assets/../c/aanbiedingen", "https://www.lidl.nl/assets/%2e%2e/c/aanbiedingen",
            "https://user@www.lidl.nl/assets/gcp1.png", "https://www.lidl.nl:8443/assets/gcp1.png",
        )
        dropped.forEach { assertNull(StoreImages.accept(it), "$it") }
    }

    @Test
    fun helpers() {
        assertEquals(4.99, singlePrice("voor 4,99"))
        assertEquals(3.49, singlePrice("VOOR 3.49"))
        assertNull(singlePrice("2 voor 5,00"))
        assertNull(singlePrice("25% korting"))
        assertEquals("""a "b" & <c> 'd' é""", htmlUnescape("a &quot;b&quot; &amp; &lt;c&gt; &#39;d&#x27; &#233;"))
        assertEquals("&unknown;", htmlUnescape("&unknown;"))
    }
}
