package dev.tobins.mealplanner.app.plan

import dev.tobins.mealplanner.app.Msg
import dev.tobins.mealplanner.app.Plural

/** The texts of the plan, planning and today screens, adjusting a meal, preps, standard meals and meal changes, in English and Dutch side by side. */
enum class PlanMsg(override val english: String, override val dutch: String) : Msg {
    // --- Tabs
    TAB_TODAY("Today", "Vandaag"),

    // --- Today
    PREVIOUS_DAY("Previous day", "Vorige dag"),
    NEXT_DAY("Next day", "Volgende dag"),
    BACK_TO_TODAY("Back to today", "Terug naar vandaag"),
    DAY_LOAD_FAILED("Could not load the day", "De dag kon niet worden geladen"),
    NOTHING_PLANNED_DAY("Nothing is planned for this day.", "Voor deze dag staat niets gepland."),
    OPEN_PLAN("Open the plan", "Open het weekplan"),
    ADD_EXTRA("Type kcal yourself", "Zelf kcal invullen"),
    EATEN_TOTAL("Eaten: {0} kcal · {1} g protein", "Gegeten: {0} kcal · {1} g eiwit"),
    SET_UP_PROFILE_HINT("Set up your profile under Progress to see targets.", "Stel je profiel in onder Voortgang om je doelen te zien."),
    EATEN_SO_FAR("Eaten so far", "Tot nu toe gegeten"),
    KCAL_OF("{0} of {1} kcal", "{0} van {1} kcal"),
    PROTEIN_OF("{0} of {1} g protein", "{0} van {1} g eiwit"),
    FIBRE_OF("{0} of {1} g fibre", "{0} van {1} g vezels"),
    FORECAST_FIBRE(
        "Following the rest of the plan gives {0} of {1} g fibre today.",
        "Als je de rest van het plan volgt, krijg je vandaag {0} van {1} g vezels binnen.",
    ),
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
    STATUS_EATEN("Eaten", "Gegeten"),
    STATUS_EATEN_MARK("✓ Eaten", "✓ Gegeten"),
    STATUS_SKIPPED("Skipped", "Overgeslagen"),
    EXTRA_TITLE("Something else you ate", "Iets anders gegeten"),
    EXTRA_WHAT("What", "Wat"),
    EXTRA_CALORIES("Calories (a rough number is fine)", "Calorieën (ruwe schatting)"),
    EXTRA_PROTEIN("Protein in grams (optional)", "Eiwit in gram (optioneel)"),

    // --- Foods logged as eaten outside the plan (issue 362)
    LOG_ADD_FOOD("Add a food", "Voedingsmiddel toevoegen"),
    LOG_CHANGE_TITLE("Change what you ate", "Wijzig wat je at"),
    LOG_FOOD_GONE(
        "{0} is no longer among your foods, so it cannot be counted again. Type its numbers over, or remove it.",
        "{0} staat niet meer bij je voedingsmiddelen en kan dus niet opnieuw worden berekend. Typ de getallen opnieuw in, of verwijder het.",
    ),

    // --- Plan
    PLAN_NEW_FOR("New {0} for {1}", "Nieuw recept voor {0} op {1}"),
    PLAN_SET_UP_PROFILE(
        "Set up your profile first: the planner needs your daily calorie and protein targets.",
        "Stel eerst je profiel in: de planner heeft je dagelijkse calorie- en eiwitdoelen nodig.",
    ),
    PLAN_LOAD_FAILED("Could not load the plan", "Het weekplan kon niet worden geladen"),
    DAILY_TARGET("Daily target: {0} kcal · {1} g protein", "Dagdoel: {0} kcal · {1} g eiwit"),
    DAILY_TARGET_FIBRE("Daily target: {0} kcal · {1} g protein · {2} g fibre", "Dagdoel: {0} kcal · {1} g eiwit · {2} g vezels"),
    WEEK_COST("≈ {0} for the week's meals", "≈ {0} voor de maaltijden van deze week"),
    WEEK_COST_SAVED("≈ {0} for the week's meals, {1} saved by offers", "≈ {0} voor de maaltijden van deze week, {1} bespaard met aanbiedingen"),
    NO_RECIPES_FOR(
        "No recipes yet for: {0}. Days stay below the target until those exist.",
        "Nog geen recepten voor: {0}. De dagen blijven onder je doel tot die er zijn.",
    ),
    NOTHING_PLANNED_WEEK("Nothing planned for this week yet.", "Voor deze week staat nog niets gepland."),
    DAY_TOTALS("{0} of {1} kcal · {2} of {3} g protein", "{0} van {1} kcal · {2} van {3} g eiwit"),
    DAY_TOTALS_FIBRE(
        "{0} of {1} kcal · {2} of {3} g protein · {4} of {5} g fibre",
        "{0} van {1} kcal · {2} van {3} g eiwit · {4} van {5} g vezels",
    ),
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

