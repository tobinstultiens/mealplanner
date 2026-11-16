package dev.tobins.mealplanner.app

/*
 * Every text the app shows that is not made of data, in English and Dutch side by side, so a
 * translation is read (and fixed) in one place. Placeholders are `{0}`, `{1}`, ...; both
 * languages must use the same ones, which MessagesTest checks. Numbers, money and dates are
 * formatted by the caller and passed in. Use them through [Strings] (`LocalStrings.current`).
 *
 * Dutch is informal ("je") and uses the words of a Dutch supermarket app. "kcal" stays.
 */

/** One text in both languages. */
enum class Msg(private val english: String, private val dutch: String) {
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
    RETRY("Retry", "Opnieuw"),
    REFRESH_FAILED_KEPT("Could not refresh. Showing what was loaded before.", "Vernieuwen lukte niet. Dit is wat eerder is geladen."),

    // --- Meal slots (lower case, capitalised by the caller for titles) and shop sections
    SLOT_BREAKFAST("breakfast", "ontbijt"),
    SLOT_LUNCH("lunch", "lunch"),
    SLOT_DINNER("dinner", "avondeten"),
    SLOT_SNACK("snack", "tussendoortje"),
    SECTION_FRUIT_VEG("Fruit and vegetables", "Groente en fruit"),
    SECTION_BREAD("Bread", "Brood"),
    SECTION_DELI("Cold meats and cheese", "Vleeswaren en kaas"),
    SECTION_SAUCES("Sauces, spices and oil", "Sauzen, kruiden en olie"),
    SECTION_MEAT("Meat", "Vlees"),
    SECTION_FISH("Fish", "Vis"),
    SECTION_VEGETARIAN("Vegetarian and plant-based", "Vegetarisch en plantaardig"),
    SECTION_DAIRY("Dairy and eggs", "Zuivel en eieren"),
    SECTION_READY_MEALS("Ready meals and salads", "Kant-en-klaar en salades"),
    SECTION_WORLD("Pasta, rice and world foods", "Pasta, rijst en wereldkeuken"),
    SECTION_TINS("Tins and jars", "Blik en pot"),
    SECTION_BREAKFAST("Breakfast and spreads", "Ontbijt en beleg"),
    SECTION_BAKING("Baking", "Bakken"),
    SECTION_SNACKS("Snacks and sweets", "Snacks en snoep"),
    SECTION_DRINKS("Drinks", "Drinken"),
    SECTION_FROZEN("Frozen", "Diepvries"),
    SECTION_NON_FOOD("Household and non-food", "Huishouden en non-food"),
    SECTION_OTHER("Other", "Overig"),

    // --- Tabs
    TAB_TODAY("Today", "Vandaag"),
    TAB_PLAN("Plan", "Weekplan"),
    TAB_SHOPPING("Shopping", "Boodschappen"),
    TAB_RECIPES("Recipes", "Recepten"),
    TAB_PROGRESS("Progress", "Voortgang"),

    // --- Dates and times
    WHEN_TODAY("today {0}", "vandaag {0}"),
    WHEN_YESTERDAY("yesterday {0}", "gisteren {0}"),

