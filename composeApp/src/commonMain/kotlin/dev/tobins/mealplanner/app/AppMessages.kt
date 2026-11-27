package dev.tobins.mealplanner.app

/** The texts of the app's shell: the tabs, notifications, updates and the web client's last resort, in English and Dutch side by side. */
enum class AppMsg(override val english: String, override val dutch: String) : Msg {
    // --- Common
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
    TAB_PLAN("Plan", "Weekplan"),
    TAB_SHOPPING("Shopping", "Boodschappen"),

    // --- Today
    MEAL_LEFTOVERS("{0} · leftovers", "{0} · restjes"),

    // --- Settings: language
    LANGUAGE_SYSTEM("System", "Systeem"),
    LANGUAGE_ENGLISH("English", "English"),
    LANGUAGE_DUTCH("Nederlands", "Nederlands"),

    // --- Settings: theme; shopping: views and progress
    THEME_SYSTEM("System", "Systeem"),
    THEME_LIGHT("Light", "Licht"),
    THEME_DARK("Dark", "Donker"),
    VIEW_SECTION("By section", "Per afdeling"),
    VIEW_STORE("Per store", "Per winkel"),

    // --- App shell and login
    YOUR_ACCOUNT("your account", "je account"),
    LOGIN_START_FAILED("Could not start the login: {0}", "Kon het inloggen niet starten: {0}"),

    // --- Settings
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

    // --- Prepped meals (issue 234)
    MEAL_PREP_DAY("{0} · prep day", "{0} · prepdag"),
    MEAL_PREPPED("{0} · prepped {1}", "{0} · geprept {1}"),
    SIDE_PREP_DAY("Side · prep day", "Bijgerecht · prepdag"),
    SIDE_PREPPED("Side · prepped {0}", "Bijgerecht · geprept {0}"),

    // --- One batch eaten partly as meals and partly as sides (issue 269)
    THROW_AWAY_MIXED("Throw away {0} and {1} of {2}", "Gooi {0} en {1} {2} weg"),
    UNMARKED_MIXED("{0} and {1} of {2} were not marked: eaten, or to throw away?", "{0} en {1} {2} staan nog open: gegeten, of weggooien?"),

    // --- Prepped meals, the last rough edges (issue 263): a batch kept vacuum sealed says so on its meals and sides
    PREP_LABEL_VACUUM("{0} · vacuum sealed", "{0} · vacuüm"),

    // --- Profile
    WEIGHT("Weight", "Gewicht"),
    HEIGHT("Height", "Lengte"),
    GOAL("Weight loss per week", "Gewichtsverlies per week"),
    PROBLEM_RANGE("{0} must be between {1} and {2}", "{0} moet tussen {1} en {2} liggen"),
    SMALLEST_PORTION("Smallest portion", "Kleinste portie"),
    LARGEST_PORTION("Largest portion", "Grootste portie"),
    PROBLEM_QUARTERS("{0} must be in quarter servings, such as 1.25", "{0} moet in kwarten, zoals 1,25"),
    PROBLEM_PORTIONS_ORDER("The smallest portion must not be larger than the largest", "De kleinste portie mag niet groter zijn dan de grootste"),

    // --- Adjusting a meal to what was in it (issue 226)
    MEAL_ADJUSTED("{0} · adjusted", "{0} · aangepast"),

    // --- Android: the download of an update (toasts)
    DOWNLOADING_VERSION("Downloading version {0}…", "Versie {0} wordt gedownload…"),
    DOWNLOAD_FAILED("The download failed; try again from Settings.", "Het downloaden is mislukt; probeer het opnieuw via Instellingen."),

    // --- Android: the notifications, their switches in Settings and the texts they post (issue 225)
    NOTIFY_UPDATE("App update", "App-update"),
    NOTIFY_UPDATE_HINT("When a new version is out", "Als er een nieuwe versie is"),
    NOTIFY_OFFERS_ENDING("Offers ending", "Aanbiedingen die aflopen"),
    NOTIFY_OFFERS_ENDING_HINT(
        "On the last day of an offer and the day before, for what is still on your list",
        "Op de laatste dag van een aanbieding en de dag ervoor, voor wat nog op je lijst staat",
    ),
    NOTIFY_PLAN_NEXT_WEEK("Plan next week", "Volgende week plannen"),
    NOTIFY_PLAN_NEXT_WEEK_HINT(
        "From Saturday, while next week has no meals",
        "Vanaf zaterdag, zolang volgende week nog geen maaltijden heeft",
    ),
    NOTIFY_WEIGH_IN("Weigh-in", "Wegen"),
    NOTIFY_WEIGH_IN_HINT("When the last weigh-in is a week old", "Als je laatste meting een week oud is"),
    NOTIFY_DINNER_TODAY("Dinner today", "Avondeten vandaag"),
    NOTIFY_DINNER_TODAY_HINT("In the afternoon: what to cook tonight", "'s Middags: wat je vanavond kookt"),
    NOTIFY_MEALS_TO_MARK("Mark what you ate", "Aangeven wat je at"),
    NOTIFY_MEALS_TO_MARK_HINT("In the evening, while meals of today are not marked", "'s Avonds, als maaltijden van vandaag nog openstaan"),
    NOTIFY_THROW_AWAY("Prepped food to throw away", "Geprept eten om weg te gooien"),
    NOTIFY_THROW_AWAY_HINT(
        "The morning after a prep's last day, for portions still in the box or not marked",
        "De ochtend na de laatste dag van een prep, voor porties die nog in het bakje zitten of openstaan",
    ),

