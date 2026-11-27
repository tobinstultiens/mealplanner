package dev.tobins.mealplanner.server.prices.offers

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.prices.history.ProductKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The fixtures are trimmed copies of what the stores served on 2026-11-12; the pictures (AH `images`,
 * Jumbo `image`) and the Lidl donut tile are from 2026-11-15.
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
                imageUrl = "https://static.ah.nl/dam/product/AHI_5f37776b6d71456651626935325a6b484a315a317577?revLabel=1&rendition=200x200_WEBP&fileType=binary",
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
        // AH lists 800, 400 and 200 px WebP, a 48 px GIF and an 80 px JPEG, in that order; a group made
        // up for the week ("Alle …") has a picture of its own in 200 and 708 px.
        val (mince, chicken, _, stew) = AhParser.section(fixture("ah-section.json"))
        assertEquals(
            "https://static.ah.nl/mam/bonus/COMBIBEELD_207831_AH_NL_WK46_ALLE_AH_GREENFIELDS_STOOFVLEES?rendition=200px&fileType=binary&version=kybRNnZOGKGxCexGJV6X_DmmLfSHZUXOpnvnXD-SZHo",
            stew.imageUrl,
        )
        assertEquals(stew.imageUrl, StoreImages.accept(stew.imageUrl), "kept when stored")
        assertEquals(
            "https://static.ah.nl/dam/product/AHI_5f37776b6d71456651626935325a6b484a315a317577?revLabel=1&rendition=200x200_WEBP&fileType=binary",
            mince.imageUrl,
        )
        assertEquals(
            "https://static.ah.nl/dam/product/AHI_386e6f465431636d544165796b302d33616a79774951?revLabel=1&rendition=200x200_WEBP&fileType=binary",
            chicken.imageUrl,
            "a bonus group has a picture too",
        )

        fun section(images: String) = """{"bonusGroupOrProducts":[{"product":{"title":"AH Kipfilet","bonusMechanism":"25% korting",
            "bonusStartDate":"2026-11-16","bonusEndDate":"2026-11-22","images":$images}}]}"""
        val small = """[{"width":48,"height":48,"url":"https://static.ah.nl/dam/product/AHI_1?rendition=48x48_GIF"},
            {"width":80,"height":80,"url":"https://static.ah.nl/dam/product/AHI_1?rendition=80x80_JPG"}]"""
        assertEquals("https://static.ah.nl/dam/product/AHI_1?rendition=80x80_JPG", AhParser.section(section(small)).single().imageUrl, "else the widest")
        assertNull(AhParser.section(section("[]")).single().imageUrl)
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
    fun jumboShowsNoMarkupInASubtitleAndKeepsItsLinesApart() {
        val rolls = JumboParser.page(fixture("jumbo-page.html")).single { it.title.startsWith("Page Compleet") }
        assertEquals(
            "Compleet Schoon à 24 rollen, Kussenzacht à 18 rollen of Sensitive à 16 rollen, per verpakking · M.u.v. Gekoeld",
            rolls.packSize,
        )
    }

    @Test
    fun lidlAndAhShowNoMarkupInATitleOrPack() {
        fun tile(title: String, packaging: String) = """data-grid-data="{&quot;fullTitle&quot;: &quot;$title&quot;, &quot;category&quot;: &quot;Food&quot;, &quot;storeStartDate&quot;: 1794178800, &quot;storeEndDate&quot;: 1794783599, &quot;canonicalUrl&quot;: &quot;/p/x/p1&quot;, &quot;price&quot;: {&quot;price&quot;: 1.5, &quot;packaging&quot;: {&quot;text&quot;: &quot;$packaging&quot;}}}""""
        val lidl = LidlParser.page(tile("Chips &amp;amp; dip", "300 g&lt;br /&gt;per zak")).single()
        // The tile's attribute is unescaped once for the JSON, the text in it once more.
        assertEquals("Chips & dip", lidl.title)
        assertEquals("300 g · per zak", lidl.packSize)

        val ah = AhParser.section("""{"bonusGroupOrProducts":[{"product":{"title":"Ben &amp; Jerry's<br>ijs","bonusMechanism":"2 voor 5,00",
            "salesUnitSize":"465 ml<br />","bonusStartDate":"2026-11-09","bonusEndDate":"2026-11-15"}}]}""").single()
        assertEquals("Ben & Jerry's · ijs", ah.title)
        assertEquals("465 ml", ah.packSize, "a trailing line break leaves no separator")
    }

    @Test
    fun aCharacterReferenceOutOfRangeInATitleLeavesTheOfferParsed() {
        val tile = """data-grid-data="{&quot;fullTitle&quot;: &quot;Chips &amp;#99999999999; dip&quot;, &quot;category&quot;: &quot;Food&quot;, &quot;storeStartDate&quot;: 1794178800, &quot;storeEndDate&quot;: 1794783599, &quot;price&quot;: {&quot;price&quot;: 1.5}}""""
        assertEquals("Chips &#99999999999; dip", LidlParser.page(tile).single().title)
    }

    @Test
    fun anEscapedTagIsCleanedTheSameHoweverOftenAndGivesOneProductKey() {
        val once = storeText("&lt;b&gt;")
        assertEquals(once, storeText(once))
        assertEquals("", once)
        assertEquals("a", storeText("&amp;lt;b&amp;gt;a"), "also where it is escaped twice")
        val offer = Offer(Store.JUMBO, "Kip &lt;b&gt;", "1+1 gratis", null, packSize = "&lt;b&gt;", validFrom = "2026-11-16", validUntil = "2026-11-22")
        assertEquals(ProductKey.of(offer), ProductKey.recorded(Store.JUMBO, "kip &lt;b&gt;", "&lt;b&gt;"))
        assertEquals(ProductKey.of(offer), ProductKey.of(offer.cleaned()))
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
            "https://static.ah.nl/dam/product/AHI_43545239393132353833?revLabel=1&rendition=200x200_WEBP&fileType=binary",
            "https://static.ah.nl/mam/bonus/COMBIBEELD_207839_AH_NL_WK46_AH_OVENSPECIALITEITEN?rendition=200px&fileType=binary&version=HHh15Qg61OTKzllc1IBd_0-vaL2G2EFUMoxFhK7y_yA",
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
            "https://static.ah.nl/somewhere/else.png",
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
        assertEquals("a · b", storeText(storeText("a<br />b<br /><br />")), "cleaning twice is cleaning once")
        assertEquals("a &#99999999999; b &#xD800;", htmlUnescape("a &#99999999999; b &#xD800;"), "a number that is no character stays text")
        assertEquals("Ben & Jerry's", storeText(" Ben  &amp;\n Jerry's <b></b>"))
    }
}
