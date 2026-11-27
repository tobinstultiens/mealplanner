package dev.tobins.mealplanner.app.recipes

import dev.tobins.mealplanner.app.Msg
import dev.tobins.mealplanner.app.Plural

/** The texts of the recipe bank, lists, editor, cook mode and generation, in English and Dutch side by side. */
enum class RecipesMsg(override val english: String, override val dutch: String) : Msg {
    // --- Tabs
    TAB_RECIPES("Recipes", "Recepten"),

    // --- One batch eaten partly as meals and partly as sides (issue 269)
    IN_THIS_MEAL("In this meal: {0}", "In deze maaltijd: {0}"),
    KEEPS_ONCE_MADE("Keeps once made", "Blijft goed na het maken"),
    KEEPS_IN_A_BOX("In a box: {0}", "In een bakje: {0}"),
    KEEPS_VACUUM("Vacuum sealed: {0}", "Vacuüm: {0}"),
    KEEPS_FAILED("Could not save how long it keeps.", "Kon niet opslaan hoe lang het goed blijft."),

    // --- Generate a recipe
    GENERATE_RECIPE("Generate a recipe", "Recept laten maken"),
    TO_MY_WISH("To my wish", "Naar mijn wens"),
    SURPRISE_ME("Surprise me", "Verras me"),
    GEN_HINT_WISH("A recipe that fits your targets and what you ask for.", "Een recept dat past bij je doelen en je wens."),
    GEN_HINT_SURPRISE(
        "Something unlike your recent meals, using what is at home and on offer.",
        "Iets anders dan je laatste maaltijden, met wat je in huis hebt en wat in de aanbieding is.",
    ),
    WISH_EXAMPLE("Wish (e.g. Korean, spicy, quick)", "Wens (bijv. Koreaans, pittig, snel)"),
    WISH_OPTIONAL("Wish (optional)", "Wens (optioneel)"),
    WRITING_RECIPE("Writing the recipe; this can take a minute.", "Het recept wordt geschreven; dit kan een minuut duren."),
    GEN_OFF(
        "This server does not write recipes.",
        "Deze server maakt geen recepten.",
    ),
    GEN_NEEDS_PROFILE("Set up your profile first; recipes are sized to your targets.", "Stel eerst je profiel in; recepten worden op je doelen afgestemd."),
    GEN_FAILED("Could not generate: {0}", "Het maken is niet gelukt: {0}"),
    GENERATE("Generate", "Maak recept"),

