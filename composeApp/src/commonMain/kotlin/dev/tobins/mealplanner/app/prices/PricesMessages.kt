package dev.tobins.mealplanner.app.prices

import dev.tobins.mealplanner.app.Msg
import dev.tobins.mealplanner.app.Plural

/** The texts of offers, regular prices, deal verdicts and "not this ingredient", in English and Dutch side by side. */
enum class PricesMsg(override val english: String, override val dutch: String) : Msg {
    // --- Offers and shopping: whether a deal is a good one, by its price history (issue 118). Dates are `19 Oct`,
    // amounts `€1.87/kg`; the deal texts stay as the store wrote them.
    VERDICT_CHIP_REAL("Real deal", "Echte aanbieding"),
    VERDICT_USUAL_ONLY("Usual deal", "Gewone aanbieding"),
    VERDICT_CHIP_WORSE("Usually cheaper", "Meestal goedkoper"),
    VERDICT_CHIP_NOT_A_DEAL("No real discount", "Geen echte korting"),
    VERDICT_CHIP_CHEAPER("Can be cheaper", "Kan goedkoper"),
    VERDICT_REAL_LOWEST("Real deal: lowest so far, {0}", "Echte aanbieding: laagste tot nu toe, {0}"),
    VERDICT_REAL_LOWEST_PACK("Real deal: lowest so far for this pack, {0}", "Echte aanbieding: laagste tot nu toe voor deze verpakking, {0}"),
    VERDICT_REAL_AS_LOW("Real deal: as low as before ({0})", "Echte aanbieding: even laag als eerder ({0})"),
    VERDICT_USUAL("Usual deal: usually {0}", "Gewone aanbieding: meestal {0}"),
    VERDICT_BETTER("Better than usual: now {0}; was {1} ({2})", "Beter dan meestal: nu {0}; was {1} ({2})"),
    VERDICT_WORSE("Usually cheaper: {0}, now {1} (last on {2})", "Meestal goedkoper: {0}, nu {1} (laatst op {2})"),
    VERDICT_ALWAYS("Always on offer: its normal price", "Altijd in de aanbieding: gewone prijs"),
    VERDICT_NOT_A_DEAL("No real discount: price went up first ({0} → {1})", "Geen echte korting: prijs eerst verhoogd ({0} → {1})"),
    VERDICT_CHEAPER_DEAL("Cheaper now with {0}: {1}", "Nu goedkoper met {0}: {1}"),
    VERDICT_CHEAPER_DEAL_ONLY("Cheaper now with {0}", "Nu goedkoper met {0}"),
    VERDICT_CHEAPER_PRICE("Cheaper now at {0}", "Nu goedkoper voor {0}"),
    VERDICT_NOT_COMPARABLE("Can't compare this deal", "Niet te vergelijken"),
    VERDICT_TOO_NEW("Too new to judge ({0} of {1} days)", "Nog te nieuw om te beoordelen ({0} van {1} dagen)"),
    VERDICT_TOO_FEW("Too few earlier offers to judge", "Te weinig eerdere aanbiedingen"),
    VERDICT_PACK_NOW("Cheaper per {0} now in the {1} pack: {2}", "Nu goedkoper per {0} in de verpakking van {1}: {2}"),
    VERDICT_OTHER_PACK_NOW("Cheaper per {0} now in another pack: {1}", "Nu goedkoper per {0} in een andere verpakking: {1}"),
    VERDICT_PACK_BEFORE("Cheapest per {0}: {1}, {2} ({3})", "Goedkoopst per {0}: {1}, {2} ({3})"),
    VERDICT_PACK_NAMED("{0} pack", "verpakking van {0}"),
    VERDICT_PACK_OTHER("another pack", "andere verpakking"),
    VERDICT_UNIT_KG("kg", "kg"),
    VERDICT_UNIT_LITRE("litre", "liter"),
    VERDICT_EACH("{0} each", "{0} per stuk"),
    VERDICT_SHARE("{0} of the regular price", "{0} van de gewone prijs"),
    VERDICT_ALSO("Also on offer: {0}", "Ook in de aanbieding: {0}"),
    VERDICT_RAISED("Price went up first: {0} → {1}", "Prijs eerst verhoogd: {0} → {1}"),
    VERDICT_ENDED_EARLY("Ended early", "Eerder gestopt"),
    VERDICT_EARLIER("Earlier offers", "Eerdere aanbiedingen"),
    OFFER_SHOW("Show more of this offer", "Toon meer van deze aanbieding"),
    OFFER_HIDE("Show less of this offer", "Toon minder van deze aanbieding"),