    // --- Today
    PREVIOUS_DAY("Previous day", "Vorige dag"),
    NEXT_DAY("Next day", "Volgende dag"),
    BACK_TO_TODAY("Back to today", "Terug naar vandaag"),
    DAY_LOAD_FAILED("Could not load the day", "De dag kon niet worden geladen"),
    NOTHING_PLANNED_DAY("Nothing is planned for this day.", "Voor deze dag staat niets gepland."),
    OPEN_PLAN("Open the plan", "Open het weekplan"),
    ALSO_EATEN("Also eaten", "Ook gegeten"),
    KCAL_PROTEIN("{0} kcal · {1} g protein", "{0} kcal · {1} g eiwit"),
    ADD_EXTRA("Add something else you ate", "Voeg iets anders toe dat je at"),
    EATEN_TOTAL("Eaten: {0} kcal · {1} g protein", "Gegeten: {0} kcal · {1} g eiwit"),
    SET_UP_PROFILE_HINT("Set up your profile under Progress to see targets.", "Stel je profiel in onder Voortgang om je doelen te zien."),
    EATEN_SO_FAR("Eaten so far", "Tot nu toe gegeten"),
    KCAL_OF("{0} of {1} kcal", "{0} van {1} kcal"),
    PROTEIN_OF("{0} of {1} g protein", "{0} van {1} g eiwit"),
    FORECAST_OVER(
        "Following the rest of the plan ends the day at {0} kcal, {1} over the target.",
        "Als je de rest van het plan volgt, eindig je de dag op {0} kcal, {1} boven je doel.",
    ),
    FORECAST_UNDER(
        "Following the rest of the plan ends the day at {0} kcal, {1} under the target.",
        "Als je de rest van het plan volgt, eindig je de dag op {0} kcal, {1} onder je doel.",
    ),
    FORECAST_ON(
        "Following the rest of the plan ends the day at {0} kcal, exactly on target.",
        "Als je de rest van het plan volgt, eindig je de dag op {0} kcal, precies op je doel.",
    ),
    MEAL_LEFTOVERS("{0} · leftovers", "{0} · restjes"),
    STATUS_EATEN("Eaten", "Gegeten"),
    STATUS_EATEN_MARK("✓ Eaten", "✓ Gegeten"),
    STATUS_SKIPPED("Skipped", "Overgeslagen"),
    EXTRA_TITLE("Something else you ate", "Iets anders gegeten"),
    EXTRA_WHAT("What", "Wat"),
    EXTRA_CALORIES("Calories (a rough number is fine)", "Calorieën (ruwe schatting)"),
    EXTRA_PROTEIN("Protein in grams (optional)", "Eiwit in gram (optioneel)"),

    // --- Settings: language
    LANGUAGE("Language", "Taal"),
    LANGUAGE_SYSTEM("System", "Systeem"),
    LANGUAGE_ENGLISH("English", "English"),
    LANGUAGE_DUTCH("Nederlands", "Nederlands"),

    // --- Formatted facts (recipes, offers, plans)
    OFFER_WAS("(was {0})", "(was {0})"),
    ON_OFFER("on offer", "in de aanbieding"),
    NO_AMOUNTS_YET("No amounts yet", "Nog geen hoeveelheden"),
    MISSING_AMOUNTS("Missing amounts", "Hoeveelheden ontbreken"),

    // --- Settings: theme; shopping: views and progress
    THEME_SYSTEM("System", "Systeem"),
    THEME_LIGHT("Light", "Licht"),
    THEME_DARK("Dark", "Donker"),
    VIEW_SECTION("By section", "Per afdeling"),
    VIEW_STORE("Per store", "Per winkel"),
    ALL_TICKED_OFF("All {0} ticked off", "Alle {0} afgevinkt"),
    BADGE_OFFER("offer", "aanbieding"),
    OFFER_FROM("from {0}", "vanaf {0}"),
    OFFER_UNTIL("until {0}", "t/m {0}"),
    PACKS_TO_TAKE("{0} × {1}", "{0} × {1}"),
    PACKS_DEAL_NEEDS("{0} × {1} · deal needs {2}", "{0} × {1} · actie vraagt {2}"),

    // --- App shell and login
    YOUR_ACCOUNT("your account", "je account"),
    LOGIN_FAILED("Logging in failed", "Inloggen is mislukt"),
    LOGIN_START_FAILED("Could not start the login: {0}", "Kon het inloggen niet starten: {0}"),

    // --- Login screen
    APP_NAME("Meal planner", "Meal planner"),
    LOGGED_OUT_BY_USER("You are logged out.", "Je bent uitgelogd."),
    LOGGED_OUT_EXPIRED(
        "You were logged out: your login ran out or was ended. Log in again to carry on.",
        "Je bent uitgelogd: je inlog is verlopen of beëindigd. Log opnieuw in om verder te gaan.",
    ),
    LOGGING_IN("Logging in…", "Inloggen…"),
    LOGIN_AT("Logs in at {0}", "Logt in bij {0}"),
    CHANGE_SERVER("Change server", "Andere server"),

