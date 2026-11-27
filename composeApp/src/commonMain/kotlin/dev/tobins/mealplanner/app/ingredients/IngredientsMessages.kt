package dev.tobins.mealplanner.app.ingredients

import dev.tobins.mealplanner.app.Msg
import dev.tobins.mealplanner.app.Plural

/** The texts of the ingredient mapping, the pantry, products and foods, in English and Dutch side by side. */
enum class IngredientsMsg(override val english: String, override val dutch: String) : Msg {
    // --- Foods logged as eaten outside the plan (issue 362)
    LOG_FOOD_VALUES_CHANGED(
        "The values of {0} have changed since it was logged. It keeps what it counted then; another amount counts by its values of now.",
        "De waarden van {0} zijn veranderd sinds het is gelogd. Het houdt wat het toen telde; een andere hoeveelheid telt met de waarden van nu.",
    ),

    // --- Foods in a slot (issue 358)
    FOOD_SAVE_FAILED("Could not save the food: {0}", "Het voedingsmiddel opslaan is niet gelukt: {0}"),
    FOOD_REMOVE_FAILED("Could not take the food out: {0}", "Het voedingsmiddel weghalen is niet gelukt: {0}"),

    // --- A product with the values of its label and named portions (issue 351)
    PRODUCTS("Products", "Producten"),
    PRODUCTS_INTRO(
        "A product of a brand, such as a protein bar, counted by the values on its label, with portions to use as a unit in recipes.",
        "Een product van een merk, zoals een eiwitreep, dat telt met de waarden op het etiket, met porties om als eenheid in recepten te gebruiken.",
    ),
    NEW_PRODUCT("New product", "Nieuw product"),
    EDIT_PRODUCT("Edit product", "Product bewerken"),
    PRODUCT_SUMMARY("{0} kcal per {1}", "{0} kcal per {1}"),
    PRODUCT_PORTIONS("portions: {0}", "porties: {0}"),
    PRODUCT_VALUES_PER("Values per", "Waarden per"),
    PRODUCT_VALUES_HINT(
        "As on the label. Leave empty what the label does not give: it stays unknown, not 0.",
        "Zoals op het etiket. Laat leeg wat het etiket niet geeft: dat blijft onbekend, niet 0.",
    ),
    PRODUCT_NAME_NEEDED("Needed: the product's name", "Nodig: de naam van het product"),
    PRODUCT_NEEDS_ENERGY("Needed: the energy in kcal from the label", "Nodig: de energie in kcal van het etiket"),
    PRODUCT_NOT_A_NUMBER("Not a number", "Geen getal"),
    PRODUCT_NEGATIVE("May not be negative", "Mag niet negatief zijn"),
    LABEL_ENERGY("Energy", "Energie"),
    LABEL_FAT("Fat", "Vet"),
    LABEL_SATURATED_FAT("of which saturated", "waarvan verzadigd"),
    LABEL_CARBS("Carbohydrates", "Koolhydraten"),
    LABEL_SUGARS("of which sugars", "waarvan suikers"),
    LABEL_FIBRE("Fibre", "Vezels"),
    LABEL_PROTEIN("Protein", "Eiwitten"),
    LABEL_SALT("Salt", "Zout"),
    PORTIONS("Portions", "Porties"),
    PORTIONS_HINT(
        "Each is a unit of this product in the recipe editor, such as bar = 45 g.",
        "Elk is een eenheid van dit product in de recepteditor, zoals reep = 45 g.",
    ),
    PORTION_NAME("Name", "Naam"),
    PORTION_SIZE("Size", "Grootte"),
    PORTION_UNIT("In {0}; tap for the other", "In {0}; tik voor de andere"),
    ADD_PORTION("Add a portion", "Portie toevoegen"),
    REMOVE_PORTION("Remove the portion {0}", "De portie {0} verwijderen"),
    PORTION_NAME_NEEDED("Needed: a name for the portion", "Nodig: een naam voor de portie"),
    PORTION_NAME_TAKEN("Another portion has this name", "Een andere portie heeft deze naam"),
    PORTION_NAME_IS_UNIT("“{0}” is a unit already: give the portion another name", "“{0}” is al een eenheid: geef de portie een andere naam"),
    PORTION_NAME_BAD("A portion's name cannot hold | or a line break", "De naam van een portie mag geen | of regeleinde bevatten"),
    PORTION_SIZE_NEEDED("Needed: its size in {0}", "Nodig: de grootte in {0}"),
    PORTION_SIZE_ZERO("Must be more than 0", "Moet meer dan 0 zijn"),
    REMOVE_PORTIONS_TITLE("Remove {0}?", "{0} verwijderen?"),
    REMOVE_PORTIONS_TEXT(
        "Recipes with a line in {0} will no longer count that line, until a portion of that name is added again.",
        "Recepten met een regel in {0} tellen die regel niet meer mee, tot er weer een portie met die naam is.",
    ),
    REMOVE_AND_SAVE("Remove and save", "Verwijderen en opslaan"),
    KEEP_PORTIONS("Keep it", "Behouden"),
    PRODUCT_SAVED("Saved {0}", "{0} opgeslagen"),
    PRODUCT_VALUES_FROM("Values from {0}", "Waarden van {0}"),
    PRODUCT_CHANGED_BY_HAND("{0}, changed by hand", "{0}, met de hand aangepast"),
    OFF_LICENCE("Open Food Facts' data is under the Open Database Licence: {0}", "De gegevens van Open Food Facts vallen onder de Open Database License: {0}"),
    LOOKUP_TITLE("Look up at AH", "Opzoeken bij AH"),
    LOOKUP_FIELD("Name or barcode", "Naam of streepjescode"),
    LOOKUP_HINT(
        "A barcode AH does not know is looked up at Open Food Facts. The form is filled in: check it against the pack, then save.",
        "Een streepjescode die AH niet kent, wordt opgezocht bij Open Food Facts. Het formulier wordt ingevuld: vergelijk het met de verpakking en sla dan op.",
    ),
    LOOKUP("Look up", "Opzoeken"),
    LOOKUP_BUSY("Looking up…", "Opzoeken…"),
    SCAN("Scan barcode", "Streepjescode scannen"),
    SCAN_HELP("Hold the barcode inside the frame.", "Houd de streepjescode in het kader."),
    SCAN_CLOSE("Close the scanner", "Scanner sluiten"),
    SCAN_TORCH("Torch", "Zaklamp"),
    SCAN_TORCH_ON("On", "Aan"),
    SCAN_TORCH_OFF("Off", "Uit"),
    SCAN_SETTINGS("Open the app's settings", "Instellingen van de app openen"),
    SCAN_REFUSED(
        "The camera is not allowed, so nothing can be scanned. Type the digits instead, or allow the camera for this app in the phone's settings (Settings, Apps, Meal planner, Permissions).",
        "De camera is niet toegestaan, dus er kan niets gescand worden. Typ de cijfers in, of sta de camera voor deze app toe in de instellingen van de telefoon (Instellingen, Apps, Meal planner, Rechten).",
    ),
    SCAN_UNAVAILABLE("The camera could not be opened. Type the digits instead.", "De camera kon niet geopend worden. Typ de cijfers in."),
    LOOKUP_TOO_SHORT("Type at least 2 characters of a name, or a barcode.", "Typ minstens 2 tekens van een naam, of een streepjescode."),
    PRODUCT_NAME_TAKEN("There is already an ingredient with this name", "Er is al een ingrediënt met deze naam"),
    LOOKUP_FAILED("Could not look it up: {0}", "Opzoeken is niet gelukt: {0}"),
    LOOKUP_NOTHING("Nothing found at AH for “{0}”.", "Niets gevonden bij AH voor “{0}”."),
    LOOKUP_NOT_FOUND("No product found for it.", "Geen product gevonden."),
    LOOKUP_AH_FAILED("AH did not answer.", "AH gaf geen antwoord."),
    LOOKUP_AH_NO_TABLE("AH gives no values per 100 g or 100 ml for it.", "AH geeft er geen waarden per 100 g of 100 ml voor."),
    LOOKUP_OFF_FAILED("Open Food Facts did not answer.", "Open Food Facts gaf geen antwoord."),
    LOOKUP_OFF_BUSY("Open Food Facts was asked too often this minute: try again in a moment.", "Open Food Facts is deze minuut te vaak gevraagd: probeer het zo nog eens."),
    LOOKUP_OFF_NOT_CONFIGURED("Open Food Facts is not set up on this server, so it was not asked.", "Open Food Facts is niet ingesteld op deze server en is dus niet gevraagd."),
    LOOKUP_NO_VALUES("{0} gives no values per 100 g or 100 ml for it.", "{0} geeft er geen waarden per 100 g of 100 ml voor."),
    LOOKUP_BASIS_UNKNOWN(
        "{0} does not say whether the values are per 100 g or per 100 ml: choose below.",
        "{0} zegt niet of de waarden per 100 g of per 100 ml zijn: kies hieronder.",
    ),
    PRODUCT_BASIS_NEEDED("Needed: per 100 g or per 100 ml, as on the label", "Nodig: per 100 g of per 100 ml, zoals op het etiket"),
    LOOKUP_OTHER_PROBLEM("A source answered in a way this app does not know.", "Een bron gaf een antwoord dat deze app niet kent."),
    LOOKUP_TYPE_VALUES("Type the values from the label.", "Typ de waarden van het etiket over."),
    LOOKUP_UNREAD("Could not be read: {0}.", "Niet te lezen: {0}."),
    LOOKUP_NOT_GIVEN("Not given: {0}.", "Niet gegeven: {0}."),
    LOOKUP_ONE_OF_PACK("The values are of one of the {0} in the pack.", "De waarden zijn van één van de {0} in de verpakking."),
    LOOKUP_PACK_OF("pack of {0}", "verpakking van {0}"),
    NUTRIENT_SATURATED_FAT("saturated fat", "verzadigd vet"),
    NUTRIENT_SUGARS("sugars", "suikers"),
    PORTION_PACK("pack", "verpakking"),
    PORTION_ITEM("item", "los stuk"),