    // --- Foods in a slot (issue 358)
    PLAN_ADD_FOOD("Add a food…", "Voedingsmiddel toevoegen…"),
    PLAN_REMOVE_FOODS("Remove from the plan", "Uit het plan halen"),
    PLAN_ADD_ANOTHER_FOOD("Add another food…", "Nog een voedingsmiddel toevoegen…"),
    FOODS_AND_MORE("{0} + {1} more", "{0} + {1} meer"),
    FOOD_AMOUNT("{0} · {1} kcal", "{0} · {1} kcal"),

    // --- Prepped meals (issue 234)
    PREP_A_RECIPE("Prep a recipe…", "Recept preppen…"),
    PREP_THIS_RECIPE("Prep this recipe…", "Dit recept preppen…"),
    PREP_TITLE("Prep", "Preppen"),
    PREP_RECIPE("Recipe", "Recept"),
    PREP_NO_RECIPES(
        "None of your recipes keeps yet. Set how many days a recipe keeps under \"Keeps once made\" in its settings.",
        "Nog geen van je recepten blijft goed. Stel bij een recept onder \"Blijft goed na het maken\" in hoeveel dagen.",
    ),
    PREP_MADE_ON("Made on", "Gemaakt op"),
    PREP_WAY("Kept", "Bewaard"),
    PREP_IN_A_BOX("In a box · {0}", "In een bakje · {0}"),
    PREP_VACUUM("Vacuum sealed · {0}", "Vacuüm · {0}"),
    PREP_EATEN_ON("Eaten on", "Gegeten op"),
    PREP_MEAL_HINT(
        "Cooked once on the day it is made, for at least two lunches or dinners up to its last day, the first on that day. Each one picked gets its portion of it, in place of what is planned there.",
        "Je kookt het één keer op de dag dat je het maakt, voor minstens twee lunches of avondeten tot de laatste dag, de eerste op die dag. Elke gekozen maaltijd krijgt er zijn portie van, in plaats van wat er gepland staat.",
    ),
    PREP_FIRST_ON_MADE_DAY("The first meal is on the day it is made.", "De eerste maaltijd is op de dag dat je het maakt."),
    PREP_TWO_MEALS("Pick at least two meals.", "Kies minstens twee maaltijden."),
    PREP_TWO_SIDES(
        "A prep is made once for at least two meals: pick a second lunch or dinner for the side to go with.",
        "Een prep maak je één keer voor minstens twee maaltijden: kies nog een lunch of avondeten voor het bijgerecht.",
    ),
    PREP_MADE_ON_PAST("The day it is made has passed: pick today or a later day.", "De dag waarop je het maakt is voorbij: kies vandaag of later."),
    PREP_PICK_RECIPE("Pick a recipe that keeps.", "Kies een recept dat goed blijft."),
    PREP_NOT_THIS_WAY("This recipe is not kept this way.", "Dit recept wordt niet zo bewaard."),
    PREP_SAVE("Prep", "Preppen"),
    REMOVE_PREP("Remove the prep…", "Prep verwijderen…"),
    REMOVE_PREP_TITLE("Remove the prep of {0}?", "De prep van {0} verwijderen?"),
    REMOVE_PREP_TEXT("This takes all its meals out of the plan: {0}.", "Hiermee gaan al zijn maaltijden uit het plan: {0}."),
    REMOVE_PREP_MADE_TITLE("The prep of {0} was made", "De prep van {0} is al gemaakt"),
    REMOVE_PREP_MADE_TEXT(
        "One of its meals was eaten, so the batch was cooked and stays in the plan. Mark the meals you do not eat skipped instead: {0}.",
        "Een van de maaltijden is gegeten, dus de prep is gekookt en blijft in het plan. Zet de maaltijden die je niet eet op overgeslagen: {0}.",
    ),
    REMOVE_PREP_SIDES_MADE_TEXT(
        "A meal with this side was eaten, so the batch was cooked and its sides stay in the plan. Mark the meals you do not eat it with skipped instead: {0}.",
        "Een maaltijd met dit bijgerecht is gegeten, dus de prep is gekookt en de bijgerechten blijven in het plan. Zet de maaltijden waarbij je het niet eet op overgeslagen: {0}.",
    ),
    REMOVE_PREP_MADE_NOTHING_LEFT(
        "One of its meals was eaten, so the batch was cooked and stays in the plan. All its meals are eaten or skipped already: there is nothing left to change.",
        "Een van de maaltijden is gegeten, dus de prep is gekookt en blijft in het plan. Alle maaltijden zijn al gegeten of overgeslagen: er valt niets meer te wijzigen.",
    ),
    SPARE_TITLE("A portion in the box: {0}", "Een portie in het bakje: {0}"),
    SPARE_KEEPS("Skipped on {0} · keeps until {1}", "Overgeslagen op {0} · goed tot {1}"),
    SPARE_LAST_DAY("Its last day: eat it today or throw it away.", "Laatste dag: eet het vandaag op of gooi het weg."),
    SPARE_EAT_ON("Eat it on…", "Eet het op…"),
    SPARE_EAT_PROPOSED("Eat it on {0}", "Eet het op {0}"),
    SPARE_NO_ROOM("No later lunch or dinner has room for it.", "Geen latere lunch of avondeten heeft er ruimte voor."),
    PREP_EATEN_AS("Eaten as", "Gegeten als"),
    PREP_AS_MEAL("The meal", "De maaltijd"),
    PREP_AS_SIDE("A side · {0}", "Bijgerecht · {0}"),
    PREP_SIDE_HINT(
        "A side goes next to the meal planned there, which keeps its own portion: only lunches and dinners with a meal still to eat and no side yet can be picked.",
        "Een bijgerecht komt naast de maaltijd die er gepland staat, die zijn eigen portie houdt: je kunt alleen lunches en avondeten kiezen met een maaltijd die nog gegeten moet worden en nog geen bijgerecht heeft.",
    ),
    PREP_MEAL_NOTES("{0} ({1})", "{0} ({1})"),
    PREP_NOTE_SIDE("side", "bijgerecht"),
    PREP_NOTE_SKIPPED("skipped", "overgeslagen"),
    PREP_NOTE_EATEN("eaten", "gegeten"),
    REMOVE_PREP_SIDES_TEXT("This takes all its sides out of the plan; the meals they go with stay: {0}.", "Hiermee gaan al zijn bijgerechten uit het plan; de maaltijden ernaast blijven: {0}."),
    MEAL_WITH_SIDE("With the side: {0}", "Met bijgerecht: {0}"),
    DAY_OVER_WITH_SIDES("This day is {0} kcal over the target; its sides add {1} kcal.", "Deze dag zit {0} kcal boven je doel; de bijgerechten zijn samen {1} kcal."),
    ADD_SIDE("Add a prepped side…", "Geprept bijgerecht toevoegen…"),
    REMOVE_SIDE("Remove the side", "Bijgerecht verwijderen"),
    REMOVE_SIDE_PREP("Remove the side's prep…", "Prep van het bijgerecht verwijderen…"),
    SPARE_SIDE_TITLE("A side in the box: {0}", "Een bijgerecht in het bakje: {0}"),