    // --- Settings
    SETTINGS("Settings", "Instellingen"),
    CARD_SERVER("Server", "Server"),
    SERVER_ADDRESS("Server address", "Serveradres"),
    SERVER_ADDRESS_HINT("For example https://mealplanner.example.nl", "Bijvoorbeeld https://mealplanner.example.nl"),
    CONNECTED("Connected to server {0}.", "Verbonden met server {0}."),
    TESTING("Testing…", "Testen…"),
    TEST_CONNECTION("Test connection", "Verbinding testen"),
    CANNOT_REACH("Cannot reach the server", "De server is niet bereikbaar"),
    CARD_ACCOUNT("Account", "Account"),
    LOGGED_IN_AS("Logged in as {0}.", "Ingelogd als {0}."),
    LOGGED_IN("Logged in.", "Ingelogd."),
    LOG_OUT("Log out", "Uitloggen"),
    LOG_IN("Log in", "Inloggen"),
    LOGIN_EXPLAIN(
        "Log in with your account on the server's login page (Authelia). Each account has its own recipes, plans and progress.",
        "Log in met je account op de inlogpagina van de server (Authelia). Elk account heeft zijn eigen recepten, plannen en voortgang.",
    ),
    COULD_NOT_LOG_IN("Could not log in", "Inloggen is niet gelukt"),
    CARD_APPEARANCE("Appearance", "Weergave"),
    CARD_SHOPPING("Shopping", "Boodschappen"),
    SYSTEM_COLORS("Use system colours", "Systeemkleuren gebruiken"),
    SYSTEM_COLORS_HINT("Take the colours from your wallpaper.", "Neem de kleuren over van je achtergrond."),
    PRODUCT_PICTURES("Show product pictures", "Productfoto's tonen"),
    PRODUCT_PICTURES_HINT(
        "Pictures are loaded from the stores' websites, so the stores can see this phone asking for them. Off shows a letter instead.",
        "Foto's worden van de websites van de winkels geladen, dus de winkels zien dat deze telefoon ze opvraagt. Uit toont een letter.",
    ),
    CARD_APP("App", "App"),
    VERSION("Version {0}", "Versie {0}"),
    CHECKING("Checking…", "Controleren…"),
    CHECK_UPDATES("Check for updates", "Controleren op updates"),
    LATEST_VERSION("You have the latest version.", "Je hebt de nieuwste versie."),
    CHECK_FAILED("Could not check: {0}", "Controleren is niet gelukt: {0}"),

    // --- App updates and login errors made by the app itself
    GITHUB_ANSWERED("GitHub answered {0}", "GitHub antwoordde met {0}"),
    UPDATE_AVAILABLE("Version {0} is available", "Versie {0} is beschikbaar"),
    UPDATE_EXPLAIN(
        "You have {0}. The update downloads and then asks Android to install it.",
        "Je hebt {0}. De update wordt gedownload en daarna vraagt Android of je hem wilt installeren.",
    ),
    WHATS_NEW("What's new", "Wat is er nieuw"),
    UPDATE("Update", "Bijwerken"),
    LATER("Later", "Later"),
    LOGIN_NOT_STARTED("No login was started", "Er is geen inlog gestart"),
    LOGIN_ANSWER_MISMATCH("The login answer does not match the login that was started", "Het antwoord van de inlogpagina hoort niet bij de inlog die is gestart"),
    LOGIN_NO_CODE("The login answer has no code", "Het antwoord van de inlogpagina bevat geen code"),
    LOGIN_FAILED_STATUS("Logging in failed ({0})", "Inloggen is mislukt ({0})"),
    LOGIN_SETTINGS_STATUS("The server answered {0} for its login settings", "De server antwoordde met {0} op het ophalen van de inloginstellingen"),

