package dev.tobins.mealplanner.app.accounts

import dev.tobins.mealplanner.app.Msg
import dev.tobins.mealplanner.app.Plural

/** The texts of login, registration, account requests, the profile and the settings, in English and Dutch side by side. */
enum class AccountsMsg(override val english: String, override val dutch: String) : Msg {
    // --- Settings: language
    LANGUAGE("Language", "Taal"),

    // --- "Not this ingredient" (issue 316)
    EXCLUSIONS_HINT(
        "Offers you said are not the ingredient they were matched to. They stay apart in later weeks and in other packs.",
        "Aanbiedingen waarvan je zei dat ze niet het ingrediënt zijn waar ze bij stonden. Ze blijven apart, ook in latere weken en andere verpakkingen.",
    ),
    EXCLUSIONS_NONE(
        "None yet. An offer on the Offers screen, and a deal on the shopping list, has “Not this ingredient”.",
        "Nog geen. Een aanbieding in Aanbiedingen, en op de boodschappenlijst, heeft “Niet dit ingrediënt”.",
    ),
    EXCLUDED_FROM("{0} · not counted as {1}", "{0} · telt niet als {1}"),
    EXCLUSION_WAITING("not sent yet", "nog niet verstuurd"),
    TAKE_BACK("Take back", "Intrekken"),

    // --- Login screen
    APP_NAME("Meal planner", "Meal planner"),
    LOGGED_OUT_BY_USER("You are logged out.", "Je bent uitgelogd."),
    LOGGED_OUT_EXPIRED(
        "You were logged out: your login ran out or was ended. Log in again to carry on.",
        "Je bent uitgelogd: je inlog is verlopen of beëindigd. Log opnieuw in om verder te gaan.",
    ),
    LOGGED_OUT_NOT_RENEWED(
        "You were logged out: your login could not be renewed for a while, as the login server kept answering with an error. Log in again to carry on.",
        "Je bent uitgelogd: je inlog kon al een tijd niet worden verlengd, omdat de inlogserver steeds met een fout antwoordde. Log opnieuw in om verder te gaan.",
    ),

    // The server lets only its group in (issue 314); the first is all a person refused needs to know.
    LOGGED_OUT_NOT_ALLOWED(
        "This account may not use the meal planner yet. Ask the person who runs this server.",
        "Dit account mag de meal planner nog niet gebruiken. Vraag het aan degene die deze server beheert.",
    ),
    LOGGED_OUT_NO_GROUPS(
        "You were logged out: your login does not say which groups your account is in. Log in again; if this stays, ask the person who runs this server.",
        "Je bent uitgelogd: je inlog zegt niet in welke groepen je account zit. Log opnieuw in; blijft dit zo, vraag het dan aan degene die deze server beheert.",
    ),

    // Asked for an account on the login screen, and the owner has not decided yet (issue 314).
    LOGGED_OUT_WAITING("Your request is waiting for approval.", "Je aanvraag wacht op goedkeuring."),
    LOGGING_IN("Logging in…", "Inloggen…"),
    LOGIN_AT("Logs in at {0}", "Logt in bij {0}"),
    CHANGE_SERVER("Change server", "Andere server"),

