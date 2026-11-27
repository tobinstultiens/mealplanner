package dev.tobins.mealplanner.app.ui

import dev.tobins.mealplanner.app.Msg
import dev.tobins.mealplanner.app.Plural

/** The texts of what more than one feature shows, and the building blocks (slots, buttons, common words), in English and Dutch side by side. */
enum class CommonMsg(override val english: String, override val dutch: String) : Msg {
    // --- Common
    BACK("Back", "Terug"),
    MORE("More", "Meer"),
    DETAILS("Details", "Details"),
    CLOSE("Close", "Sluiten"),
    LOADING("Loading…", "Laden…"),
    TRY_AGAIN("Try again", "Opnieuw proberen"),
    SERVER_SETTINGS("Server settings", "Serverinstellingen"),
    CANCEL("Cancel", "Annuleren"),
    SAVE("Save", "Opslaan"),
    ADD("Add", "Toevoegen"),
    REMOVE("Remove", "Verwijderen"),
    UNDO("Undo", "Ongedaan maken"),

    // --- Dates and times
    WHEN_TODAY("today {0}", "vandaag {0}"),
    WHEN_YESTERDAY("yesterday {0}", "gisteren {0}"),

    // --- Errors made by the app (the server's own messages are shown as they come)
    NO_CONNECTION("the server could not be reached", "de server is niet bereikbaar"),
    WENT_WRONG("something went wrong", "er ging iets mis"),
    UNREADABLE_ANSWER("the server's answer could not be read", "het antwoord van de server was niet te lezen"),

    // --- Today
    ALSO_EATEN("Also eaten", "Ook gegeten"),
    KCAL_PROTEIN("{0} kcal · {1} g protein", "{0} kcal · {1} g eiwit"),
    KCAL_PROTEIN_FIBRE("{0} kcal · {1} g protein · {2} g fibre", "{0} kcal · {1} g eiwit · {2} g vezels"),

    // --- Formatted facts (recipes, offers, plans)
    OFFER_WAS("(was {0})", "(was {0})"),
    ON_OFFER("on offer", "in de aanbieding"),
    NO_AMOUNTS_YET("No amounts yet", "Nog geen hoeveelheden"),
    MISSING_AMOUNTS("Missing amounts", "Hoeveelheden ontbreken"),

    // --- "Not this ingredient" (issue 316)
    NOT_THIS_INGREDIENT("Not this ingredient", "Niet dit ingrediënt"),

    // --- Settings
    SETTINGS("Settings", "Instellingen"),

    // --- Plan
    WEEK_OF("Week of {0}", "Week van {0}"),
    PREVIOUS_WEEK("Previous week", "Vorige week"),
    NEXT_WEEK("Next week", "Volgende week"),
    OPEN_PROFILE("Open profile", "Open profiel"),

    // The fresh ingredients the week leaves over (issue 244): {0} is the euros, {1} the list; MORE ends a long list.
    WEEK_REST("About {0} of fresh ingredients left over: {1}", "Ongeveer {0} aan verse ingrediënten over: {1}"),
    WEEK_REST_MORE("{0} and {1} more", "{0} en nog {1}"),

    // --- Foods in a slot (issue 358)
    FOOD_UNCOUNTED("{0} · not counted", "{0} · telt niet mee"),

    // --- One batch eaten partly as meals and partly as sides (issue 269)
    COOK_PREP("Cook {0}: {1} now, {2} for the prep's later meals", "Kook {0}: {1} nu, {2} voor de latere maaltijden van de prep"),
    NOTHING_TO_COOK_PREPPED("Prepped on {0}: nothing to cook", "Geprept op {0}: niets te koken"),
    KEEPS_NOT("not prepped", "niet geprept"),

    // --- Recipe editor
    NAME("Name", "Naam"),
    FOUND_NO_NUTRITION("No nutrition data", "Geen voedingswaarden"),
    EDITOR_ADD_FAILED("Could not add the food: {0}", "Het voedingsmiddel toevoegen is niet gelukt: {0}"),

    // --- A product with the values of its label and named portions (issue 351)
    SAVE_FAILED("Could not save: {0}", "Opslaan is niet gelukt: {0}"),

    // --- Offers
    OFFERS("Offers", "Aanbiedingen"),

    // --- Pantry
    PANTRY("Pantry", "Voorraad"),
    SEARCH_INGREDIENTS("Search ingredients", "Zoek ingrediënten"),

    // --- Recipes
    LINK_INGREDIENTS("Link ingredients", "Ingrediënten koppelen"),

    // --- Recipe detail
    COOK_SERVINGS("Cook {0}", "Kook {0}"),
    COOK_WITH_LEFTOVERS(
        "Cook {0}: {1} now, {2} for tomorrow's lunch",
        "Kook {0}: {1} nu, {2} voor de lunch van morgen",
    ),
    NOTHING_TO_COOK_LEFTOVERS(
        "Leftovers of yesterday's dinner: nothing to cook",
        "Restjes van het eten van gisteren: niets te koken",
    ),
    NOTHING_TO_COOK_SKIPPED("Skipped: nothing to cook", "Overgeslagen: niets te koken"),

    // --- Errors made by the app (the server's own messages are shown as they come)
    SERVER_ANSWERED("The server answered {0} {1}", "De server antwoordde met {0} {1}"),
    UNKNOWN_ERROR("Unknown error", "Onbekende fout"),
    NO_ANSWER("The server did not answer in time", "De server antwoordde niet op tijd"),

    // --- Progress
    PROFILE_AND_TARGETS("Profile and targets", "Profiel en doelen"),

    // --- Leaving a form with unsaved changes
    DISCARD_CHANGES_TITLE("Discard your changes?", "Wijzigingen weggooien?"),
    DISCARD_CHANGES_TEXT("What you changed has not been saved.", "Wat je hebt gewijzigd is nog niet opgeslagen."),
    KEEP_EDITING("Keep editing", "Blijven bewerken"),
    DISCARD("Discard", "Weggooien"),

    // --- Link ingredients
    INGREDIENTS_LOAD_FAILED("Could not load ingredients", "De ingrediënten konden niet worden geladen"),
    ;
}

/** The texts of what more than one feature shows, and the building blocks (slots, buttons, common words) that depend on a count, in English and Dutch. */
enum class CommonPlural(
    override val englishOne: String,
    override val englishOther: String,
    override val dutchOne: String,
    override val dutchOther: String,
) : Plural {
    SERVINGS("{0} serving", "{0} servings", "{0} portie", "{0} porties"),
    DAYS("{0} day", "{0} days", "{0} dag", "{0} dagen"),

    /** For an amount that may be fractional: the count only picks the form, `{1}` is the amount as written. */
    SERVINGS_AMOUNT("{1} serving", "{1} servings", "{1} portie", "{1} porties"),
    INGREDIENTS_TO_LINK("{0} ingredient to link", "{0} ingredients to link", "{0} ingrediënt om te koppelen", "{0} ingrediënten om te koppelen"),
    ;
}