    // --- Plan
    WEEK_OF("Week of {0}", "Week van {0}"),
    PREVIOUS_WEEK("Previous week", "Vorige week"),
    NEXT_WEEK("Next week", "Volgende week"),
    PLAN_NEW_FOR("New {0} for {1}", "Nieuw recept voor {0} op {1}"),
    PLAN_SET_UP_PROFILE(
        "Set up your profile first: the planner needs your daily calorie and protein targets.",
        "Stel eerst je profiel in: de planner heeft je dagelijkse calorie- en eiwitdoelen nodig.",
    ),
    OPEN_PROFILE("Open profile", "Open profiel"),
    PLAN_LOAD_FAILED("Could not load the plan", "Het weekplan kon niet worden geladen"),
    DAILY_TARGET("Daily target: {0} kcal · {1} g protein", "Dagdoel: {0} kcal · {1} g eiwit"),
    WEEK_COST("≈ {0} for the week's meals", "≈ {0} voor de maaltijden van deze week"),
    WEEK_COST_SAVED("≈ {0} for the week's meals, {1} saved by offers", "≈ {0} voor de maaltijden van deze week, {1} bespaard met aanbiedingen"),
    REPLAN("Replan unlocked meals", "Plan niet-vergrendelde maaltijden opnieuw"),
    PLAN_WEEK("Plan this week", "Plan deze week"),
    NO_RECIPES_FOR(
        "No recipes yet for: {0}. Days stay below the target until those exist.",
        "Nog geen recepten voor: {0}. De dagen blijven onder je doel tot die er zijn.",
    ),
    NOTHING_PLANNED_WEEK("Nothing planned for this week yet.", "Voor deze week staat nog niets gepland."),
    DAY_TOTALS("{0} of {1} kcal · {2} of {3} g protein", "{0} van {1} kcal · {2} van {3} g eiwit"),
    NOTHING_PLANNED("Nothing planned", "Niets gepland"),
    UNLOCK_SLOT("Unlock {0}", "Ontgrendel {0}"),
    LOCK_SLOT("Lock {0}", "Vergrendel {0}"),
    SWAP_SLOT("Swap {0}", "Wissel {0}"),
    ANOTHER_RECIPE("Another recipe", "Ander recept"),
    WRITE_NEW_RECIPE("Write a new recipe…", "Nieuw recept laten schrijven…"),
    CHOOSE_RECIPE("Choose a recipe…", "Kies een recept…"),
    NOT_EATING_THIS("Not eating this", "Eet ik niet"),
    EAT_AFTER_ALL("Eat it after all", "Toch eten"),
    PLAN_ADD_MEAL("Add a meal…", "Maaltijd toevoegen…"),
    PLAN_CHOOSE_FOR("Recipe for {0} on {1}", "Recept voor {0} op {1}"),
    PLAN_CHOOSE_FAILED("The recipe could not be put in the plan.", "Het recept kon niet in het plan worden gezet."),
    PLAN_CHOOSE_UNSUPPORTED(
        "The server cannot put a chosen recipe in the plan yet. It is updated overnight; try again tomorrow.",
        "De server kan nog geen gekozen recept in het plan zetten. Hij wordt 's nachts bijgewerkt; probeer het morgen opnieuw.",
    ),

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
        "Recipe generation is off on the server: it needs an ANTHROPIC_API_KEY.",
        "Recepten laten maken staat uit op de server: die heeft een ANTHROPIC_API_KEY nodig.",
    ),
    GEN_NEEDS_PROFILE("Set up your profile first; recipes are sized to your targets.", "Stel eerst je profiel in; recepten worden op je doelen afgestemd."),
    GEN_FAILED("Could not generate: {0}", "Het maken is niet gelukt: {0}"),
    GENERATE("Generate", "Maak recept"),

    // --- Recipe editor
    NEW_RECIPE("New recipe", "Nieuw recept"),
    EDIT_RECIPE("Edit recipe", "Recept bewerken"),
    EDITOR_LOAD_FAILED("Could not load the recipe: {0}", "Het recept kon niet worden geladen: {0}"),
    NAME("Name", "Naam"),
    SERVINGS("Servings", "Porties"),
    PLAN_AS("Plan as", "Plannen als"),
    INGREDIENTS("Ingredients", "Ingrediënten"),
    INGREDIENTS_HINT(
        "One per line: amount | unit | item, for example\n200 | g | low-fat quark\n1 | banana |",
        "Eén per regel: hoeveelheid | eenheid | product, bijvoorbeeld\n200 | g | magere kwark\n1 | banaan |",
    ),
    STEPS_OPTIONAL("Steps (optional)", "Stappen (optioneel)"),
    CHECK_LINES(
        "Check the ingredient lines: each needs the form amount | unit | item",
        "Controleer de ingrediëntregels: elke regel heeft de vorm hoeveelheid | eenheid | product",
    ),
    SAVE_FAILED("Could not save: {0}", "Opslaan is niet gelukt: {0}"),
    DELETE("Delete", "Verwijderen"),
    DELETE_FAILED("Could not delete: {0}", "Verwijderen is niet gelukt: {0}"),

    // --- Offers
    OFFERS("Offers", "Aanbiedingen"),
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

    // --- Pantry
    PANTRY("Pantry", "Voorraad"),
    PANTRY_LOAD_FAILED("Could not load the pantry", "De voorraad kon niet worden geladen"),
    PANTRY_SUMMARY(
        "Staples: {0} (always at home, never on the shopping list) · On hand: {1} (to use up; the planner favours them)",
        "Basisproducten: {0} (altijd in huis, nooit op je boodschappenlijst) · In huis: {1} (op te maken; de planner geeft ze voorrang)",
    ),
    SEARCH_INGREDIENTS("Search ingredients", "Zoek ingrediënten"),
    STAPLE("Staple", "Basis"),
    ON_HAND("On hand", "In huis"),
    PANTRY_LEFT("{0} left", "nog {0}"),
    PANTRY_FROM_WEEK("for the week of {0}", "voor de week van {0}"),
    PANTRY_FROM_WEEKS("for the weeks of {0}", "voor de weken van {0}"),

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

    // --- Recipes
    SYNCING("Syncing the cooking site", "Kookwebsite wordt gesynchroniseerd"),
    SYNC("Sync the cooking site", "Kookwebsite synchroniseren"),
    SYNCED("Synced: {0} added, {1} updated, {2} removed", "Gesynchroniseerd: {0} toegevoegd, {1} bijgewerkt, {2} verwijderd"),
    SYNC_FAILED("Sync failed: {0}", "Synchroniseren is mislukt: {0}"),
    IMPORTED("Imported: {0} added, {1} updated, {2} removed", "Geïmporteerd: {0} toegevoegd, {1} bijgewerkt, {2} verwijderd"),
    IMPORT_FAILED("Import failed: {0}", "Importeren is mislukt: {0}"),
    LINK_INGREDIENTS("Link ingredients", "Ingrediënten koppelen"),
    SEARCH_RECIPES("Search title or tag", "Zoek op titel of label"),
    MEALS_ONLY("Meals only", "Alleen maaltijden"),
    RECIPES_LOAD_FAILED("Could not load recipes", "De recepten konden niet worden geladen"),
    NO_RECIPES_YET(
        "No recipes yet. Add your cooking site to import them, or write one yourself.",
        "Nog geen recepten. Voeg je kookwebsite toe om ze te importeren, of schrijf er zelf een.",
    ),
    NO_RECIPES_MATCH("No recipes match.", "Geen recepten gevonden."),
    SYNC_NONE("No cooking site set. Tap to import recipes from one.", "Geen kookwebsite ingesteld. Tik om recepten te importeren."),
    SYNC_NOT_YET("Cooking site not synced yet", "Kookwebsite nog niet gesynchroniseerd"),
    SYNC_AT("Cooking site synced {0}", "Kookwebsite gesynchroniseerd {0}"),
    LAST_SYNC_FAILED("Last sync failed", "Laatste synchronisatie mislukt"),
    COOKING_SITE("Cooking site", "Kookwebsite"),
    COOKING_SITE_EXPLAIN(
        "The git repository of your cooking site. Its recipes are imported now and then automatically every 6 hours.",
        "De git-repository van je kookwebsite. De recepten worden nu geïmporteerd en daarna elke 6 uur automatisch.",
    ),
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
    AS_WRITTEN("As written ({0})", "Zoals geschreven ({0})"),
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

    // --- Errors made by the app (the server's own messages are shown as they come)
    SERVER_ANSWERED("The server answered {0} {1}", "De server antwoordde met {0} {1}"),
    UNKNOWN_ERROR("Unknown error", "Onbekende fout"),
    NO_ANSWER("The server did not answer in time", "De server antwoordde niet op tijd"),
    NO_CONNECTION("the server could not be reached", "de server is niet bereikbaar"),
    WENT_WRONG("something went wrong", "er ging iets mis"),
    UNREADABLE_ANSWER("the server's answer could not be read", "het antwoord van de server was niet te lezen"),

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
    SHOPS_SAVE_FAILED_TITLE("Could not save the stores", "Winkels opslaan is niet gelukt"),
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
    BUY_AHEAD_SETTING("Buy ahead for next week", "Vooruit kopen voor volgende week"),
    BUY_AHEAD_SETTING_HINT(
        "The shopping list suggests offers ending this week for meals of next week",
        "De boodschappenlijst stelt aanbiedingen voor die deze week stoppen, voor maaltijden van volgende week",
    ),
    STOCK_UP_SETTING("Stock-up advice", "Advies om in te slaan"),
    STOCK_UP_SETTING_HINT(
        "The shopping list names staples and food that keeps when on offer",
        "De boodschappenlijst noemt basisproducten en houdbaar eten als ze in de aanbieding zijn",
    ),
    ADVICE_SAVE_FAILED_TITLE("Could not save the shopping advice", "Het boodschappenadvies opslaan is niet gelukt"),

    // --- Progress
    PROFILE_AND_TARGETS("Profile and targets", "Profiel en doelen"),
    WEIGHT_LOAD_FAILED("Could not load your weight log", "Je gewichtslogboek kon niet worden geladen"),
    TODAYS_WEIGHT("Today's weight (kg)", "Gewicht vandaag (kg)"),
    WEIGH_HINT(
        "Weigh yourself at the same time each day, for example after getting up. After two weeks the trend shows whether the calorie target fits.",
        "Weeg jezelf elke dag op hetzelfde moment, bijvoorbeeld na het opstaan. Na twee weken zie je aan de trend of je caloriedoel past.",
    ),
    TARGET_NOW("Calorie target is now {0} kcal", "Je caloriedoel is nu {0} kcal"),
    TARGET_CHANGE_FAILED("Could not change the target", "Het doel kon niet worden aangepast"),
    WEIGHINGS("Weighings", "Metingen"),
    WEIGHING_ROW("{0}  ·  trend {1}", "{0}  ·  trend {1}"),
    TREND_WEIGHT("Trend weight", "Trendgewicht"),
    DOWN_SINCE("Down {0} since {1}", "{0} lichter sinds {1}"),
    UP_SINCE("Up {0} since {1}", "{0} zwaarder sinds {1}"),
    LOSING("Losing {0} kg a week over the last two weeks", "Je valt {0} kg per week af over de afgelopen twee weken"),
    GAINING("Gaining {0} kg a week over the last two weeks", "Je komt {0} kg per week aan over de afgelopen twee weken"),
    RATE_LATER("A weekly rate appears after two weeks of regular weighings.", "Het weekelijkse tempo verschijnt na twee weken regelmatig wegen."),
    ADJUST_TARGET("Adjust your calorie target?", "Je caloriedoel aanpassen?"),
    SUGGESTION_LOSS(
        "Your goal is {0} kg a week, and the trend shows a loss of {1} kg a week. Changing the target from {2} to {3} kcal should close the gap.",
        "Je doel is {0} kg per week en de trend laat een afname van {1} kg per week zien. Pas je doel aan van {2} naar {3} kcal om het verschil te dichten.",
    ),
    SUGGESTION_GAIN(
        "Your goal is {0} kg a week, and the trend shows a gain of {1} kg a week. Changing the target from {2} to {3} kcal should close the gap.",
        "Je doel is {0} kg per week en de trend laat een toename van {1} kg per week zien. Pas je doel aan van {2} naar {3} kcal om het verschil te dichten.",
    ),
    USE_KCAL("Use {0} kcal", "Gebruik {0} kcal"),
    NOT_NOW("Not now", "Niet nu"),
    WEIGHT_TREND_CHART("Weight trend (kg)", "Gewichtstrend (kg)"),
    CHART_SELECTED("{0}: weighed {1}, trend {2}", "{0}: gewogen {1}, trend {2}"),
    CHART_HINT(
        "Line: trend. Dots: daily weighings. Touch the chart to read a day.",
        "Lijn: trend. Stippen: dagelijkse metingen. Tik op de grafiek om een dag af te lezen.",
    ),

    // --- Profile
    PROFILE_LOAD_FAILED("Could not load your profile", "Je profiel kon niet worden geladen"),
    WEIGHT_KG("Weight (kg)", "Gewicht (kg)"),
    HEIGHT_CM("Height (cm)", "Lengte (cm)"),
    AGE("Age", "Leeftijd"),
    WEIGHT("Weight", "Gewicht"),
    HEIGHT("Height", "Lengte"),
    GOAL("Weight loss per week", "Gewichtsverlies per week"),
    PROBLEM_RANGE("{0} must be between {1} and {2}", "{0} moet tussen {1} en {2} liggen"),
    MALE("Male", "Man"),
    FEMALE("Female", "Vrouw"),
    ACTIVITY("Activity", "Activiteit"),
    ACTIVITY_SEDENTARY("Mostly sitting", "Vooral zittend"),
    ACTIVITY_LIGHT("Light (1–3 workouts a week)", "Licht (1–3 keer sporten per week)"),
    ACTIVITY_MODERATE("Moderate (3–5 workouts)", "Gemiddeld (3–5 keer sporten)"),
    ACTIVITY_ACTIVE("Very active (6–7 workouts)", "Zeer actief (6–7 keer sporten)"),
    GOAL_PER_WEEK("Weight loss per week (kg, 0 to 1)", "Afvallen per week (kg, 0–1)"),
    DAILY_TARGETS("Daily targets", "Dagelijkse doelen"),
    FILL_IN("Fill in the fields above to see your targets.", "Vul de velden hierboven in om je doelen te zien."),
    MAINTENANCE(
        "You maintain your weight at about {0} kcal a day. Suggested: {1} kcal, {2} g protein.",
        "Je houdt je gewicht op ongeveer {0} kcal per dag. Voorstel: {1} kcal, {2} g eiwit.",
    ),
    OWN_TARGETS("Optional: use your own targets instead of the suggestion", "Optioneel: gebruik je eigen doelen in plaats van het voorstel"),
    CALORIES_PER_DAY("Calories per day", "Calorieën per dag"),
    PROTEIN_PER_DAY("Protein per day (g)", "Eiwit per dag (g)"),
    TASTE("Taste", "Smaak"),
    LIKES("Likes, comma separated", "Vind ik lekker"),
    LIKES_HINT("Cuisines or flavours for new recipes, e.g. Korean, spicy", "Gescheiden door komma's. Keukens of smaken voor nieuwe recepten, bijv. Koreaans, pittig"),
    DISLIKES("Never use, comma separated", "Nooit gebruiken"),
    DISLIKES_HINT(
        "Ingredients the planner and new recipes leave out, e.g. mushroom",
        "Gescheiden door komma's. Ingrediënten die de planner en nieuwe recepten weglaten, bijv. champignon",
    ),
    PROFILE_SAVED("Profile saved", "Profiel opgeslagen"),

    // --- Link ingredients
    FOOD_SUMMARY("{0} · {1} kcal, {2} g protein per {3}", "{0} · {1} kcal, {2} g eiwit per {3}"),
    INGREDIENTS_LOAD_FAILED("Could not load ingredients", "De ingrediënten konden niet worden geladen"),
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

    // --- Android: the download of an update (toasts)
    DOWNLOADING_VERSION("Downloading version {0}…", "Versie {0} wordt gedownload…"),
    DOWNLOAD_FAILED("The download failed; try again from Settings.", "Het downloaden is mislukt; probeer het opnieuw via Instellingen."),

    // --- end of messages
    ;

    /** The text in [language]. */
    fun text(language: Language): String = if (language == Language.DUTCH) dutch else english
}