    // --- Recipe editor
    NEW_RECIPE("New recipe", "Nieuw recept"),
    EDIT_RECIPE("Edit recipe", "Recept bewerken"),
    SERVINGS("Servings", "Porties"),
    PLAN_AS("Plan as", "Plannen als"),
    INGREDIENTS("Ingredients", "Ingrediënten"),
    STEPS_OPTIONAL("Steps (optional)", "Stappen (optioneel)"),
    EDITOR_PER_SERVING("Per serving: {0}", "Per portie: {0}"),
    EDITOR_NO_TOTALS("Add ingredients with an amount to see what a serving has.", "Voeg ingrediënten met een hoeveelheid toe om te zien wat een portie bevat."),
    EDITOR_NO_SERVINGS("Give the number of servings (1 to 100) to see what a serving has.", "Vul het aantal porties in (1 tot 100) om te zien wat een portie bevat."),
    EDITOR_NO_ROWS("No ingredients yet. Search for one below, or paste lines.", "Nog geen ingrediënten. Zoek er hieronder een, of plak regels."),
    EDITOR_ADD_INGREDIENT("Add an ingredient", "Ingrediënt toevoegen"),
    EDITOR_SEARCH_HINT("Your ingredients and NEVO foods, in English or Dutch", "Je ingrediënten en NEVO-voedingsmiddelen, in het Nederlands of Engels"),
    EDITOR_NOTHING_FOUND("No ingredient or food found.", "Geen ingrediënt of voedingsmiddel gevonden."),
    EDITOR_SEARCH_FAILED("Could not search the NEVO foods: {0}", "Zoeken in de NEVO-voedingsmiddelen is niet gelukt: {0}"),
    FOUND_PER_100("{0} kcal · {1} g protein per {2}", "{0} kcal · {1} g eiwit per {2}"),
    FOUND_NEW("New from NEVO", "Nieuw uit NEVO"),
    EDITOR_LOOKUP_FAILED("Could not look the pasted lines up: {0}", "De geplakte regels opzoeken is niet gelukt: {0}"),
    EDITOR_AMOUNT("Amount", "Hoeveelheid"),
    EDITOR_UNIT("Unit", "Eenheid"),
    EDITOR_AMOUNT_UNREADABLE("Not an amount", "Geen hoeveelheid"),
    EDITOR_REMOVE_ROW("Remove {0}", "{0} verwijderen"),
    ROW_COUNTED("{0} · {1} · {2} kcal", "{0} · {1} · {2} kcal"),
    ROW_NO_INGREDIENT("Not counted: tap to choose the ingredient", "Telt niet mee: tik om het ingrediënt te kiezen"),
    ROW_NO_INGREDIENT_BROWSER("Not counted: click to choose the ingredient", "Telt niet mee: klik om het ingrediënt te kiezen"),
    ROW_NOT_COUNTED("{0} · {1}", "{0} · {1}"),
    ROW_NO_AMOUNT("no amount, not counted", "geen hoeveelheid, telt niet mee"),
    ROW_NO_NUTRITION("no nutrition data, not counted", "geen voedingswaarden, telt niet mee"),
    ROW_WEIGHT_UNKNOWN("weight unknown, not counted", "gewicht onbekend, telt niet mee"),
    ROW_PORTION_GONE("the portion {0} is gone, not counted", "de portie {0} bestaat niet meer, telt niet mee"),
    EDITOR_CHOOSE_INGREDIENT("Choose the ingredient", "Kies het ingrediënt"),
    EDITOR_CHOSEN("Ingredient: {0}", "Ingrediënt: {0}"),
    EDITOR_NONE_CHOSEN("No ingredient yet: the row is kept as text and not counted.", "Nog geen ingrediënt: de regel blijft tekst en telt niet mee."),
    EDITOR_ROW_TEXT("Text in the recipe", "Tekst in het recept"),
    EDITOR_GROUP("Group", "Groep"),
    EDITOR_GROUP_HINT("Empty for none", "Leeg voor geen"),
    EDITOR_NEW_GROUP("New group", "Nieuwe groep"),
    EDITOR_RENAME_GROUP("Rename the group {0}", "De groep {0} hernoemen"),
    EDITOR_NEXT_GROUP("New ingredients go here", "Nieuwe ingrediënten komen hier"),
    EDITOR_PASTE("Paste lines", "Regels plakken"),
    EDITOR_PASTE_TITLE("Paste ingredient lines", "Ingrediëntregels plakken"),
    EDITOR_PASTE_LINES("One ingredient per line", "Eén ingrediënt per regel"),
    EDITOR_PASTE_HINT("Such as 200 g chicken fillet, 2 tbsp olive oil or salt.", "Zoals 200 g kipfilet, 2 el olijfolie of zout."),
    EDITOR_ADD_ROWS("Add as rows", "Als regels toevoegen"),
    EDITOR_UNREAD(
        "The cooking site could not read these lines, so they are not in the recipe: {0}",
        "Deze regels kon de kookwebsite niet lezen, dus ze staan niet in het recept: {0}",
    ),
    EDITOR_TAGS("Tags", "Labels"),
    EDITOR_ADD_TAG("Add a tag", "Label toevoegen"),
    EDITOR_REMOVE_TAG("Remove the tag {0}", "Het label {0} verwijderen"),
    UNIT_PIECE("pieces", "stuks"),
    UNIT_TBSP("tbsp", "el"),
    UNIT_TSP("tsp", "tl"),
    UNIT_CLOVE("cloves", "teentjes"),
    UNIT_CUP("cups", "kopjes"),