    // --- One batch eaten partly as meals and partly as sides (issue 269)
    PREP_EATEN_AS_HINT(
        "Each lunch or dinner you pick below is eaten as chosen here. Switch in between to have one batch as the meal on some days and as a side on others.",
        "Elke lunch of elk avondeten dat je hieronder kiest, eet je zoals hier gekozen. Wissel tussendoor om één prep op sommige dagen als maaltijd en op andere als bijgerecht te eten.",
    ),
    PREP_SLOT_SIDE("{0} · side {1}", "{0} · bijgerecht {1}"),
    REMOVE_PREP_MIXED_TEXT(
        "This takes its meals out of the plan: {0}. The meals with one of its sides keep their main and lose the side: {1}.",
        "Hiermee gaan de maaltijden ervan uit het plan: {0}. De maaltijden met een bijgerecht ervan houden hun hoofdgerecht en verliezen het bijgerecht: {1}.",
    ),
    REMOVE_PREP_MIXED_MADE_TEXT(
        "One of its meals, or a meal with one of its sides, was eaten, so the batch was cooked and stays in the plan. Mark the meals you do not eat, or do not eat it with, skipped instead: {0}.",
        "Een van de maaltijden ervan, of een maaltijd met een bijgerecht ervan, is gegeten, dus de prep is gekookt en blijft in het plan. Zet de maaltijden die je niet eet, of waarbij je het niet eet, op overgeslagen: {0}.",
    ),
    ADD_SIDE_TITLE("A side for {0}", "Bijgerecht bij {0}"),
    ADD_SIDE_HINT(
        "From a prep still to make: it grows by the side, and is bought and cooked with it.",
        "Uit een prep die nog gemaakt moet worden: die wordt groter met het bijgerecht, en wordt er zo voor gekocht en gekookt.",
    ),
    ADD_SIDE_FROM("From the prep of", "Uit de prep van"),
    ADD_SIDE_BATCH("Made {0} · keeps until {1}", "Gemaakt {0} · goed tot {1}"),
    ADD_SIDE_SIZE("The side", "Het bijgerecht"),
    ADD_SIDE_NEW_PREP("Prep a new side…", "Nieuw bijgerecht preppen…"),