/**
 * A text that depends on a count, in English and Dutch. Both languages use the form for one
 * when the count is 1 and the other form otherwise, including for 0. `{0}` is the count.
 */
enum class Plural(
    private val englishOne: String,
    private val englishOther: String,
    private val dutchOne: String,
    private val dutchOther: String,
) {
    SERVINGS("{0} serving", "{0} servings", "{0} portie", "{0} porties"),

    /** For an amount that may be fractional: the count only picks the form, `{1}` is the amount as written. */
    SERVINGS_AMOUNT("{1} serving", "{1} servings", "{1} portie", "{1} porties"),
    PACK_PIECES("{0} piece", "{0} pieces", "{0} stuk", "{0} stuks"),
    INGREDIENTS_TO_LINK("{0} ingredient to link", "{0} ingredients to link", "{0} ingrediënt om te koppelen", "{0} ingrediënten om te koppelen"),
    STORE_ITEMS_ON_OFFER(
        "{1} · {0} item on offer", "{1} · {0} items on offer",
        "{1} · {0} product in de aanbieding", "{1} · {0} producten in de aanbieding",
    ),
    SECTIONS_TO_GO(
        "{1} of {2} · {0} section to go", "{1} of {2} · {0} sections to go",
        "{1} van {2} · nog {0} afdeling", "{1} van {2} · nog {0} afdelingen",
    ),
    SOURCE_FETCHED(
        "{1}: {0} offer, fetched {2}", "{1}: {0} offers, fetched {2}",
        "{1}: {0} aanbieding, opgehaald {2}", "{1}: {0} aanbiedingen, opgehaald {2}",
    ),
    PRICES_SUMMARY(
        "{0} ingredient priced from Albert Heijn, looked up {1}.", "{0} ingredients priced from Albert Heijn, looked up {1}.",
        "{0} ingrediënt geprijsd via Albert Heijn, opgezocht op {1}.", "{0} ingrediënten geprijsd via Albert Heijn, opgezocht op {1}.",
    ),
    MAKES_SERVINGS("Makes {0} serving", "Makes {0} servings", "Voor {0} portie", "Voor {0} porties"),
    RATE_STARS("Rate {0} star", "Rate {0} stars", "Geef {0} ster", "Geef {0} sterren"),
    INCOMPLETE(
        "Incomplete: {0} ingredient is not counted yet ({1}).", "Incomplete: {0} ingredients are not counted yet ({1}).",
        "Onvolledig: {0} ingrediënt is nog niet meegeteld ({1}).", "Onvolledig: {0} ingrediënten zijn nog niet meegeteld ({1}).",
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
    USED_IN_RECIPES("Used in {0} recipe, e.g. {1}", "Used in {0} recipes, e.g. {1}", "Gebruikt in {0} recept, bijv. {1}", "Gebruikt in {0} recepten, bijv. {1}"),
    // --- end of plurals
    ;

    /** The text of [language] for [count]. */
    fun text(language: Language, count: Int): String = when (language) {
        Language.ENGLISH -> if (count == 1) englishOne else englishOther
        Language.DUTCH -> if (count == 1) dutchOne else dutchOther
    }
}
