package dev.tobins.mealplanner.server.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Which pack texts are one pack, and the short names of packs, on the stores' own texts: every one below is a pack
 * size Jumbo or Lidl gave in the offers recorded on 15, 16, 18 and 20 November 2026, unless it says otherwise.
 */
class PacksTest {
    /** The texts as the history keeps them ([ProductKey.pack]). */
    private fun key(text: String) = normalised(text)

    @Test
    fun footnotesAndMarkupAreNoPartOfThePack() {
        val bottle = key("Alle flesjes à 500 ml, per flesje")
        assertEquals(bottle, packText(key("Alle flesjes à 500 ml, per flesje<br />M.u.v. Gekoeld")))
        assertEquals(bottle, packText(key("Alle flesjes à 500 ml, per flesje<br />M.u.v. Gekoeld<br /><br />")))
        assertEquals(key("6 x 1,5 L"), packText(key("6 x 1,5 L<br />De korting is al verwerkt in de verkoopprijs van dit artikel")))
        assertEquals(key("Per verpakking"), packText(key("Per verpakking<br />M.u.v. Voordeelverpakkingen<br /><br />")))
        assertEquals(bottle, packText(key("Alle flesjes à 500 ml, per flesje · M.u.v. Gekoeld")), "as the offers are cleaned today")
        assertEquals(key("Chips & dip"), packText(key("Chips &amp; dip")))
        // A subtitle that is only a footnote says nothing of the pack, as no subtitle does.
        assertEquals("", packText(key("M.u.v. Wegwerpmesjes")))
        // Made up: a limit per order is a footnote too.
        assertEquals(key("Blikje 250 ml, per blikje"), packText(key("Blikje 250 ml, per blikje<br />Maximaal 2 blikjes per bestelling")))
    }

    @Test
    fun linesThatTellProductsApartStay() {
        // Two wines under one title are two products; a list of flavours before the size is part of the pack.
        assertNotEquals(
            packText(key("Alle flessen à 750 ml, per fles<br />Land van herkomst: Frankrijk")),
            packText(key("Alle flessen à 750 ml, per fles<br />Land van herkomst: Italië")),
        )
        assertEquals(key("Knoflook, curry of Italiaans Schaal 330 gram, per 100 gram"), packText(key("Knoflook, curry of Italiaans<br />Schaal 330 gram, per 100 gram")))
        // Left as they are: "per fles" or not, and loose by the kilo or by the piece.
        assertNotEquals(packText(key("Alle flessen à 750 ml")), packText(key("Alle flessen à 750 ml, per fles")))
        assertNotEquals(packText(key("Los, per kilo")), packText(key("Los, per stuk")))
    }

    @Test
    fun approximateWeightsWithinAFewPercentAreOnePack() {
        // Made up, as the review of #280 feared: the same pack weighed a little differently next week.
        val packs = SamePacks(listOf("ca. 460 g", "ca. 450 g", "schaal circa 700 gram. per 100 gram", "schaal circa 720 gram. per 100 gram", "ca. 500 g"))
        assertEquals("ca. 450 g", packs["ca. 460 g"], "2 % apart")
        assertEquals("ca. 450 g", packs["ca. 450 g"])
        assertEquals("schaal circa 700 gram. per 100 gram", packs["schaal circa 720 gram. per 100 gram"], "3 % apart, Jumbo's way")
        assertEquals("ca. 500 g", packs["ca. 500 g"], "11 % heavier than the lightest: another pack")
        // Whatever order they come in, the lightest names the pack.
        assertEquals("ca. 450 g", SamePacks(listOf("ca. 460 g", "ca. 450 g").reversed())["ca. 460 g"])
    }

    @Test
    fun aRunOfApproximateWeightsIsMeasuredFromItsLightest() {
        // 450, 470 and 490: each 4 % from the one before, but 490 is 9 % above 450, so it starts a pack of its own.
        val packs = SamePacks(listOf("ca. 450 g", "ca. 470 g", "ca. 490 g"))
        assertEquals(listOf("ca. 450 g", "ca. 450 g", "ca. 490 g"), listOf("ca. 450 g", "ca. 470 g", "ca. 490 g").map { packs[it] })
    }