    // --- A product with the values of its label and named portions (issue 351)
    NEW_PRODUCT_NAMED("New product “{0}”", "Nieuw product “{0}”"),
    DELETE("Delete", "Verwijderen"),
    DELETE_FAILED("Could not delete: {0}", "Verwijderen is niet gelukt: {0}"),

    // --- Recipes
    SYNCING("Syncing the cooking site", "Kookwebsite wordt gesynchroniseerd"),
    SYNC("Sync the cooking site", "Kookwebsite synchroniseren"),
    SYNCED("Synced: {0} added, {1} updated, {2} removed", "Gesynchroniseerd: {0} toegevoegd, {1} bijgewerkt, {2} verwijderd"),
    SYNCED_BANK(
        "Synced: {0} new in the bank, {1} updated, {2} removed",
        "Gesynchroniseerd: {0} nieuw in de receptenbank, {1} bijgewerkt, {2} verwijderd",
    ),
    SYNC_FAILED("Sync failed: {0}", "Synchroniseren is mislukt: {0}"),
    IMPORTED("Imported: {0} added, {1} updated, {2} removed", "Geïmporteerd: {0} toegevoegd, {1} bijgewerkt, {2} verwijderd"),
    IMPORTED_BANK(
        "Imported: {0} new in the bank, {1} updated, {2} removed",
        "Geïmporteerd: {0} nieuw in de receptenbank, {1} bijgewerkt, {2} verwijderd",
    ),
    IMPORT_FAILED("Import failed: {0}", "Importeren is mislukt: {0}"),
    SEARCH_RECIPES("Search title or tag", "Zoek op titel of label"),
    MEALS_ONLY("Meals only", "Alleen maaltijden"),
    RECIPES_LOAD_FAILED("Could not load recipes", "De recepten konden niet worden geladen"),
    NO_RECIPES_YET(
        "No recipes yet. Add your cooking site to import them, or write one yourself.",
        "Nog geen recepten. Voeg je kookwebsite toe om ze te importeren, of schrijf er zelf een.",
    ),
    NO_RECIPES_YET_BANK(
        "No recipes yet. Add some from the recipe bank, or write one yourself.",
        "Nog geen recepten. Voeg er een paar toe uit de receptenbank, of schrijf er zelf een.",
    ),
    NO_RECIPES_MATCH("No recipes match.", "Geen recepten gevonden."),

