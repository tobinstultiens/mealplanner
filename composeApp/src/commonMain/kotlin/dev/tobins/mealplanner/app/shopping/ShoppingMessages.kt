package dev.tobins.mealplanner.app.shopping

import dev.tobins.mealplanner.app.Msg
import dev.tobins.mealplanner.app.Plural

/** The texts of the shopping list, its advice, shops and views, in English and Dutch side by side. */
enum class ShoppingMsg(override val english: String, override val dutch: String) : Msg {
    // --- Common
    RETRY("Retry", "Opnieuw"),

    // --- Settings: theme; shopping: views and progress
    ALL_TICKED_OFF("All {0} ticked off", "Alle {0} afgevinkt"),
    BADGE_OFFER("offer", "aanbieding"),
    OFFER_FROM("from {0}", "vanaf {0}"),
    OFFER_UNTIL("until {0}", "t/m {0}"),
    PACKS_TO_TAKE("{0} × {1}", "{0} × {1}"),
    PACKS_DEAL_NEEDS("{0} × {1} · deal needs {2}", "{0} × {1} · actie vraagt {2}"),

    // --- Foods in a slot (issue 358)
    FOOD_SOURCE("{0} on {1}", "{0} op {1}"),

    // --- Shopping
    TICK_FAILED("Could not save the tick: {0}", "Afvinken opslaan is niet gelukt: {0}"),
    TICK_AGAIN_NEEDS_CONNECTION(
        "Not saved: ticking this again for the new amount needs a connection",
        "Niet opgeslagen: dit opnieuw afvinken voor de nieuwe hoeveelheid kan alleen met een verbinding",
    ),
    BACK_ON_LIST("{0} back on the list", "{0} staat weer op de lijst"),
    TICKED_OFF("{0} ticked off", "{0} afgevinkt"),
    EXTRA_FIELD("Add an item of your own", "Voeg een eigen product toe"),
    EXTRA_ADD("Add", "Toevoegen"),
    EXTRA_REMOVE("Remove {0} from the list", "Haal {0} van de lijst"),
    EXTRA_NEEDS_CONNECTION("Adding and removing items needs a connection", "Producten toevoegen of weghalen kan alleen met een verbinding"),
    EXTRA_ADD_FAILED("Could not add the item: {0}", "Het product toevoegen is niet gelukt: {0}"),
    EXTRA_REMOVE_FAILED("Could not remove the item: {0}", "Het product weghalen is niet gelukt: {0}"),
    SHOPPING_LOAD_FAILED("Could not load the shopping list", "De boodschappenlijst kon niet worden geladen"),
    NOTHING_TO_BUY("Nothing to buy: no meals are planned for this week.", "Niets te kopen: er zijn deze week geen maaltijden gepland."),
    NOTHING_TO_BUY_PREPPED(
        "Nothing to buy: this week's prepped meals were bought with the list of the week of {0}.",
        "Niets te kopen: de geprepte maaltijden van deze week zijn gekocht met de lijst van de week van {0}.",
    ),
    OFFLINE_LIST(
        "Offline: the list as of {0}. Ticks are saved and sent later.",
        "Offline: de lijst van {0}. Afgevinkte producten worden bewaard en later verstuurd.",
    ),
    TAP_TO_SHOP("Tap to shop here", "Tik om hier te winkelen"),
    FREE_TO_BUY("Free to buy wherever you go", "Bij elke winkel te koop"),
    CHEAPER_ELSEWHERE("Cheaper elsewhere ({0})", "Goedkoper elders ({0})"),
    TO_TASTE("To taste (check you have them): {0}", "Naar smaak (kijk of je ze in huis hebt): {0}"),
    STAPLES_NOTE("Staples this plan uses (not on the list): {0}", "Basisproducten die dit plan gebruikt (niet op de lijst): {0}"),
    SHOPPING_MODE("Shopping mode", "Winkelmodus"),
    SHOPPING_MODE_HINT("Big rows, the screen stays on, ticked items step aside", "Grote rijen, het scherm blijft aan, afgevinkte producten gaan opzij"),
    SECTION_DONE("✓ {0} done", "✓ {0} klaar"),
    DONE_COUNT("Done ({0})", "Klaar ({0})"),
    STORES_I_SHOP_AT("Stores I shop at", "Winkels waar ik kom"),
    SHOPS_NOTE(
        "Your plans count only these stores' offers. Savings are measured against Albert Heijn's regular prices.",
        "Je weekplan telt alleen de aanbiedingen van deze winkels. Besparingen zijn vergeleken met de reguliere prijzen van Albert Heijn.",
    ),
    SHOPS_SAVE_FAILED("Could not save the stores: {0}", "Winkels opslaan is niet gelukt: {0}"),
    STORE_SAVING_HINT("Saves about {0} · tap to shop here", "Bespaart ongeveer {0} · tik om hier te winkelen"),
    SHOPPING_AT("Shopping at {0}", "Boodschappen bij {0}"),
    ITS_OFFERS("Its offers and everything without one", "De aanbiedingen daar en alles zonder aanbieding"),
    VISIT_SAVING(
        "Its offers save about {0}, measured against Albert Heijn's regular prices",
        "De aanbiedingen daar besparen ongeveer {0}, vergeleken met de reguliere prijzen van Albert Heijn",
    ),
    ALL_STORES("All stores", "Alle winkels"),
    ON_HAND_MAYBE("{0}  (on hand?)", "{0}  (in huis?)"),
    ON_HAND_AMOUNT("{0} on hand", "{0} in huis"),
    ON_HAND_BUY("{0} on hand, buy {1}", "{0} in huis, koop {1}"),
    ON_HAND_ENOUGH("{0} on hand: enough", "{0} in huis: genoeg"),
    ON_HAND_BUY_TO_COME("{0} on hand, buy {1} for the meals to come", "{0} in huis, koop {1} voor de maaltijden die nog komen"),
    ON_HAND_ENOUGH_TO_COME("{0} on hand: enough for the meals to come", "{0} in huis: genoeg voor de maaltijden die nog komen"),
    FOR_MEALS_TO_COME("For the meals to come", "Voor de maaltijden die nog komen"),
    NOTHING_LEFT_HINT("On hand, or the meals that need it are done", "In huis, of de maaltijden die het nodig hebben zijn klaar"),
    NOTHING_TO_COME("Nothing to buy: the meals that need it are done", "Niets te kopen: de maaltijden die het nodig hebben zijn klaar"),
    NOTHING_LEFT_TO_BUY("Nothing left to buy", "Niets meer te kopen"),
    SHOW_DEAL("Show the deal", "Toon de aanbieding"),
    FOR_THIS_WEEK("For this week", "Voor deze week"),
    BUY_AHEAD_TITLE("Buy ahead for next week (on offer now)", "Nu kopen voor volgende week (nu in de aanbieding)"),
    BUY_AHEAD_HINT(
        "It keeps, and the offer ends this week. Ticked, it counts as on hand for next week's plan.",
        "Het blijft goed en de aanbieding stopt deze week. Afgevinkt telt het als in huis voor het plan van volgende week.",
    ),
    BUY_AHEAD_HINT_PROVISIONAL(
        "Next week is not planned yet: these are dinners it could have. It keeps, and the offer ends this week. Ticked, next week's plan prefers meals that use it.",
        "Volgende week is nog niet gepland: dit zijn maaltijden die het kan krijgen. Het blijft goed en de aanbieding stopt deze week. Afgevinkt kiest het plan van volgende week liever maaltijden die het gebruiken.",
    ),
    SUGGESTED_MEALS("for e.g. {0}", "bijv. voor {0}"),
    MORE_NEEDED("{0} more ({1} bought)", "{0} extra nodig ({1} gekocht)"),
    NO_LONGER_NEEDED_TITLE("Bought, no longer needed", "Gekocht, niet meer nodig"),
    NO_LONGER_NEEDED_HINT(
        "The plan changed after you bought these. Use it up and the planner prefers meals that use it; Remove takes it off.",
        "Het plan veranderde nadat je dit kocht. Opmaken: het plan kiest liever maaltijden die het gebruiken; Verwijderen haalt het weg.",
    ),
    USE_IT_UP("Use it up", "Opmaken"),
    USE_UP_FAILED("Could not put it on hand: {0}", "In huis zetten is niet gelukt: {0}"),
    STOCK_UP_TITLE("Stock up: on offer now", "Inslaan: nu in de aanbieding"),
    STOCK_UP_HINT(
        "Keeps for months; no meal on this list needs it. One pack each; ticked, it goes in the pantry.",
        "Blijft maanden goed; geen maaltijd op deze lijst heeft het nodig. Eén pak per product; afgevinkt gaat het naar de voorraad.",
    ),
    ADVICE_SAVING("Saves about {0}.", "Bespaart ongeveer {0}."),
    HIDE_ADVICE("Hide", "Verbergen"),
    SHOW_ADVICE("Show", "Tonen"),
    BUY_AHEAD_HIDDEN("Buying ahead hidden for this week", "Vooruit kopen verborgen voor deze week"),
    STOCK_UP_HIDDEN("Stocking up hidden for this week", "Inslaan verborgen voor deze week"),
    ADVICE_CHANGE_FAILED("Could not change the list: {0}", "De lijst aanpassen is niet gelukt: {0}"),