    @Test
    fun onlyApproximateWeightsOfTheSameKindAreJoined() {
        val packs = SamePacks(listOf("450 g", "460 g", "ca. 450 g", "bak ca. 460 g", "ca. 0.46 kg"))
        assertEquals("460 g", packs["460 g"], "a weight the store states is exact")
        assertEquals("bak ca. 460 g", packs["bak ca. 460 g"], "another kind of pack")
        assertEquals("ca. 450 g", packs["ca. 0.46 kg"], "the same weight in kilos")
        assertEquals("alle flesjes à 500 ml. per flesje", SamePacks(emptyList())["alle flesjes à 500 ml. per flesje<br />m.u.v. gekoeld"], "a text it was not given")
    }

    @Test
    fun aPackIsNamedByItsSize() {
        val names = mapOf(
            "Alle flessen à 1,5 liter" to "1,5 l",
            "Alle flesjes à 500 ml, per flesje<br />M.u.v. Gekoeld" to "500 ml",
            "Tray 24x250ml" to "24 × 250 ml",
            "24 x 33 cl" to "24 × 33 cl",
            "Tray met 24 blikken à 33cl" to "24 × 33 cl",
            "6 x 1,5 L<br />De korting is al verwerkt in de verkoopprijs van dit artikel" to "6 × 1,5 l",
            "12 x 250 ml<br />De korting is al verwerkt in de verkoopprijs van dit artikel" to "12 × 250 ml",
            "4-pack à 1 liter" to "4 × 1 l",
            "2 verpakkingen à 200 gram" to "2 × 200 g",
            "2 bekers à 500ml" to "2 × 500 ml",
            "2 x 150 g" to "2 × 150 g",
            "Net 1 kilo" to "1 kg",
            "Bak 250 gram" to "250 g",
            "1 kg" to "1 kg",
            "1500 g" to "1500 g",
            "Schaal 330 gram, per 100 gram" to "330 g",
            "Knoflook, curry of Italiaans<br />Schaal 330 gram, per 100 gram" to "330 g",
            "Schaal circa 700 gram, per 100 gram" to "ca. 700 g",
            "Alle verpakkingen circa 130 gram" to "ca. 130 g",
            "Alle bakjes à 170-200 gram, per bakje" to "170–200 g",
            "3 verpakkingen à 133 - 300 gram" to "133–300 g",
            "Salt, Red Paprika of Chili Pepper<br />Alle zakken à 150 gram" to "150 g",
            "Alle flessen à 750 ml, per fles<br />Land van herkomst: Spanje<br />M.u.v. Neleman Orange Wine" to "750 ml",
            "Fles 750 ml <br />Land van herkomst: Italië" to "750 ml",
            "Per bakje à 100 gram" to "100 g",
            "Doorsnede van 24 cm" to "24 cm",
            // The pan of #280's tests: a piece, named by its size.
            "1 stuk, doorsnede van 14 cm" to "14 cm",
            // As the history keeps it, in lower case with a decimal point.
            "alle flessen à 1.5 liter" to "1,5 l",
        )
        for ((text, name) in names) assertEquals(name, packName(text), text)
    }

    @Test
    fun aPackWithoutOneSizeHasNoName() {
        val nameless = listOf(
            null, "", "Per stuk", "Los", "Los per kilo", "Los, per 500 gram", "Per 100 gram", "Per verpakking<br />M.u.v. Kassa-items",
            // Pieces: the app names such an offer by its deal.
            "Pak 16 stuks", "3x 16 stuks", "15 Stk.", "Verpakking met 8 pakjes à 5 stuks", "Diverse soorten<br />Schaal à 2 stuks",
            // Several sizes to choose from.
            "Zak 500 gram of 1 kilo", "6x 350 gram of 6x 400 gram", "Alle zakken à 150 of 200 gram", "24 of 28cm<br />Per stuk",
            "Alle zakken à 75, 100 of 160 gram, per zak<br />M.u.v. Crispy Stars, paprika en bolognese à 100 gram",
            "Alle bekers à 450-500 gram of pak à 1 liter", "Alle verpakkingen à 20 stuks of 485 ml, per verpakking", "Zand of grijs<br />70x140 cm of 50x100 cm",
            "Krat of set à 6 flesjes of blikjes of los blik à 500 ml<br />M.u.v. Geschenkverpakkingen",
            "M.u.v. Verpakking à 5 stuks",
        )
        for (text in nameless) assertEquals(null, packName(text), text)
    }
}