    // --- Recipes: my recipes, the rotation and the bank
    FILTER_ALL("All", "Alle"),
    FILTER_IN_ROTATION("Rotation", "Rotatie"),
    FILTER_OUT_OF_ROTATION("Not in rotation", "Niet in rotatie"),
    ROTATION_OF("In the rotation: {0}", "In de rotatie: {0}"),
    ROTATION_NOT_OF("Not in the rotation: {0}", "Niet in de rotatie: {0}"),
    NOT_A_MEAL_SKIPPED("Not a meal: the planner skips it", "Geen maaltijd: de planner slaat het over"),
    REMOVED_FROM_SITE("No longer on the cooking site", "Niet meer op de kookwebsite"),
    BROWSE_BANK("Browse the bank", "Bekijk de receptenbank"),
    BROWSE_BANK_COUNT("Browse the bank ({0})", "Bekijk de receptenbank ({0})"),
    SHOW_THEM("Show them", "Toon ze"),
    BROWSE_BANK_MORE("Browse the bank…", "Bekijk de receptenbank…"),
    BANK_TITLE("Recipe bank", "Receptenbank"),
    BANK_EMPTY(
        "The bank is empty. Set your cooking site to fill it.",
        "De receptenbank is leeg. Stel je kookwebsite in om hem te vullen.",
    ),
    BANK_ALL_SECTIONS("All sections", "Alle rubrieken"),
    ADD_TO_MY_RECIPES("Add to my recipes", "Aan mijn recepten toevoegen"),
    ADD_RECIPE("Add {0} to my recipes", "Voeg {0} toe aan mijn recepten"),
    ADDED_ONE("“{0}” added", "“{0}” toegevoegd"),
    ADDED_FAILED("Could not add: {0}", "Toevoegen is niet gelukt: {0}"),
    LINK_SHORT("Link", "Koppelen"),
    SELECT_RECIPES("Select recipes", "Recepten selecteren"),
    STOP_SELECTING("Stop selecting", "Stoppen met selecteren"),
    SELECTED_COUNT("{0} selected", "{0} geselecteerd"),
    SELECT_ALL("Select all", "Alles selecteren"),
    ADD_TO_ROTATION("Add to rotation", "Aan rotatie toevoegen"),
    TAKE_OUT_OF_ROTATION("Take out of rotation", "Uit rotatie halen"),
    REMOVE_FROM_MINE("Remove from my recipes", "Uit mijn recepten halen"),
    REMOVED_ONE("“{0}” is back in the bank", "“{0}” staat weer in de receptenbank"),
    ROTATION_FAILED("Could not change the rotation: {0}", "De rotatie aanpassen is niet gelukt: {0}"),
    REMOVE_FAILED("Could not remove: {0}", "Uit mijn recepten halen is niet gelukt: {0}"),
    NOTHING_TO_REMOVE(
        "Nothing was removed: recipes made or changed in the app have no bank to go back to. Delete them from their own screen.",
        "Er is niets uit je recepten gehaald: recepten die je in de app maakte of aanpaste kunnen niet terug naar de receptenbank. Verwijder ze op hun eigen scherm.",
    ),
    IN_MY_ROTATION("In my rotation", "In mijn rotatie"),
    ROTATION_ON_HINT("The planner may pick this", "De planner mag dit kiezen"),
    ROTATION_OFF_HINT("Only planned when you choose it", "Alleen gepland als jij het kiest"),
    LET_PLANNER_PICK("Let the planner pick it", "Laat de planner het kiezen"),
    DELETE_RECIPE_TITLE("Delete “{0}”?", "“{0}” verwijderen?"),
    DELETE_RECIPE_TEXT("The recipe is deleted for good.", "Het recept wordt voorgoed verwijderd."),
    DELETE_TAKEN_RECIPE_TEXT(
        "Your copy, with what you changed, is deleted for good. The cooking site's recipe is in the bank again after the next sync.",
        "Je eigen versie, met wat je veranderde, wordt voorgoed verwijderd. Het recept van de kookwebsite staat na de volgende synchronisatie weer in de receptenbank.",
    ),
    SHOW("Show", "Tonen"),
    PICKER_IN_ROTATION("In my rotation", "In mijn rotatie"),
    PICKER_OTHERS("My other recipes", "Mijn andere recepten"),
    SYNC_NONE("No cooking site set. Tap to import recipes from one.", "Geen kookwebsite ingesteld. Tik om recepten te importeren."),
    SYNC_NOT_YET("Cooking site not synced yet", "Kookwebsite nog niet gesynchroniseerd"),
    SYNC_AT("Cooking site synced {0}", "Kookwebsite gesynchroniseerd {0}"),
    SYNC_SERVER_AT("This server's cooking site, synced {0}", "Kookwebsite van deze server, gesynchroniseerd {0}"),
    LAST_SYNC_FAILED("Last sync failed", "Laatste synchronisatie mislukt"),
    COOKING_SITE("Cooking site", "Kookwebsite"),
    COOKING_SITE_CHOOSE(
        "Where the recipes in your bank come from. The ones you cook you add to your recipes.",
        "Waar de recepten in je receptenbank vandaan komen. Die je kookt, voeg je toe aan je recepten.",
    ),
    SITE_OF_SERVER("This server's cooking site", "De kookwebsite van deze server"),
    SITE_OWN("A repository of my own", "Een eigen repository"),
    COOKING_SITE_EXPLAIN(
        "The git repository of your cooking site. Its recipes are imported now and then automatically every 6 hours.",
        "De git-repository van je kookwebsite. De recepten worden nu geïmporteerd en daarna elke 6 uur automatisch.",
    ),
    SITE_NONE("None", "Geen"),
    SITE_NONE_EXPLAIN("No bank: only the recipes you make in the app.", "Geen receptenbank: alleen de recepten die je in de app maakt."),
    REPO_URL("Repository URL", "Repository-URL"),
    REPO_URL_EXAMPLE("https://github.com/you/cooking.git", "https://github.com/jij/koken.git"),
    IMPORTING("Importing…", "Importeren…"),