    // --- Asking for an account (issue 314): the login screen's form
    REGISTER("Register a new account", "Nieuw account registreren"),
    REGISTER_INTRO(
        "Ask for an account on this server. The person who runs it approves each request; until then you cannot use the meal planner.",
        "Vraag een account aan op deze server. Degene die hem beheert keurt elke aanvraag goed; tot dan kun je de meal planner niet gebruiken.",
    ),
    FIELD_DISPLAY_NAME("Your name", "Je naam"),
    FIELD_USERNAME("User name", "Gebruikersnaam"),
    FIELD_USERNAME_HINT("What you log in with", "Waarmee je inlogt"),
    FIELD_EMAIL("E-mail address", "E-mailadres"),
    FIELD_PASSWORD("Password", "Wachtwoord"),
    FIELD_PASSWORD_AGAIN("Password again", "Wachtwoord nog een keer"),
    PROBLEM_DISPLAY_NAME("Fill in your name, at most {0} characters.", "Vul je naam in, hoogstens {0} tekens."),
    PROBLEM_USERNAME(
        "{0} to {1} characters: letters a to z and digits, and . - _ after the first.",
        "{0} tot {1} tekens: letters a tot z en cijfers, en . - _ na de eerste.",
    ),
    PROBLEM_EMAIL(
        "Fill in an e-mail address such as name@example.org, without spaces or accented letters.",
        "Vul een e-mailadres in zoals naam@voorbeeld.nl, zonder spaties of letters met accenten.",
    ),
    PROBLEM_PASSWORD("{0} to {1} characters, and not your user name.", "{0} tot {1} tekens, en niet je gebruikersnaam."),
    PROBLEM_PASSWORD_AGAIN("The two passwords differ.", "De twee wachtwoorden verschillen."),
    REGISTER_SEND("Send request", "Aanvraag versturen"),
    REGISTER_SENDING("Sending…", "Versturen…"),
    REGISTER_FIX_FIELDS("Some fields are not right yet: see the marked ones.", "Een paar velden kloppen nog niet: zie de gemarkeerde."),
    REFUSED_OFF("This server takes no requests for an account now.", "Deze server neemt nu geen aanvragen voor een account aan."),
    REFUSED_INVALID(
        "The server did not accept these details. Check every field and try again.",
        "De server nam deze gegevens niet aan. Controleer elk veld en probeer het opnieuw.",
    ),
    REFUSED_UNAVAILABLE(
        "This user name or e-mail address cannot be used. Choose another.",
        "Deze gebruikersnaam of dit e-mailadres kan niet worden gebruikt. Kies een andere.",
    ),
    REFUSED_TOO_MANY_LATER(
        "Too many requests for an account just now. Try again later.",
        "Te veel aanvragen voor een account op dit moment. Probeer het later opnieuw.",
    ),
    REFUSED_FULL(
        "Many requests are waiting for approval already. Try again in a few days.",
        "Er wachten al veel aanvragen op goedkeuring. Probeer het over een paar dagen opnieuw.",
    ),
    REFUSED_DIRECTORY_DOWN(
        "The login server's accounts cannot be reached just now; nothing was made. Try again later.",
        "De accounts van de inlogserver zijn nu niet bereikbaar; er is niets aangemaakt. Probeer het later opnieuw.",
    ),
    REGISTER_SENT_TITLE("Request sent", "Aanvraag verstuurd"),
    REGISTER_SENT(
        "Your request for the account {0} is waiting for approval by the person who runs this server. Once it is approved, log in with that user name and your password.",
        "Je aanvraag voor het account {0} wacht op goedkeuring door degene die deze server beheert. Is hij goedgekeurd, log dan in met die gebruikersnaam en je wachtwoord.",
    ),
    BACK_TO_LOGIN("Back to log in", "Terug naar inloggen"),

    // --- The owner's requests for an account (issue 314), in Settings
    CARD_REQUESTS("Requests for an account", "Aanvragen voor een account"),
    REQUESTS_NONE("No requests wait.", "Er wachten geen aanvragen."),
    REQUESTS_LOAD_FAILED("Could not load the requests", "De aanvragen zijn niet geladen"),
    REQUEST_DATES("Asked {0} · rejected by itself on {1}", "Aangevraagd {0} · vanzelf afgewezen op {1}"),
    APPROVE("Approve", "Goedkeuren"),
    REJECT("Reject", "Afwijzen"),
    REJECT_QUESTION_TITLE("Reject the request of {0}?", "De aanvraag van {0} afwijzen?"),
    REJECT_QUESTION_TEXT(
        "This deletes the account {0} at the login server. They can ask again.",
        "Dit verwijdert het account {0} bij de inlogserver. Diegene kan opnieuw een aanvraag doen.",
    ),
    REQUEST_APPROVED("{0} can log in now.", "{0} kan nu inloggen."),
    REQUEST_REJECTED("The request of {0} is rejected.", "De aanvraag van {0} is afgewezen."),
    REQUEST_GONE("This request no longer waits; the list is up to date again.", "Deze aanvraag wacht niet meer; de lijst is weer bijgewerkt."),
    REQUEST_DECIDED_ELSEWHERE(
        "This request was decided at the login server already, so it is gone from the list.",
        "Over deze aanvraag is al bij de inlogserver beslist, dus hij staat niet meer in de lijst.",
    ),
    REQUEST_DIRECTORY_DOWN(
        "The login server's accounts cannot be reached just now; the request stays. Try again later.",
        "De accounts van de inlogserver zijn nu niet bereikbaar; de aanvraag blijft staan. Probeer het later opnieuw.",
    ),
    DECIDE_FAILED("Could not decide: {0}", "Beslissen lukte niet: {0}"),

    // --- Settings
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

    // --- App updates and login errors made by the app itself
    LOGIN_NOT_STARTED("No login was started", "Er is geen inlog gestart"),
    LOGIN_ANSWER_MISMATCH("The login answer does not match the login that was started", "Het antwoord van de inlogpagina hoort niet bij de inlog die is gestart"),
    LOGIN_NO_CODE("The login answer has no code", "Het antwoord van de inlogpagina bevat geen code"),
    LOGIN_FAILED_STATUS("Logging in failed ({0})", "Inloggen is mislukt ({0})"),
    LOGIN_SETTINGS_STATUS("The server answered {0} for its login settings", "De server antwoordde met {0} op het ophalen van de inloginstellingen"),

    // --- Shopping
    SHOPS_SAVE_FAILED_TITLE("Could not save the stores", "Winkels opslaan is niet gelukt"),
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