    // The owner's only (issue 314): the switch shows only to the account that decides on requests.
    NOTIFY_ACCOUNT_REQUESTS("Account requests", "Accountaanvragen"),
    NOTIFY_ACCOUNT_REQUESTS_HINT("When someone asks for an account on your server", "Als iemand een account aanvraagt op je server"),
    ACCOUNT_REQUESTS_NOTIFICATION_TEXT("New: {0}. Approve or reject in Settings.", "Nieuw: {0}. Keur goed of wijs af in Instellingen."),
    UPDATE_NOTIFICATION_TITLE("Meal planner {0} is available", "Meal planner {0} is beschikbaar"),
    UPDATE_NOTIFICATION_TEXT("Tap to update.", "Tik om bij te werken."),
    PLAN_NOTIFICATION_TITLE("Next week has no plan yet", "Volgende week heeft nog geen plan"),
    PLAN_NOTIFICATION_TEXT("Plan the week.", "Plan de week."),
    WEIGH_IN_NOTIFICATION_TITLE("Time for a weigh-in", "Tijd om te wegen"),
    DINNER_NOTIFICATION_TITLE("Tonight: {0}", "Vanavond: {0}"),
    DINNER_NOTIFICATION_TEXT("{0} to cook", "{0} om te koken"),
    DINNER_NOTIFICATION_PREP_TEXT("{0} to cook: tonight's and the prep's later meals", "{0} om te koken: voor vanavond en de latere maaltijden van de prep"),
    DINNER_NOTIFICATION_SIDE_TITLE("Tonight's side: {0}", "Bijgerecht vanavond: {0}"),
    MEALS_TO_MARK_NOTIFICATION_TEXT("Mark what you ate.", "Geef aan wat je hebt gegeten."),

    // --- The web client's last resort and its own wording (browser, not phone)
    CRASH_TITLE("Something went wrong", "Er ging iets mis"),
    CRASH_TEXT(
        "The app stopped working. Reloading the page usually fixes it. Nothing was sent anywhere.",
        "De app werkt niet meer. De pagina opnieuw laden lost het meestal op. Er is niets verstuurd.",
    ),
    CRASH_RELOAD("Reload", "Opnieuw laden"),
    ;
}

/** The texts of the app's shell: the tabs, notifications, updates and the web client's last resort that depend on a count, in English and Dutch. */
enum class AppPlural(
    override val englishOne: String,
    override val englishOther: String,
    override val dutchOne: String,
    override val dutchOther: String,
) : Plural {
    OFFERS_END_TODAY(
        "{0} offer for your list ends today", "{0} offers for your list end today",
        "{0} aanbieding voor je lijst eindigt vandaag", "{0} aanbiedingen voor je lijst eindigen vandaag",
    ),
    OFFERS_END_TOMORROW(
        "{0} offer for your list ends tomorrow", "{0} offers for your list end tomorrow",
        "{0} aanbieding voor je lijst eindigt morgen", "{0} aanbiedingen voor je lijst eindigen morgen",
    ),
    OFFERS_END_TODAY_OR_TOMORROW(
        "{0} offer for your list ends today or tomorrow", "{0} offers for your list end today or tomorrow",
        "{0} aanbieding voor je lijst eindigt vandaag of morgen", "{0} aanbiedingen voor je lijst eindigen vandaag of morgen",
    ),
    LAST_WEIGH_IN("The last one was {0} day ago", "The last one was {0} days ago", "De vorige was {0} dag geleden", "De vorige was {0} dagen geleden"),
    MEALS_TO_MARK(
        "{0} meal of today is not marked yet", "{0} meals of today are not marked yet",
        "{0} maaltijd van vandaag staat nog open", "{0} maaltijden van vandaag staan nog open",
    ),
    THROW_AWAY_PORTIONS("Throw away {0} portion of {1}", "Throw away {0} portions of {1}", "Gooi {0} portie {1} weg", "Gooi {0} porties {1} weg"),
    THROW_AWAY_SIDES("Throw away {0} side of {1}", "Throw away {0} sides of {1}", "Gooi {0} bijgerecht {1} weg", "Gooi {0} bijgerechten {1} weg"),
    UNMARKED_PORTIONS(
        "{0} portion of {1} was not marked: eaten, or to throw away?", "{0} portions of {1} were not marked: eaten, or to throw away?",
        "{0} portie {1} staat nog open: gegeten, of weggooien?", "{0} porties {1} staan nog open: gegeten, of weggooien?",
    ),
    UNMARKED_SIDES(
        "{0} side of {1} was not marked: eaten, or to throw away?", "{0} sides of {1} were not marked: eaten, or to throw away?",
        "{0} bijgerecht {1} staat nog open: gegeten, of weggooien?", "{0} bijgerechten {1} staan nog open: gegeten, of weggooien?",
    ),

    /** A batch's portions and sides counted apart, as [AppMsg.THROW_AWAY_MIXED] and [AppMsg.UNMARKED_MIXED] name them (issue 269). */
    PORTIONS("{0} portion", "{0} portions", "{0} portie", "{0} porties"),
    SIDES("{0} side", "{0} sides", "{0} bijgerecht", "{0} bijgerechten"),

    /** The count is that of the batches; `{1}` is their last day. */
    KEPT_UNTIL("It kept until {1}.", "They kept until {1}.", "Het was goed tot {1}.", "Ze waren goed tot {1}."),
    ACCOUNT_REQUESTS_TITLE(
        "{0} request for an account waits", "{0} requests for an account wait",
        "{0} aanvraag voor een account wacht", "{0} aanvragen voor een account wachten",
    ),
    ;
}