    // --- Prepped meals, the last rough edges (issue 263): a batch kept vacuum sealed says so on its meals and sides
    PLAN_CHOOSE_FOR("Recipe for {0} on {1}", "Recept voor {0} op {1}"),
    PLAN_CHOOSE_UNSUPPORTED(
        "The server cannot put a chosen recipe in the plan yet. It is updated overnight; try again tomorrow.",
        "De server kan nog geen gekozen recept in het plan zetten. Hij wordt 's nachts bijgewerkt; probeer het morgen opnieuw.",
    ),

    // --- Recipes: my recipes, the rotation and the bank
    ROTATION_EMPTY_PLAN(
        "Your rotation is empty, so the planner has nothing to pick from. Put recipes in it on the Recipes tab.",
        "Je rotatie is leeg, dus de planner heeft niets om uit te kiezen. Zet recepten in de rotatie op het tabblad Recepten.",
    ),
    OPEN_RECIPES("Open recipes", "Open recepten"),

    // --- A meal that keeps its recipe, on the Plan (issue 191): why its menu has no other recipe
    KEPT_EATEN("Eaten, so its recipe stays", "Gegeten, dus het recept blijft"),
    KEPT_DINNER_EATEN("Cooked with the dinner that was eaten, so it stays", "Gekookt met het avondeten dat gegeten is, dus het blijft"),
    KEPT_BATCH_MADE("From a prep that was made, so it stays", "Uit een prep die gemaakt is, dus het blijft"),
    KEPT_MAKES_BATCH("It makes a prep, so it stays: remove the prep instead", "Het maakt een prep, dus het blijft: verwijder de prep"),

    // --- Standard meals (issue 265): the recipe's switch, the chips where a meal is skipped, the picker
    STANDARD_MEAL("Standard meal: I can always make this", "Standaardmaaltijd: kan ik altijd maken"),
    STANDARD_ON_HINT(
        "Offered in one tap when you skip a meal it is planned as; its ingredients are staples",
        "Met één tik aangeboden als je een maaltijd overslaat waarvoor het gepland wordt; de ingrediënten zijn basis",
    ),
    STANDARD_OFF_HINT(
        "For a meal you can always make from what is at home: it is offered when you skip one, and its ingredients become staples",
        "Voor een maaltijd die je altijd kunt maken van wat in huis is: aangeboden als je er een overslaat, en de ingrediënten worden basis",
    ),
    STANDARD_ADDED_STAPLES("Now staples: {0}", "Nu basis: {0}"),
    STANDARD_NONE_ADDED("Its ingredients were staples already", "De ingrediënten waren al basis"),
    STANDARD_UNLINKED("Not linked to an ingredient, so not a staple: {0}", "Niet aan een ingrediënt gekoppeld, dus geen basis: {0}"),