    // --- Recipe detail
    RECIPE("Recipe", "Recept"),
    RECIPE_LOAD_FAILED("Could not load recipe", "Het recept kon niet worden geladen"),
    NO_STRUCTURED_INGREDIENTS(
        "This recipe has no structured ingredient list on the cooking site yet, so it cannot be planned.",
        "Dit recept heeft op de kookwebsite nog geen gestructureerde ingrediëntenlijst en kan dus niet worden gepland.",
    ),
    AS_WRITTEN("As written ({0})", "Zoals geschreven ({0})"),
    COOK("Cook", "Koken"),
    COOK_STEPS("Steps", "Stappen"),
    COOK_STEP_NUMBER("Step {0}", "Stap {0}"),
    COOK_CURRENT_STEP("Now", "Nu"),
    COOK_ALL_DONE("All steps done. Enjoy your meal!", "Alle stappen gedaan. Eet smakelijk!"),
    COOK_MARK_EATEN("Mark as eaten", "Markeer als gegeten"),
    COOK_MARK_EATEN_FAILED("Could not mark the meal as eaten: {0}", "Het gerecht markeren als gegeten is niet gelukt: {0}"),
    COOK_NO_INSTRUCTIONS("This recipe has no instructions.", "Dit recept heeft geen bereidingswijze."),
    NOT_COUNTED("not counted (negligible)", "niet meegeteld (verwaarloosbaar)"),
    WEIGHT_UNKNOWN("weight unknown", "gewicht onbekend"),
    RATING("Rating", "Beoordeling"),
    EVERY_DAY_BREAKFAST("Every day for breakfast", "Elke dag als ontbijt"),
    EVERY_DAY_SNACK("Every day as snack", "Elke dag als tussendoortje"),
    NOT_PLANNED_AS_MEAL(
        "Not planned as a meal (a sauce, rub, dough or side).",
        "Wordt niet als maaltijd gepland (een saus, kruidenmix, deeg of bijgerecht).",
    ),
    PER_SERVING("Per serving", "Per portie"),
    NUTRITION_MACROS("Fat {0} · Carbs {1} · Fibre {2}", "Vet {0} · Koolhydraten {1} · Vezels {2}"),
    TOO_LOW(
        "Too low: no amount is given for {0}. Add amounts on the cooking site to count them.",
        "Te laag: er is geen hoeveelheid opgegeven voor {0}. Voeg hoeveelheden toe op de kookwebsite om ze mee te tellen.",
    ),
    APPROXIMATE("Approximate: some volumes were converted assuming 1 g per ml.", "Bij benadering: sommige volumes zijn omgerekend met 1 g per ml."),
    SOURCE("Source: {0}", "Bron: {0}"),
    COST_PER_SERVING("Cost per serving", "Kosten per portie"),
    COST_SAVED(
        "{0} at regular prices; this week's offers save {1}.",
        "{0} tegen reguliere prijzen; de aanbiedingen van deze week besparen {1}.",
    ),
    COST_ESTIMATED("Estimated at a typical price: {0}.", "Geschat met een gangbare prijs: {0}."),
    COST_SOURCE("Regular prices from Albert Heijn.", "Reguliere prijzen van Albert Heijn."),