    // --- Pantry
    PANTRY_LOAD_FAILED("Could not load the pantry", "De voorraad kon niet worden geladen"),
    PANTRY_SUMMARY(
        "Staples: {0} (always at home, never on the shopping list) · On hand: {1} (to use up; the planner favours them)",
        "Basisproducten: {0} (altijd in huis, nooit op je boodschappenlijst) · In huis: {1} (op te maken; de planner geeft ze voorrang)",
    ),
    STAPLE("Staple", "Basis"),
    ON_HAND("On hand", "In huis"),
    PANTRY_LEFT("{0} left", "nog {0}"),
    PANTRY_FROM_WEEK("for the week of {0}", "voor de week van {0}"),
    PANTRY_FROM_WEEKS("for the weeks of {0}", "voor de weken van {0}"),

    // --- Link ingredients
    FOOD_SUMMARY("{0} · {1} kcal, {2} g protein per {3}", "{0} · {1} kcal, {2} g eiwit per {3}"),
    ALL_LINKED("Every ingredient with an amount is linked.", "Elk ingrediënt met een hoeveelheid is gekoppeld."),
    NEEDS_DECISION(
        "Ingredients that match a food exactly are linked automatically. These need your decision; most-used first.",
        "Ingrediënten die precies bij een voedingsmiddel passen worden automatisch gekoppeld. Voor deze moet jij kiezen; de meest gebruikte staan bovenaan.",
    ),
    NUTRITION_DATA("Nutrition data: {0}", "Voedingsgegevens: {0}"),
    SECTION_CHANGED("{0} is now under {1}", "{0} staat nu bij {1}"),
    SECTION_CHANGE_FAILED("Could not change the section: {0}", "De afdeling kon niet worden aangepast: {0}"),
    KEEPS_CHANGE_FAILED(
        "{0} was moved, but whether it keeps could not be saved: {1}",
        "{0} is verplaatst, maar of het goed blijft kon niet worden opgeslagen: {1}",
    ),
    LINKED("Linked “{0}” to {1}", "“{0}” gekoppeld aan {1}"),
    LINK_FAILED("Could not link: {0}", "Koppelen is niet gelukt: {0}"),
    LINKED_NO_WEIGHT("Linked to {0}, but its weight per piece is unknown.", "Gekoppeld aan {0}, maar het gewicht per stuk is onbekend."),
    SET_PIECE_WEIGHT("Set weight per piece", "Gewicht per stuk instellen"),
    SEARCH_DOTS("Search…", "Zoeken…"),
    LINK_TITLE("Link “{0}”", "“{0}” koppelen"),
    LINKED_TO("Linked to {0}.", "Gekoppeld aan {0}."),
    CHOOSE_OTHER_FOOD("Choose another food", "Kies een ander voedingsmiddel"),
    SEARCH_FOODS("Search foods (English or Dutch)", "Zoek voedingsmiddelen (Engels of Nederlands)"),
    NO_FOODS("No foods found.", "Geen voedingsmiddelen gevonden."),
    GRAMS_PER_PIECE("Grams per piece", "Gram per stuk"),
    GRAMS_PER_PIECE_HINT(
        "Recipes count this in pieces; without a weight it cannot be counted.",
        "Recepten tellen dit in stuks; zonder gewicht kan het niet worden meegeteld.",
    ),
    LINK("Link", "Koppelen"),
    SHOP_SECTIONS("Shop sections", "Winkelafdelingen"),
    SECTIONS_INTRO("The shopping list follows the route through the shop.", "De boodschappenlijst volgt de route door de winkel."),
    SECTIONS_ALL_SET("Every ingredient has a section.", "Elk ingrediënt heeft een afdeling."),
    SECTIONS_SOME_OTHER(
        "Ingredients without a section go last, under Other; these are listed below.",
        "Ingrediënten zonder afdeling komen als laatste, onder Overig; die staan hieronder.",
    ),
    SECTIONS_TAP("Tap an ingredient to put it in another section.", "Tik op een ingrediënt om het onder een andere afdeling te zetten."),
    FIND_INGREDIENT("Find an ingredient", "Zoek een ingrediënt"),
    SET_BY_HAND("{0} · set by hand", "{0} · zelf ingesteld"),
    SECTION_FOR("Section for {0}", "Afdeling voor {0}"),
    AUTOMATIC("Automatic: {0}", "Automatisch: {0}"),
    KEEPS_TITLE("Keeps until next week?", "Blijft het goed tot volgende week?"),
    KEEPS_YES("Yes (in the freezer, for meat and fish)", "Ja (in de vriezer, voor vlees en vis)"),
    KEEPS_NO("No", "Nee"),
    KEEPS_HINT("Only what keeps is suggested for buying ahead.", "Alleen wat goed blijft, wordt voorgesteld om vooruit te kopen."),
    KEEPS_SHORT("keeps", "blijft goed"),

    // --- The web client's last resort and its own wording (browser, not phone)
    SECTIONS_TAP_BROWSER("Click an ingredient to put it in another section.", "Klik op een ingrediënt om het onder een andere afdeling te zetten."),
    ;
}

/** The texts of the ingredient mapping, the pantry, products and foods that depend on a count, in English and Dutch. */
enum class IngredientsPlural(
    override val englishOne: String,
    override val englishOther: String,
    override val dutchOne: String,
    override val dutchOther: String,
) : Plural {
    USED_IN_RECIPES("Used in {0} recipe, e.g. {1}", "Used in {0} recipes, e.g. {1}", "Gebruikt in {0} recept, bijv. {1}", "Gebruikt in {0} recepten, bijv. {1}"),
    ;
}