    // The number and its unit stay on one line (a no-break space).
    STANDARD_CHIP("{0} · {1}\u00A0kcal", "{0} · {1}\u00A0kcal"),
    STANDARD_MISSING("no {0} at home", "geen {0} in huis"),
    STANDARD_OR("or", "of"),
    STANDARD_INSTEAD("Instead, one of my standard meals:", "In plaats daarvan, een van mijn standaardmaaltijden:"),
    STANDARD_RESTOCK("Back among the staples, to restock: {0}", "Weer bij de basis, om aan te vullen: {0}"),
    SKIP_SHEET_TITLE("Skipped: {0}", "Overgeslagen: {0}"),
    SKIP_SHEET_TEXT("Eat one of your standard meals instead?", "Een van je standaardmaaltijden eten in plaats daarvan?"),
    SKIP_SHEET_LEAVE("Leave it empty", "Leeg laten"),
    REMOVE_STANDARD_MEAL("Remove from the plan", "Uit het plan halen"),
    REMOVE_STANDARD_TITLE("Remove {0} from the plan?", "{0} uit het plan halen?"),
    REMOVE_STANDARD_TEXT(
        "The meal is taken out and its place stays empty, whether you put it there or the planner did.",
        "De maaltijd gaat eruit en de plek blijft leeg, of je hem er zelf zette of de planner.",
    ),
    PICKER_SHRINKS_PREP(
        "This meal is from a prepped batch still to make: another recipe here makes the batch smaller.",
        "Deze maaltijd komt uit een prep die nog gemaakt wordt: een ander recept hier maakt de prep kleiner.",
    ),

    // --- Adjusting a meal to what was in it (issue 226)
    ADJUST("Adjust", "Aanpassen"),
    ADJUST_WHAT_WAS_IN_IT("Adjust what was in it", "Aanpassen wat erin zat"),
    ADJUST_TITLE("What was in it", "Wat erin zat"),
    ADJUST_EXPLAIN(
        "Change, leave out or add what was in this meal; it counts that. The recipe stays as it is.",
        "Verander, laat weg of voeg toe wat er in deze maaltijd zat; die telt dat. Het recept blijft zoals het is.",
    ),
    ADJUST_RESET("Reset to recipe", "Terug naar het recept"),
    ADJUST_RESET_DONE("Back to the recipe", "Weer volgens het recept"),
    ADJUST_ADD("Add an ingredient", "Ingrediënt toevoegen"),
    ADJUST_IN_MEAL("In this meal", "In deze maaltijd"),
    ADJUST_LEFT_OUT("Left out", "Weggelaten"),
    ADJUST_LEFT_OUT_DONE("{0} left out", "{0} weggelaten"),
    ADJUST_REMOVED_DONE("{0} removed", "{0} verwijderd"),
    ADJUST_PUT_BACK("Put back", "Terugzetten"),
    ADJUST_LEAVE_OUT("Leave out {0}", "{0} weglaten"),
    ADJUST_WAS("recipe: {0}", "recept: {0}"),
    ADJUST_ADDED("added", "toegevoegd"),
    ADJUST_NOT_LINKED("Not one of your ingredients, so not counted and not changeable", "Geen van je ingrediënten, dus niet meegeteld en niet aan te passen"),
    ADJUST_AMOUNT_UNKNOWN("amount not known", "hoeveelheid onbekend"),
    ADJUST_INCOMPLETE("Counted as nothing, no nutrition known: {0}", "Telt als niets, geen voedingswaarden bekend: {0}"),
    ADJUST_LOAD_FAILED("Could not load this meal.", "Deze maaltijd kon niet worden geladen."),
    ADJUST_NO_MATCH("No ingredient matches", "Geen ingrediënt gevonden"),
    AMOUNT_OF("How much {0}?", "Hoeveel {0}?"),
    AMOUNT_PIECES("Pieces", "Stuks"),
    AMOUNT_GRAMS("Grams", "Gram"),
    ADJUST_QUICK("Quick add", "Snel toevoegen"),
    ADJUST_ADDED_DONE("{0} added", "{0} toegevoegd"),
    ADJUST_SHORT_TITLE("Not enough left for a later meal", "Niet genoeg over voor een latere maaltijd"),
    ADJUST_SHORT("{0}: {1} short for {2}", "{0}: {1} te weinig voor {2}"),
    ADJUST_SHORT_MEAL("{0}, {1} {2}", "{0}, {1} {2}"),
    ADJUST_SHORT_LISTED(
        "This meal used what was at home for it, so the shopping list asks for it again.",
        "Deze maaltijd gebruikte wat daarvoor in huis was, dus het boodschappenlijstje vraagt er weer om.",
    ),