    // --- "Not this ingredient" (issue 316)
    EXCLUDED("“{0}” at {1} no longer counts as {2}", "“{0}” van {1} telt niet meer als {2}"),
    TAKEN_BACK("“{0}” at {1} counts as {2} again", "“{0}” van {1} telt weer als {2}"),
    EXCLUSION_FAILED("Could not save “Not this ingredient”: {0}", "“Niet dit ingrediënt” opslaan is niet gelukt: {0}"),

    // --- Offers
    FETCHING_OFFERS("Fetching the offers", "Aanbiedingen ophalen"),
    REFRESH_OFFERS("Refresh the offers", "Aanbiedingen vernieuwen"),
    REFRESH_FAILED("Could not refresh: {0}", "Vernieuwen is niet gelukt: {0}"),
    REGULAR_PRICES("Regular prices", "Reguliere prijzen"),
    OFFERS_LOAD_FAILED("Could not load the offers", "De aanbiedingen konden niet worden geladen"),
    MY_INGREDIENTS("My ingredients ({0})", "Mijn ingrediënten ({0})"),
    NO_OFFERS_YET("No offers fetched yet. Tap Refresh.", "Nog geen aanbiedingen opgehaald. Tik op Vernieuwen."),
    NO_OFFERS_MATCH("No offers match these filters.", "Geen aanbiedingen bij deze filters."),
    SOURCE_COULD_NOT_FETCH("{0}: could not fetch", "{0}: ophalen is niet gelukt"),
    SOURCE_LAST_FAILED("{0}: last fetch failed, offers from {1}", "{0}: laatste keer ophalen mislukt, aanbiedingen van {1}"),
    SOURCE_NOT_FETCHED("{0}: not fetched yet", "{0}: nog niet opgehaald"),
    SOURCE_WARNING("{0}: offers may be out of date", "{0}: aanbiedingen zijn mogelijk verouderd"),
    UNTIL("until {0}", "t/m {0}"),

    // --- Regular prices
    LOOKING_UP_PRICES("Looking up prices", "Prijzen opzoeken"),
    REFRESH_PRICES("Refresh the prices", "Prijzen vernieuwen"),
    PRICES_START_FAILED("Could not start: {0}", "Starten is niet gelukt: {0}"),
    PRICES_LOAD_FAILED("Could not load the prices", "De prijzen konden niet worden geladen"),
    PRICES_LOOKING("Looking up prices at Albert Heijn; this takes a few minutes.", "Prijzen bij Albert Heijn worden opgezocht; dit duurt een paar minuten."),
    PRICES_NONE("No prices looked up yet. Tap Refresh.", "Nog geen prijzen opgezocht. Tik op Vernieuwen."),
    PRICES_EXPLAIN(
        "The cheapest few products that match each ingredient set its price per kg. Offers are taken off this when costing recipes and choosing meals.",
        "De goedkoopste producten die bij een ingrediënt passen bepalen de prijs per kg. Aanbiedingen gaan hier nog vanaf bij het berekenen van receptkosten en het kiezen van maaltijden.",
    ),
    PRICE_PER_KG("{0}: {1}/kg", "{0}: {1}/kg"),
    PRICES_UNPRICED(
        "No product found, costed at a typical price: {0}.",
        "Geen product gevonden, berekend met een gangbare prijs: {0}.",
    ),

    // --- The web client's last resort and its own wording (browser, not phone)
    NO_OFFERS_YET_BROWSER("No offers fetched yet. Click Refresh.", "Nog geen aanbiedingen opgehaald. Klik op Vernieuwen."),
    PRICES_NONE_BROWSER("No prices looked up yet. Click Refresh.", "Nog geen prijzen opgezocht. Klik op Vernieuwen."),
    ;
}

/** The texts of offers, regular prices, deal verdicts and "not this ingredient" that depend on a count, in English and Dutch. */
enum class PricesPlural(
    override val englishOne: String,
    override val englishOther: String,
    override val dutchOne: String,
    override val dutchOther: String,
) : Plural {
    SOURCE_FETCHED(
        "{1}: {0} offer, fetched {2}", "{1}: {0} offers, fetched {2}",
        "{1}: {0} aanbieding, opgehaald {2}", "{1}: {0} aanbiedingen, opgehaald {2}",
    ),
    PRICES_SUMMARY(
        "{0} ingredient priced from Albert Heijn, looked up {1}.", "{0} ingredients priced from Albert Heijn, looked up {1}.",
        "{0} ingrediënt geprijsd via Albert Heijn, opgezocht op {1}.", "{0} ingrediënten geprijsd via Albert Heijn, opgezocht op {1}.",
    ),
    ;
}