    // --- The web client's last resort and its own wording (browser, not phone)
    TAP_TO_SHOP_BROWSER("Click to shop here", "Klik om hier te winkelen"),
    STORE_SAVING_HINT_BROWSER("Saves about {0} · click to shop here", "Bespaart ongeveer {0} · klik om hier te winkelen"),
    ;
}

/** The texts of the shopping list, its advice, shops and views that depend on a count, in English and Dutch. */
enum class ShoppingPlural(
    override val englishOne: String,
    override val englishOther: String,
    override val dutchOne: String,
    override val dutchOther: String,
) : Plural {
    PACK_PIECES("{0} piece", "{0} pieces", "{0} stuk", "{0} stuks"),
    STORE_ITEMS_ON_OFFER(
        "{1} · {0} item on offer", "{1} · {0} items on offer",
        "{1} · {0} product in de aanbieding", "{1} · {0} producten in de aanbieding",
    ),
    SECTIONS_TO_GO(
        "{1} of {2} · {0} section to go", "{1} of {2} · {0} sections to go",
        "{1} van {2} · nog {0} afdeling", "{1} van {2} · nog {0} afdelingen",
    ),
    ANY_STORE_ITEMS("Any store · {0} item", "Any store · {0} items", "Elke winkel · {0} product", "Elke winkel · {0} producten"),
    TICKS_NOT_SENT(
        "{0} tick has not reached the server yet; it is sent again", "{0} ticks have not reached the server yet; they are sent again",
        "{0} vinkje heeft de server nog niet bereikt; het wordt opnieuw verstuurd", "{0} vinkjes hebben de server nog niet bereikt; ze worden opnieuw verstuurd",
    ),
    NOTHING_LEFT_ITEMS(
        "Covered · {0} item", "Covered · {0} items",
        "Gedekt · {0} product", "Gedekt · {0} producten",
    ),
    ;
}