    // --- Planning the week (issue 255): the planning screen, its suggestions and strip, "Another recipe" for a dinner, moving a dinner
    PLAN_THE_WEEK("Plan the week", "Plan de week"),
    PLANNING_LOAD_FAILED("Could not load the planning", "De planning kon niet worden geladen"),
    PLANNING_OLD_SERVER(
        "This server plans the week in one go: update it to choose the dinners yourself.",
        "Deze server plant de week in één keer: werk hem bij om zelf het avondeten te kiezen.",
    ),
    PLANNING_ALL_CHOSEN("A dinner is chosen for every day that is open.", "Voor elke open dag is een avondeten gekozen."),
    PLANNING_NO_OPEN_DAYS("Every day from today on has a dinner that stays.", "Elke dag vanaf vandaag heeft al een avondeten dat blijft."),
    PLANNING_WEEK_OVER("This week is over: there is nothing left to plan in it.", "Deze week is voorbij: er valt niets meer in te plannen."),
    PLANNING_STRIP_TITLE("The week so far", "De week tot nu toe"),
    PLANNING_STRIP_NOTE(
        "The meals that stay and the dinners chosen, with their leftovers, against what those meals should give.",
        "De maaltijden die blijven en het gekozen avondeten, met restjes, tegenover wat die maaltijden zouden moeten geven.",
    ),
    PLANNING_STRIP_EMPTY("Nothing chosen yet: tap a suggestion to add it.", "Nog niets gekozen: tik op een suggestie om die toe te voegen."),
    PLANNING_TOTALS("{0} of {1} kcal · {2} of {3} g protein", "{0} van {1} kcal · {2} van {3} g eiwit"),
    PLANNING_TOTALS_FIBRE(
        "{0} of {1} kcal · {2} of {3} g protein · {4} of {5} g fibre",
        "{0} van {1} kcal · {2} van {3} g eiwit · {4} van {5} g vezels",
    ),
    PLANNING_CHOSEN_COUNT("Chosen dinners ({0})", "Gekozen avondeten ({0})"),
    PLANNING_CHOSEN_SHOW("Show", "Tonen"),
    PLANNING_CHOSEN_HIDE("Hide", "Verbergen"),
    PLANNING_TAKE_OUT("Take {0} out", "{0} weghalen"),
    PLANNING_NOT_PLANNABLE("Has no amounts any more, so it can't be planned: take it out", "Heeft geen hoeveelheden meer, dus kan niet worden gepland: haal het weg"),
    PLANNING_SUGGESTIONS("Suggestions", "Suggesties"),
    PLANNING_SHOW_OTHERS("Show others", "Andere tonen"),
    PLANNING_SOMETHING_ELSE("Something else", "Iets anders"),
    PLANNING_SEARCH("Search my recipes", "Zoek in mijn recepten"),
    PLANNING_SEARCH_NONE("None of your meals has that in its title.", "Geen van je maaltijden heeft dat in de titel."),
    PLANNING_BACK_TO_SUGGESTIONS("Back to the suggestions", "Terug naar de suggesties"),

    // No suggestion at all, before any was shown: why, and where to find more (the Recipes tab).
    PLANNING_NONE(
        "Nothing to suggest: none of your recipes in the rotation makes a dinner that is not chosen or set to Not this week already. Add recipes, or put yours in the rotation, under Recipes; Something else searches all of them.",
        "Niets om voor te stellen: geen van je recepten in de rotatie geeft een avondeten dat nog niet gekozen of op Deze week niet gezet is. Voeg recepten toe, of zet de jouwe in de rotatie, onder Recepten; Iets anders zoekt in al je recepten.",
    ),
    PLANNING_NO_MORE(
        "No more suggestions. Search all your recipes, or start again from the first.",
        "Geen suggesties meer. Zoek in al je recepten, of begin opnieuw bij de eerste.",
    ),
    PLANNING_START_AGAIN("Start again", "Opnieuw beginnen"),
    PLANNING_NOT_THIS_WEEK("Not this week", "Deze week niet"),
    PLANNING_SHOW_AGAIN("Show again", "Weer tonen"),
    PLANNING_DONE("Done", "Klaar"),
    PLANNING_FILL_REST("Fill the rest", "Vul de rest aan"),
    PLANNING_CHOOSE("Choose", "Kiezen"),
    PLANNING_ALREADY_CHOSEN("✓ Chosen for this week", "✓ Gekozen voor deze week"),

