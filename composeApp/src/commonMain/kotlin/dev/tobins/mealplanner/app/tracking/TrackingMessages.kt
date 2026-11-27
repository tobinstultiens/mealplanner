package dev.tobins.mealplanner.app.tracking

import dev.tobins.mealplanner.app.Msg

/** The texts of the progress screen and the logged foods, in English and Dutch side by side. */
enum class TrackingMsg(override val english: String, override val dutch: String) : Msg {
    // --- Tabs
    TAB_PROGRESS("Progress", "Voortgang"),

    // --- Foods logged as eaten outside the plan (issue 362)
    // The food screen's bar has one line, which must fit at the largest font.
    LOG_CHANGE_FOOD_TITLE("Change food", "Wijzig eten"),

    // --- Progress
    WEIGHT_LOAD_FAILED("Could not load your weight log", "Je gewichtslogboek kon niet worden geladen"),
    TODAYS_WEIGHT("Today's weight (kg)", "Gewicht vandaag (kg)"),
    WEIGH_HINT(
        "Weigh yourself at the same time each day, for example after getting up. After two weeks the trend shows whether the calorie target fits.",
        "Weeg jezelf elke dag op hetzelfde moment, bijvoorbeeld na het opstaan. Na twee weken zie je aan de trend of je caloriedoel past.",
    ),
    TARGET_NOW("Calorie target is now {0} kcal", "Je caloriedoel is nu {0} kcal"),
    TARGET_CHANGE_FAILED("Could not change the target", "Het doel kon niet worden aangepast"),
    WEIGHING_REMOVED("Weigh-in of {0} removed", "Meting van {0} verwijderd"),
    WEIGHT_KEPT(
        "Your last weigh-in is removed. Your profile keeps the weight it has now: if a mistyped weigh-in set it, correct it on the profile.",
        "Je laatste meting is verwijderd. Je profiel houdt het gewicht dat het nu heeft: als een verkeerd getypte meting het zette, verbeter het dan in je profiel.",
    ),
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

    // --- The web client's last resort and its own wording (browser, not phone)
    CHART_HINT_BROWSER(
        "Line: trend. Dots: daily weighings. Click the chart to read a day.",
        "Lijn: trend. Stippen: dagelijkse metingen. Klik op de grafiek om een dag af te lezen.",
    ),
    ;
}