    // --- Profile
    PROFILE_LOAD_FAILED("Could not load your profile", "Je profiel kon niet worden geladen"),
    WEIGHT_KG("Weight (kg)", "Gewicht (kg)"),
    HEIGHT_CM("Height (cm)", "Lengte (cm)"),
    AGE("Age", "Leeftijd"),
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
        "You maintain your weight at about {0} kcal a day. Suggested: {1} kcal, {2} g protein, {3} g fibre.",
        "Je houdt je gewicht op ongeveer {0} kcal per dag. Voorstel: {1} kcal, {2} g eiwit, {3} g vezels.",
    ),
    OWN_TARGETS("Optional: use your own targets instead of the suggestion", "Optioneel: gebruik je eigen doelen in plaats van het voorstel"),
    CALORIES_PER_DAY("Calories per day", "Calorieën per dag"),
    PROTEIN_PER_DAY("Protein per day (g)", "Eiwit per dag (g)"),
    FIBRE_PER_DAY("Fibre per day (g)", "Vezels per dag (g)"),
    PRIORITY("What the planner weighs most", "Waar de planner het zwaarst op let"),
    PRIORITY_BALANCED("Balanced", "In balans"),
    PRIORITY_PROTEIN("Protein", "Eiwit"),
    PRIORITY_FIBRE("Fibre", "Vezels"),
    PRIORITY_PRICE("Price", "Prijs"),
    PRIORITY_HINT(
        "Calories come first whatever you choose. A choice gives protein, fibre or price more weight, and never less to protein or fibre.",
        "Calorieën gaan altijd voor. Je keuze geeft eiwit, vezels of prijs meer gewicht, en eiwit en vezels nooit minder.",
    ),
    PORTION_PER_MEAL("Portion per meal", "Portie per maaltijd"),
    PORTION_FROM("From (servings)", "Van (porties)"),
    PORTION_TO("To (servings)", "Tot (porties)"),
    PORTION_HINT(
        "The planner sizes meals in quarter servings within this. Breakfast and snack may still go up to 2.",
        "De planner kiest porties in kwarten binnen deze grenzen. Ontbijt en tussendoortje mogen tot 2.",
    ),
    TASTE("Taste", "Smaak"),
    LIKES("Likes, comma separated", "Vind ik lekker"),
    LIKES_HINT("Cuisines or flavours for new recipes, e.g. Korean, spicy", "Gescheiden door komma's. Keukens of smaken voor nieuwe recepten, bijv. Koreaans, pittig"),
    DISLIKES("Never use, comma separated", "Nooit gebruiken"),
    DISLIKES_HINT(
        "Ingredients the planner and new recipes leave out, e.g. mushroom",
        "Gescheiden door komma's. Ingrediënten die de planner en nieuwe recepten weglaten, bijv. champignon",
    ),
    PROFILE_SAVED("Profile saved", "Profiel opgeslagen"),

    // --- Android: the notifications, their switches in Settings and the texts they post (issue 225)
    CARD_NOTIFICATIONS("Notifications", "Meldingen"),
    NOTIFICATIONS_INTRO(
        "Checked every few hours, as often as Android allows; nothing comes between 22:00 and 08:00.",
        "Een paar keer per dag bekeken, zo vaak als Android toestaat; tussen 22:00 en 08:00 komt er niets.",
    ),
    NOTIFICATIONS_BLOCKED("Android does not let the app post notifications.", "Android laat de app geen meldingen tonen."),
    NOTIFICATIONS_OPEN_SETTINGS("Open Android settings", "Android-instellingen openen"),

    // --- The web client's last resort and its own wording (browser, not phone)
    PRODUCT_PICTURES_HINT_BROWSER(
        "Pictures are loaded from the stores' websites, so the stores can see this browser asking for them. Off shows a letter instead.",
        "Foto's worden van de websites van de winkels geladen, dus de winkels zien dat deze browser ze opvraagt. Uit toont een letter.",
    ),
    ;
}

/** The texts of login, registration, account requests, the profile and the settings that depend on a count, in English and Dutch. */
enum class AccountsPlural(
    override val englishOne: String,
    override val englishOther: String,
    override val dutchOne: String,
    override val dutchOther: String,
) : Plural {
    /** Asking for an account (issue 314): the wait the server's `Retry-After` gives, in whole minutes. */
    REFUSED_TOO_MANY(
        "Too many requests for an account just now. Try again in {0} minute.", "Too many requests for an account just now. Try again in {0} minutes.",
        "Te veel aanvragen voor een account op dit moment. Probeer het over {0} minuut opnieuw.",
        "Te veel aanvragen voor een account op dit moment. Probeer het over {0} minuten opnieuw.",
    ),

    /** The owner's requests for an account (issue 314): in Settings, and the notification's title. */
    REQUESTS_WAITING(
        "{0} request waits for your decision.", "{0} requests wait for your decision.",
        "{0} aanvraag wacht op je besluit.", "{0} aanvragen wachten op je besluit.",
    ),
    ;
}