    // A suggestion's reasons, one line each: {0} of ON_OFFER is the offers, each OFFER or OFFER_DEAL; of FAVOURITE, RATED and WEEKS.
    REASON_ON_OFFER("On offer: {0}", "In de aanbieding: {0}"),
    REASON_OFFER("{0} at {1}", "{0} bij {1}"),
    REASON_OFFER_DEAL("{0} at {1} ({2})", "{0} bij {1} ({2})"),
    REASON_THERE("Uses what is there: {0}", "Gebruikt wat er is: {0}"),
    REASON_PACK_REST("the rest of the {0} from {1}", "de rest {0} van {1}"),
    REASON_FAVOURITE("Favourite: {0}", "Favoriet: {0}"),
    REASON_RATED("rated {0} of 5", "{0} van 5 sterren"),
    REASON_NOT_TRIED("Not tried yet", "Nog niet geprobeerd"),
    SUGGESTION_PRICE("{0} a serving", "{0} per portie"),
    SUGGESTION_PRICE_SAVED("{0} a serving, {1} saved", "{0} per portie, {1} bespaard"),
    ANOTHER_DINNER_TITLE("Another dinner for {0}", "Ander avondeten voor {0}"),
    PLANNERS_CHOICE("Let the planner choose", "Laat de planner kiezen"),
    MOVE_TO_DAY("Move to another day…", "Naar een andere dag…"),
    MOVE_TITLE("Move {0} to", "{0} verplaatsen naar"),
    MOVE_SWAPS("swaps with {0}", "ruilt met {0}"),
    MOVE_NO_DINNER("no dinner yet", "nog geen avondeten"),

    // Why a day of the move dialog can't be chosen: {0} is the dinner there, {1} one of the MOVE_WHY reasons.
    MOVE_STAYS("{0} stays on its day: {1}", "{0} blijft op zijn dag: {1}"),
    MOVE_DAY_PASSED("this day has passed", "deze dag is voorbij"),
    MOVE_WHY_EATEN("it was eaten", "het is gegeten"),
    MOVE_WHY_BATCH_MADE("it is from a prep that was made", "het komt uit een prep die gemaakt is"),
    MOVE_WHY_MAKES_BATCH("it makes a prep", "het maakt een prep"),
    MOVE_WHY_PREPPED("it is a meal of a prep", "het hoort bij een prep"),
    MOVE_WHY_SIDE("it has a prepped side", "het heeft een geprept bijgerecht"),
    MOVE_WHY_LEFTOVERS("its leftovers can't follow it", "de restjes kunnen niet mee"),
    ;
}

/** The texts of the plan, planning and today screens, adjusting a meal, preps, standard meals and meal changes that depend on a count, in English and Dutch. */
enum class PlanPlural(
    override val englishOne: String,
    override val englishOther: String,
    override val dutchOne: String,
    override val dutchOther: String,
) : Plural {
    /** Planning the week (issue 255): the dinners left to choose, what Continue planning says, and a favourite's weeks. */
    DINNERS_TO_CHOOSE("{0} dinner still to choose", "{0} dinners still to choose", "Nog {0} avondmaaltijd te kiezen", "Nog {0} avondmaaltijden te kiezen"),
    CONTINUE_PLANNING(
        "Continue planning: {0} dinner chosen", "Continue planning: {0} dinners chosen",
        "Verder plannen: {0} avondmaaltijd gekozen", "Verder plannen: {0} avondmaaltijden gekozen",
    ),
    REASON_WEEKS("not eaten for {0} week", "not eaten for {0} weeks", "{0} week niet gegeten", "{0} weken niet gegeten"),

    /** The strip's cost (issue 255): {0} is how many meals it counts, a chosen dinner's leftover lunch among them; {1} the euros, {2} the saving. */
    PLANNING_COST("≈ {1} for {0} meal", "≈ {1} for {0} meals", "≈ {1} voor {0} maaltijd", "≈ {1} voor {0} maaltijden"),
    PLANNING_COST_SAVED(
        "≈ {1} for {0} meal, {2} saved by offers", "≈ {1} for {0} meals, {2} saved by offers",
        "≈ {1} voor {0} maaltijd, {2} bespaard met aanbiedingen", "≈ {1} voor {0} maaltijden, {2} bespaard met aanbiedingen",
    ),

    /** For an amount that may be fractional, as [SERVINGS_AMOUNT]: `1.5 pieces`. */
    PIECES_AMOUNT("{1} piece", "{1} pieces", "{1} stuk", "{1} stuks"),
    ;
}