    // --- Standard meals (issue 265): the recipe's switch, the chips where a meal is skipped, the picker
    STANDARD_FAILED("Could not save whether it is a standard meal: {0}", "Opslaan of het een standaardmaaltijd is, is niet gelukt: {0}"),
    PICKER_STANDARD("My standard meals", "Mijn standaardmaaltijden"),

    // --- Adjusting a meal to what was in it (issue 226)
    TOPPINGS("Optional toppings", "Toppings naar keuze"),
    TOPPINGS_HINT(
        "Offered first when you adjust a meal of this recipe. Not counted in its nutrition, cost or shopping until added to a meal.",
        "Staan vooraan als je een maaltijd van dit recept aanpast. Tellen niet mee in voedingswaarden, kosten of boodschappen tot ze aan een maaltijd zijn toegevoegd.",
    ),
    TOPPING_ADD("Add a topping", "Topping toevoegen"),
    TOPPING_REMOVE("Remove {0}", "{0} verwijderen"),
    TOPPINGS_FAILED("Could not save the toppings: {0}", "De toppings konden niet worden opgeslagen: {0}"),

    // --- The web client's last resort and its own wording (browser, not phone)
    SYNC_NONE_BROWSER("No cooking site set. Click to import recipes from one.", "Geen kookwebsite ingesteld. Klik om recepten te importeren."),
    ;
}

/** The texts of the recipe bank, lists, editor, cook mode and generation that depend on a count, in English and Dutch. */
enum class RecipesPlural(
    override val englishOne: String,
    override val englishOther: String,
    override val dutchOne: String,
    override val dutchOther: String,
) : Plural {
    MAKES_SERVINGS("Makes {0} serving", "Makes {0} servings", "Voor {0} portie", "Voor {0} porties"),
    RATE_STARS("Rate {0} star", "Rate {0} stars", "Geef {0} ster", "Geef {0} sterren"),
    INCOMPLETE(
        "Incomplete: {0} ingredient is not counted yet ({1}).", "Incomplete: {0} ingredients are not counted yet ({1}).",
        "Onvolledig: {0} ingrediënt is nog niet meegeteld ({1}).", "Onvolledig: {0} ingrediënten zijn nog niet meegeteld ({1}).",
    ),
    BANK_RECIPES("{0} recipe in the bank", "{0} recipes in the bank", "{0} recept in de receptenbank", "{0} recepten in de receptenbank"),
    NEW_IN_BANK("{0} new recipe in the bank", "{0} new recipes in the bank", "{0} nieuw recept in de receptenbank", "{0} nieuwe recepten in de receptenbank"),
    ADDED_MANY("{0} recipe added", "{0} recipes added", "{0} recept toegevoegd", "{0} recepten toegevoegd"),
    NOT_SHOWN(
        "{0} recipe not shown (not a meal)", "{0} recipes not shown (not meals)",
        "{0} recept niet getoond (geen maaltijd)", "{0} recepten niet getoond (geen maaltijden)",
    ),
    IN_ROTATION_NOW(
        "{0} recipe is in the rotation now", "{0} recipes are in the rotation now",
        "{0} recept zit nu in de rotatie", "{0} recepten zitten nu in de rotatie",
    ),
    OUT_OF_ROTATION_NOW(
        "{0} recipe is out of the rotation now", "{0} recipes are out of the rotation now",
        "{0} recept zit niet meer in de rotatie", "{0} recepten zitten niet meer in de rotatie",
    ),
    REMOVED_MANY(
        "{0} recipe is back in the bank", "{0} recipes are back in the bank",
        "{0} recept staat weer in de receptenbank", "{0} recepten staan weer in de receptenbank",
    ),
    KEPT_MADE_IN_APP(
        "{0} recipe made or changed in the app stays: delete it from its own screen.", "{0} recipes made or changed in the app stay: delete them from their own screens.",
        "{0} recept dat je in de app maakte of aanpaste blijft: verwijder het op zijn eigen scherm.", "{0} recepten die je in de app maakte of aanpaste blijven: verwijder ze op hun eigen scherm.",
    ),
    ;
}
