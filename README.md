# Meal planner

Plans a week of cheap, healthy, high-protein meals around calorie, protein and fibre targets,
using weekly offers from AH, Jumbo and Lidl and recipes from the cooking site.

| Module | Contents |
|---|---|
| `shared` | Domain model, API types, units, Hugo recipe parser. Kotlin Multiplatform (JVM + Android). |
| `server` | Ktor API with a SQLite database (SQLDelight). |
| `composeApp` | Compose Multiplatform UI for Android and the web (WebAssembly). |
| `androidApp` | Thin Android application that hosts `composeApp`. |

## Requirements

- JDK 17 to run Gradle (`JAVA_HOME=/usr/lib/jvm/java-17-openjdk` on this machine).
- Android SDK with platform 37 (the build downloads it if licences are accepted).

## Commands

```bash
./gradlew build                      # compile everything, run all tests, assemble the debug APK
./gradlew :shared:jvmTest            # parser and domain tests only
./gradlew :server:test               # API tests (in-memory SQLite)
./gradlew :server:shadowJar          # fat jar at server/build/libs/server-all.jar
./gradlew :androidApp:testDebugUnitTest  # the real MainActivity starts (Robolectric, AppStartTest)
./gradlew :androidApp:installDebug   # install on a connected device or emulator
docker build -t mealplanner:dev .    # needs the fat jar built first
```

Run the server locally:

```bash
python3 scripts/dev-oidc-provider.py &   # a stand-in for Authelia on localhost:8090, username only
MEALPLANNER_OIDC_ISSUER=http://localhost:8090 COOKING_REPO_PATH=../cooking \
  NEVO_CSV_PATH=data/nevo/NEVO2025_v9.0.csv \
  java -jar server/build/libs/server-all.jar
```

For the app on an emulator, `adb reverse tcp:8080 tcp:8080` and `adb reverse tcp:8090 tcp:8090`
make both reachable as `localhost`, so the app and the server agree on the issuer; set the server
address to `http://localhost:8080` in the app's settings.

## Accounts and login

Users log in with an OpenID Connect provider (Authelia in the homelab); every account it knows may
use the app and gets its own recipes, ingredient catalogue, pantry, profile, plans, shopping lists,
log and weights. Store offers, regular prices, their history and the NEVO data are shared.

- Clients use the authorization code flow with PKCE as the public client `mealplanner`; the server
  tells them where to log in (`GET /api/auth/config`). The web client returns to `/`, the Android app
  to `/app/login`: an App Link (`/.well-known/assetlinks.json`, from `MEALPLANNER_ANDROID_CERTS`), and
  when Android does not open the app for it, that page sends the browser on to the app's own scheme.
  Both stay: a Custom Tab hands nothing back to the app by itself, and installed apps up to v0.1.4 use them.
- Logged out, the apps show a login screen of their own: the app's name, Log in, the server it logs in
  to (with "Change server", which opens the settings; while the screen asks for the address instead, a
  "Settings" link, so language and theme can be set first) and why the last session ended (logged out, the
  login ran out or was ended, or it could not be renewed for a while). Saving another server ends the
  session, as it has its own accounts; the screen then says "You are logged out." only when there was a
  session to end, and nothing on an install that never logged in. They open the login page by
  themselves once per logged-out state: on a fresh install (or the first start logged out after an
  update) with a known server. Once that page was opened, closed, or answered, after logging out or
  choosing another server, and after a session that ended by itself, only the Log in
  button opens it (kept in storage, so neither Back, a rotation, the app returning to the front, a
  re-created screen nor a restart opens it again). Android opens the page in a Custom Tab over the app
  (`androidx.browser`), or in the browser when no browser offers Custom Tabs; never in a WebView, and the
  app never sees the password. The tab closes when the answer reaches the app.
- The answer goes to `LoginExchange` (`Login.kt`), which lives as long as the process (Android) or the
  page (web), not the screen: the token exchange finishes when Android re-creates the screen during it,
  and the screen that is there then takes the result. One answer is exchanged at most once.
- Logging out or saving another server calls off a login under way, which may belong to the old server
  (issue 198): a running token exchange is cancelled and saves nothing, a login page still open answers in
  vain (its answer is let pass), and a login still asking the server how to log in opens no page. A renewal
  answered after the log-out, or after another login, is dropped as well. Log in waits while a login is being
  started or exchanged, in the settings as on the login screen.
- An access token the server refuses is renewed with the refresh token. When the provider refuses that
  (400 or 401, or there is no refresh token) the session ends at once on the login screen, saying the login
  ran out or was ended. Another error from the token endpoint ends it only when it goes on (issue 198): on
  the third failed renewal in a row, when the first was at least 15 minutes earlier for another client error
  (403, 404, …; a proxy's 404 while the provider restarts lasts a minute or two) or at least 24 hours earlier
  for a provider in trouble (5xx, 408, 429). "In a row" means without a renewal that worked in between; the
  time is counted from the first failure, not from when the access token expired, since a token unused for a
  week expired a week ago and one hiccup then must not end the session. The login screen then says the login
  could not be renewed for a while. A renewal without an answer (a network error) or without the server's
  login settings ends nothing, and neither counts nor starts the count afresh; until the session ends, the
  next request tries again. Ending a session forgets the shopping lists stored for it, with their unsent ticks.
- Renewals go one at a time through `LoginExchange.renew`, whoever asks: the screens and the Android app's
  background check of the notifications (issue 225). A provider that rotates refresh tokens takes each one
  once, so two renewals at the same moment would end the session; a caller whose refused token was replaced
  meanwhile gets the stored session without asking the provider again, but only while that session's token
  runs out more than 5 minutes from now by the device's clock (the client retries a request once, so a token
  that has run out too would fail it; the margin covers the retry and a server clock ahead of the device's).
  Otherwise, and whenever the refused token is the stored one, the stored session is renewed. The background check renews
  quietly: a refusal or an error there ends nothing and is not counted towards the rule above, since nobody
  is there to log in again and a provider down overnight must not log the user out behind their back; the
  screens find out at their next request and decide as above. A renewal there that works is kept, and ends a
  run of failures as any renewal that works does.
- The server accepts JWT access tokens (RS256) from that issuer issued to that client, checked against
  the provider's keys. The token's subject is the account; new accounts start with the default
  ingredient catalogue. `GET /api/me` names the caller.
- Data from before accounts existed belongs to account 1, which `MEALPLANNER_OWNER` (a username)
  claims on first login, or else the first account to log in.
- The server's cooking site (`COOKING_REPO_URL`, or the checkout at `COOKING_REPO_PATH`) is the bank of every
  account that has not chosen otherwise (issue 208); with `COOKING_REPO_SHARED=0` it is the owner's alone, as
  before. On the recipe bank screen an account chooses where its bank comes from (`PUT /api/sync/cooking/repo`):
  the server's site (`{"url": null}`), a git repository of its own (`{"url": "https://…"}`, cloned under
  `<data dir>/users/<id>/cooking`), or none (`{"url": null, "noSite": true}`). An account that had set a
  repository of its own before keeps it. `GET /api/sync/cooking` says which is in use: `shared` (the server's
  site), `serverSite` (the server offers its site to this account) and `serverRepoUrl` (its address, to name it).
  The server's address is shown to accounts other than the owner without a user name or password in it, and so
  are the errors of its fetch.
- The server's site is one clone, fetched once every six hours and then imported into each account that reads
  it, as rows of the account's own: adding, rating, the rotation and the ingredient links stay per account, and
  an edit on the site reaches every account. A repository of an account's own is fetched as often. A new account
  finds the server's site in its bank on its first visit there (`GET /api/recipes/bank` imports it when the account
  was never synced), without waiting for the schedule. `POST /api/sync/cooking` fetches the account's site at once;
  the server's site at most once every five minutes (`COOKING_REPO_FETCH_INTERVAL_MINUTES`; issue 215), as every
  account's imports wait while it is fetched. A sync within five minutes of the last fetch (by any account, the
  schedule, or one that failed) imports the clone as it is, without an error, and `fetchedAt` in
  `GET /api/sync/cooking` says when the server's site was last fetched. Without a clone yet (the first fetch
  failed) such a sync fails at once rather than trying again. The accounts that waited for a fetch then import
  at once; their imports write one after another, each in a transaction of its own, so one account's failure
  takes back nothing of another's (issue 246).
- Choosing another site leaves the old one at once: its bank-only recipes leave the bank, those the account added
  stay among its recipes as no longer on the site (until the new site has them), and its pictures go; the new site
  is imported by the sync the app asks for next (or, for the server's site, the next visit to the bank). A
  sync also keeps a web-sized copy of each recipe's picture (see `docs/data-sources.md`, "Recipe pictures") per
  account, served only to that account. The site's recipes go to the account's bank, not straight into its
  recipes: see the next section.

## Recipes: the bank, my recipes and the rotation

The cooking site is a bank of recipes to choose from, not the list of what an account cooks (issue 205).

- **The bank** is every recipe on the account's cooking site that it has not added (`GET /api/recipes/bank`).
  A sync puts a new file's recipe there. Bank recipes can be read, rated and have their pictures, but
  nothing else uses them: they are not in the recipe list, the planner never picks them, they cannot be
  put in a meal by hand (409 "Add … to your recipes first") or pinned as a routine, and their ingredient
  texts are not linked automatically nor listed under "Link ingredients". Their nutrition comes from what
  the account has linked already, so it is often incomplete (and says so).
- **My recipes** are the bank recipes the account added, plus those it made in the app or had Claude write
  (`GET /api/recipes`). Adding one (`PUT /api/recipes/{id}/added` with `{"added": true}`) links its
  ingredients that are exactly the name of a NEVO food, as a sync does. Putting one back
  (`{"added": false}`) returns it to the bank with its rating, kind and slots kept for when it is added
  again; that is refused (409, naming the days) while it is planned this week or later, and while it is
  the breakfast or snack routine. A recipe made in the app has no bank to go back to: it is deleted
  (`DELETE /api/recipes/{id}`), and putting it back is refused. Ingredient amounts may be written with a decimal
  comma (`0,5 | kg | banaan`); a line that cannot be read is refused (400) with `Unreadable ingredient line: ...`,
  one per line of the error, and the app shows those lines. An added recipe whose file leaves the
  site stays one of my recipes, marked `removedFromSite`, and keeps working as before; it loses its
  picture with the file. A bank recipe whose file leaves the site is hidden until the file is back.
- **The rotation** is the part of my recipes the planner picks from on its own: planning or replanning a
  week, "Another recipe" and the buy-ahead advice for an unplanned week. Any of my recipes can still be
  put in a meal by hand. Adding a recipe puts it in the rotation unless the request says
  `"inRotation": false`; recipes made in the app start in it. Taking one out
  (`PUT /api/recipes/{id}/rotation` with `{"inRotation": false}`) leaves the meals planned with it as they
  are. Pinning a routine puts its recipe in the rotation, and a routine cannot leave it (409).

`PUT /api/recipes/added` and `PUT /api/recipes/rotation` do the same for many recipes at once
(`{"ids": [1, 2, 3], "added": false}`) and answer my recipes; if one of them cannot be changed (another
account's: 404; planned or a routine: 409), none is. Whether a recipe is added and in the rotation is the
account's: a sync never changes it, nor does saving a recipe's settings. `RecipeSummary` says it in `added`,
`inRotation` and `removedFromSite`, which default to what servers before the bank meant (`true`, `true`,
`false`). Recipes there before the bank (database version 17, `16.sqm`) stayed added and in the rotation,
except those whose file had already left the site, which went to the bank, hidden: an installed app lists
what it listed before. Apps from before the bank show my recipes only, so a recipe new on the site appears
in them once it is added from a newer app.

## Portion per meal

The planner sizes every meal to its slot's share of the daily calories (breakfast 22 %, lunch 30 %,
dinner 38 %, snack 10 %) in quarter servings, within the profile's range: `Profile.minPortions` to
`maxPortions`, 0.75 to 1.5 servings unless set otherwise on the profile screen ("Portion per meal",
issue 227). A range runs from 0.25 to 3 servings in quarter steps, the smallest no larger than the
largest; anything else is a 400. All the rules are in `PortionRange`:

- A recipe that cannot reach half its slot's calories at the largest portion is not a meal there: the
  planner does not pick it on its own, nor does the buy-ahead advice. When nothing in the rotation fits,
  the slots stay empty and the plan says so (`emptySlots`).
- Breakfast and snack go up to 2 servings even when the range stops lower: a bowl of quark written as
  one serving is simply made larger.
- A leftover lunch gets what the dinner left, at most the largest portion; less than the smallest one
  (down to half a serving) rather than nothing.
- A recipe put in a meal by hand is sized within the range too, also one too light to be a meal there.
- Meals already planned keep their portions; a new range counts from the next plan, "Another recipe" or
  choice.

An app from before the range saves a profile without its two fields; the server keeps the stored range
then, rather than going back to the defaults.

## Prepped meals

A recipe can be made once and eaten over several days (issue 234), as the meals themselves or as a side next to
them. Only by hand: the planner never starts a prep nor adds a side, it plans around them.

- **How long it keeps.** A recipe's settings hold two numbers of days, per account: in a normal box and vacuum
  sealed, each 1 to 7 (a cooked meal keeps a week at most, so a batch reaches into the next plan week, never
  further). Empty means not for prepping that way. `PUT /api/recipes/{id}/keeps` with `{"days": 3, "vacuumDays":
  6}` (null for a way it is not prepped) answers the recipe; `RecipeSummary.keepsDays` and `keepsVacuumDays` show
  them. Its own route, so a released app saving `PUT .../settings` leaves them alone. 400 outside 1 to 7, 404 for
  another account's recipe.
- **Making a prep.** `POST /api/plans/{weekStart}/preps` with `{"recipeId": 12, "madeOn": "2026-11-17", "vacuum":
  false, "meals": [{"date": "2026-11-17", "slot": "DINNER"}, {"date": "2026-11-18", "slot": "LUNCH"}]}` answers the
  week `{weekStart}`. The made-on day is also the first meal; the meals are lunches and dinners, mixed, up to the
  made-on day plus the days it keeps (the last day, `MealPrep.lastDay`). Each meal gets the portion the planner
  would give that slot within the profile's range ("Portion per meal"); the batch is their sum. A slot may be empty
  or hold a meal that may be replaced; a dinner's leftover lunch goes with it. 400 for fewer than two meals, a
  breakfast or snack, a first meal on another day; 409 when the made-on day has passed (the server's today), the
  recipe does not keep that way, a meal is past the last day, a slot's meal was eaten or is of another prep; 404 for
  another account's recipe.
- **In the plan.** Every meal of a prep has `PlannedMeal.prep` (`MealPrep`: the made-on day, the last day, normal
  or vacuum, and every meal of the batch with its share and status, also those of the next week) and is locked.
  The made-on day's meal cooks the whole batch (`cookPortions`, so the recipe and cook mode show the batch's
  amounts), also when that meal itself was skipped; the later ones are `leftover` with nothing to cook. Each day
  counts its own portion, on the Plan and on Today. Generating or replanning keeps them and plans around them: a
  prepped dinner leaves no leftover lunch, and every meal or side of a batch in the week counts as a use of its recipe
  (also those of a batch made the week before), so the planner does not plan that recipe again next to it.
  The made-on day's meal can be neither replaced nor removed on its own (409, naming the later meals: `DELETE
  /api/plans/{weekStart}/preps/{id}` removes the prep with all its meals, in every week, after the app named
  them); a later meal can, which makes the batch smaller, until the batch is made. Unlocking a prepped meal is a 409.
- **Bought and taken once.** The shopping list of the made-on day's week buys the whole batch, also for meals the
  week after, which buys nothing for them; the planner's pack rest counts it as one purchase. The batch is made
  when the first of its meals is eaten, whichever: each meal's share is then taken from the stock of the made-on
  week, and un-eating the last eaten meal gives all of it back (`docs/data-sources.md`, "Un-eating"). Once made,
  the batch and its meals stay (409 for removing): a meal not eaten is skipped.
- **A skipped meal's share** stays in the batch. `GET /api/preps/spare/{date}` lists the shares still in the box on
  that date (`SparePortion`), each with the later lunches and dinners up to its last day that have room (empty, or
  a meal still to eat that is not locked and of no prep). On its last day it is to eat that day or throw away; from
  the day after, it is not listed, and it is never planned on its own. `POST
  /api/plans/{weekStart}/preps/{id}/spare` with `{"from": {"date": ..., "slot": ...}, "to": {...}}` eats it there:
  the meal there goes, the skipped meal keeps a share of 0 (and can no longer be marked eaten), and what the stock
  gave for it goes along, so the batch, the list and the stock stay as they were. The morning after the last day,
  the "Prepped food to throw away" notification (issue 264) names what may still be in a box: the shares of skipped
  meals or sides not eaten elsewhere, and the meals of the batch never marked. A batch never made (no meal of it
  eaten) has nothing in a box and is not named.
- **Into the next week.** A prep may run over the week's end. Its meals are in the next week before that week is
  planned, and generating it keeps them; a week with only those counts as not planned yet (the "Plan next week"
  notification, buying ahead, whose suggested dinners are then no more than the dinners those meals leave free).
  Today's "Dinner today" notification is only for a dinner with something to cook: not for a prepped later dinner.
  A shortfall warning ("What was in a meal") counts what the batch took for its meals of the next week in the week
  it was made, whose stock it was cooked from.
- **Sides.** `PrepInput.side` (0.25 or 0.5, else 400) makes the batch a side of that many servings next to the
  meal in each of its slots, which must hold a meal still to eat without a side (409 naming the slot otherwise).
  The meal keeps its main and its portion: the main is not resized, and the day may go over its calories, which
  the app says. A meal has at most one side, `PlannedMeal.side` (`MealSide`: the recipe, its portions, its kcal,
  protein, fibre and cost from the recipe as it is now, `cookPortions` and the batch, whose `PrepMeal.side` marks
  the sides). The meal's own numbers stay the main's; the day's totals (`PlanDay`, `DayView`) and the week's cost
  count the side too. The side is eaten, skipped or planned with its meal, so a batch eaten as sides is made when
  the first meal with one of its sides is eaten; it is bought with the meal it is made with, like any batch, and
  can't be adjusted (what was in the meal is its main's). Replanning keeps a meal with a side; rerolling or
  choosing another main keeps the side next to the new one, and a reroll never picks the side's own recipe (409 when
  no other recipe fits); removing the meal takes the side out of its batch,
  as `DELETE /api/plans/{weekStart}/meals/{day}/{slot}/side` does on its own, except for the side the batch is
  made with or one of a batch that was made (409). Removing a prep eaten as sides takes the sides, the meals stay.
  A skipped meal's side is a share in the box (`SparePortion.side`) whose room is the later meals still to eat
  without a side; eaten there, it is the side of that meal. The side the batch is made with stays where it was at
  0, as a skipped meal of a batch does: the batch is still made that day, with that meal, which shows the batch to
  cook and can't be removed on its own.
- Kept in `prep` and `prepPortion` (version 20 of the database). A `prepPortion` row is a meal's share of a batch,
  with a role: `MEAL` for the slot's meal, `SIDE` for a side next to it, with its own portions. What a side takes
  from the stock is kept under its meal apart from the main's (`stockUse.role`, version 21).

**In the app.** A recipe's settings have "Keeps once made": in a box and vacuum sealed, each "not prepped" or 1 to 7
days. On the Plan, "Prep a recipe…" under the replan button, or "Prep this recipe…" in the menu of a lunch or dinner
whose recipe keeps (that day and meal already chosen, from today on), opens the Prep dialog: the recipe, the day it is
made (a day of the week shown, today or later: a day that has passed is not offered, as the server refuses it, and a
week that is over has no "Prep a recipe…"), in a box or vacuum sealed, and per day up to the last day it keeps its lunch and dinner; it says why
it can't be made yet (no meal on the made-on day, fewer than two meals). A prepped meal is labelled "Dinner · prep day"
where it is made and "Lunch · prepped Mon 16 Nov" after; its lock can't be undone, and its menu has "Remove the
prep…", which names every meal that goes before it removes them. The recipe opened from the prep day says "Cook 4
servings: 1 now, 3 for the prep's later meals", from a later day "Prepped on Mon 16 Nov: nothing to cook". A skipped
meal's share still in the box today is a card above the days: when it was skipped and until when it keeps (on its last
day "eat it today or throw it away"), and "Eat it on…" with the lunches and dinners that have room.

The Prep dialog also asks how it is eaten: "The meal", or "A side" of 0.25 or 0.5 servings, when only lunches and
dinners with a meal still to eat and no side yet can be picked (of the week shown and the next). "Add a prepped
side…" in such a meal's menu opens it as a side of half a serving with that meal chosen. The side shows under the
meal's main, "Side · prep day" where it is made and "Side · prepped Wed 18 Nov" after, with its numbers and what
the meal comes to with it; a tap opens its recipe (the batch to cook on the prep day). The meal's menu then has
"Remove the side" and "Remove the side's prep…". A day card with sides that is over its calories says by how much
and what its sides add, as the day may have been over without them; Today's forecast counts them. A side needs a
second meal to go with, as a prep is made once for several meals, and the dialog says so.

An installed app that knows nothing of preps shows the later meals as locked leftovers with nothing to cook and
the made-on day's meal as cooking for the leftovers; it marks them eaten or skipped as any meal, and a refusal to
remove the made-on day's meal comes as a sentence. Of a meal with a side it shows the main alone, with the main's
numbers, while the day's totals count the side; marking the meal eaten eats the side with it.

## Fibre and what the planner weighs most

Fibre counts next to protein (issue 223). The daily targets (`DailyTargets.fibreG`) suggest 14 g of fibre
per 1000 kcal of what keeps the weight (`TargetCalculator`), not of the lower target when losing it: the
Gezondheidsraad advises 3.0 to 3.5 g per MJ of the energy requirement (Voedingsnormen 2026, as the
Voedingscentrum gives it), and 14 g per 1000 kcal is 3.35 g per MJ. The profile can set its own target
(`Profile.fibreOverrideG`, 10 to 100 g). A recipe's fibre is its NEVO foods' per serving; a food without a
fibre value counts as none, and the recipe is planned all the same. Extras logged by hand have no fibre.

The planner rewards fibre as it does protein: how far a meal goes towards its slot's share of the target,
up to 1.5 (`WeekPlanner.score`). `Profile.priority` raises one part of the score and lowers none:

| Priority | Effect |
|---|---|
| Balanced (default) | calorie fit ×2, protein fit ×2, fibre fit ×1, price as is (+0.6 to −1.2) |
| Protein | protein fit ×3 |
| Fibre | fibre fit ×2 |
| Price | price doubled (+1.2 to −2.4) |

Calorie fit and the penalties for repeats and recently eaten recipes are the same under every priority.
The fibre of planned meals (`PlannedMeal.fibreG`), of a plan's days (`PlanDay.fibreG`) and of the day view
(`DayView.eatenFibreG`, `expectedFibreG`) is not stored: it follows from the recipes' nutrition as it is
now, as a meal's cost follows from this week's prices, so meals planned before fibre counted have it too.
All of them are null from a server before fibre counted, and the app then shows no fibre. An app from
before the priority and the fibre target saves a profile without them; the server keeps the stored ones,
as it does the portion range. A newer app sends `"fibreOverrideG": null` to take its own target away.

## What was in a meal

A meal can say exactly what was eaten (issue 226): **Adjust** on a meal of Today, or "Adjust what was in it"
in its menu on the Plan, opens its recipe's ingredients for its portions (`#/meals/2026-11-16/4/dinner` in the
browser). A line's amount can be changed, a line left out (with Undo in the snackbar, and "Put back" on the
line), and any ingredient of the account's catalogue added; "Reset to recipe" takes every change away. An
ingredient whose weight per piece the catalogue knows is shown and entered in pieces ("1 piece · 60 g",
halves allowed; prefilled with one piece when added), any other in grams. Every edit is saved at once.
Any meal that is not skipped can be adjusted, eaten or still to eat; a leftover lunch on its own (its
dinner keeps its lines). The recipe itself, and the same recipe's meals on other days, never change.

- `GET /api/plans/{weekStart}/meals/{day}/{slot}/lines` answers the meal's lines (`MealLines`): the
  recipe's ingredients in its order, then those added, each with this meal's grams and the recipe's, and
  the numbers they come to. Seasoning without an amount is left out; a line linked to no ingredient is
  listed but cannot be changed.
- `PUT` on the same path with `{"lines": [{"ingredientId": 7, "grams": 60}]}` replaces the meal's changes
  (`[]` resets) and answers its `MealLines`. Grams are the amount eaten in this meal: 0 leaves a recipe line
  out. An amount as the recipe has it (to 0.05 g) is no change, nor is 0 g of an ingredient the recipe does
  not have. 404 for an empty slot or another account's meal, and for an ingredient not in the account's
  catalogue; 409 for a skipped meal; 400 for an amount below 0 or above 5000 g, an ingredient twice, or more
  than 100 lines.
- An adjusted meal's kcal, protein and fibre are worked out when it is read, from the recipe's lines and the
  catalogue as they are now with the meal's changes, the way a recipe's nutrition is; they replace the
  recipe's numbers in the plan's meal (`PlannedMeal.adjusted` is then true), its day and week totals, and the
  day view on Today. A meal without changes keeps the kcal and protein stored when it was planned, so a reset
  restores nothing. An added ingredient without a NEVO food counts as nothing and the Adjust screen names it
  (`MealLines.uncounted`). A meal's cost stays that of its recipe.
- The changes are kept per meal (`mealLine`, version 18 of the database) and go with it: choosing another
  recipe, "Another recipe", replanning or removing the meal drops them.
- An adjustment does not go on the shopping list by itself: what was added came from the fridge or the
  pantry. The one exception is an addition that takes what was at home for a meal still to cook that week
  (below). What eating the meal takes from the stock follows it: see "Un-eating" in `docs/data-sources.md`.
  An adjustment to a meal eaten earlier takes from the stock as it is now, so from a later purchase when the
  ingredient was bought since (there is no record of what was at home when it was eaten).

**Quick add-ons.** At the top of the Adjust screen, above the meal's lines (and under the warning below, when
there is one), are chips, "Feta · 30 g" or "Egg · 1 piece", one tap each, so a known topping is two taps from
Today however long the recipe is: the recipe's optional toppings first, in their order, then the ingredients
added to that recipe's meals before (in any week), most often added first, then the most recent, each with the
amount its latest meal had; at most eight of those. A chip adds its amount on top of what the meal has of that
ingredient: one the meal has already (a topping the recipe has too, or a second egg) says so, "+ 30 g
Cucumber". From earlier meals only ingredients the recipe does not have count: a meal keeps what it ate of a
recipe ingredient in all, not what was extra. Reset to recipe takes a meal's additions away, so they no longer
count for these chips either. A chip adds as any edit does, with Undo in the snackbar. Amounts are in pieces
only where that is a sensible count (whole pieces, halves or quarters), here and on the meal's lines; "30 g" of a
cucumber is said in grams.

- `GET /api/recipes/{id}/add-ons` answers them (`AddOn`: the ingredient, its name, the grams to add, its weight
  per piece, whether it is a topping and how many meals had it added). 404 for another account's recipe.

**Optional toppings** are set in a recipe's settings in the app (under "Plan as", for one of my recipes): an
ingredient of the catalogue and the amount one meal usually gets of it, in pieces where the catalogue counts
it. They are the account's own, as the rating is, also on a cooking-site recipe, and a sync never changes
them. They are not part of the recipe's nutrition, cost or shopping until one is added to a meal.

- `PUT /api/recipes/{id}/toppings` with `{"toppings": [{"ingredientId": 7, "grams": 60}]}` replaces them in
  that order (`[]` takes them all away) and answers the recipe; `RecipeDetail.toppings` lists them. Any recipe
  of the account, in the bank too. 404 for another account's recipe or an ingredient not in its catalogue; 400
  for an amount of 0 or less or above 5000 g, an ingredient twice, or more than 20 toppings. Kept in
  `recipeTopping` (version 19 of the database); they go with the recipe or the ingredient when either is
  deleted.

**A later meal left short.** When an adjusted meal has more of an ingredient than its recipe, and that takes
what was at home for a meal still to cook that week, the Adjust screen says so ("Not enough left for a later
meal": `Spinach: 100 g short for Spinach curry, dinner Thu 19 Nov`) and the shopping list asks for the amount
short again. What is at home is what the server knows of: the larger of the stock the week had and what the
ingredient's ticked row on the week's list covered (not their sum, as a tick covers the week's whole need, the
stock included). A staple, an ingredient used to taste, and one whose need can't be weighed have no row to ask
on, so nothing is said of them. With enough left (overstocked, or the later meal adjusted to use less) there is no warning and the
list stays as it was; an addition beyond what was at home came from the fridge and asks for nothing. The same
holds when the adjusted meal was eaten already. An ingredient set "On hand" by hand in the pantry has no amount,
so nothing is said about it: the owner says it is at home, and the server cannot tell how much is left. The card
says one amount per ingredient, the later meals' shortfalls added up and rounded once, as the list rounds the
amount it asks for again, so the two agree. How it is worked out, and how the list's row shows it, is in
`docs/data-sources.md`, "A later meal left short".

- `MealLines.shortfalls` lists the later meals the meal leaves short (`LaterShortfall`: day, slot, title,
  ingredient, grams); empty when enough is left.

An installed app that knows nothing of adjustments shows the adjusted numbers all the same, as it reads
the meal's `kcal` and `proteinG`; it reads a recipe with toppings as before, and a shopping row that asks for
more again as it reads any row whose need grew.

The planner also prefers a meal that uses up the rest of a fresh ingredient's pack that an earlier meal of the
week opened (issue 222): the rest of a pack counts for meals on the day it was opened and three days after, the
pack being that of the ingredient's regular AH price, and is wary of a recipe that opens a new pack for a small amount. See `docs/data-sources.md`, "Packs".

## Web client

`composeApp` also builds for the browser (`./gradlew :composeApp:wasmJsBrowserDistribution`), and the
server jar includes that build: the server serves it at `/`. On the first visit it goes to the login
page by itself, in the same tab (a normal page navigation); coming back from a cancelled or failed login
shows the login screen instead of going round again. The tokens are kept in the browser's `localStorage`. `kotlin-js-store/` pins the JavaScript toolchain's
dependencies; commit changes to it. The web tests run in a headless Chrome.

Checked by hand (login, all five tabs, light and dark, 360 px and 1280 px wide, Dutch locale, back
button, mouse wheel, keyboard focus) in Chromium 157, and in Firefox 160 (login and two tabs only).
The open screen is part of the address (`/#/recipes/12`, `#/offers`, `#/settings`; see `Routes.kt`), so a
reload or a shared link comes back to it; an unknown address is the Today tab, and without a login the login
shows first and the address is restored when it returns. The address changes without adding history entries, so
the back button works as before; Android does not use routes. Keyboard focus is shown by a ring in the theme's
primary colour (`FocusRing.kt`), on Android with a keyboard too. Before the app has started the page takes its
background from the Light/Dark choice in Settings, which `index.html` reads from `localStorage` (key `theme`).
The ingredients and steps of a recipe can be selected and copied, on Android too. Known difference: typing a
new fragment into the address of an open page does not move the app (a reload does).
A server that cannot be reached is a lost connection as on Android (the plain error with Try again, the refresh
line, the shopping list's offline line): Ktor's browser engine reports a rejected fetch as a `kotlin.Error`, no
`Exception`, so `ApiClient` turns it into an `IOException` (`connectionFailure` in `Platform.wasmJs.kt`). "In front"
is the tab being visible, taken from the browser's `visibilitychange` event as it comes and not through the
composition, since a hidden tab draws no frames: hiding the tab stops the shopping list's checks and the clock,
and showing it again fetches the screen in front once.

## Android app

Logged out, the app shows its login screen (see "Accounts and login"). A release built with the server's
address opens that server's login page at once; a debug build asks for the address on the login screen
first, suggesting `http://10.0.2.2:8080` (the host machine, seen from an emulator), which `-PserverUrl=…`
changes at build time. The address can be changed under Progress → Settings, or with "Change server" on
the login screen (its "Settings" link while it asks for the address). The tokens are kept in the app's private storage.

The app is in English and Dutch. Progress → Settings → Appearance → Language picks System (Dutch when the
device, or the browser for the web app, is set to a `nl` language, English otherwise), English or Nederlands;
it applies at once and is kept. Numbers, prices and dates follow the same choice (a decimal comma, `vr 13 nov`
in Dutch); with System they follow the device. The texts are in `composeApp/.../Messages.kt`, each with its
English and Dutch side by side, the notifications' texts among them, so these follow the same choice.
Texts that come from the server (error messages, store deals, recipes) are shown as they are.

Offers and shopping-list rows show the product's picture from the store (Coil 3; docs/data-sources.md, "Product pictures"), with the first letter of the product as placeholder. Progress → Settings → Appearance → "Show product pictures" turns the loading off. The web app shows no pictures for Jumbo, which sends no CORS headers. The screenshot tests use a fake loader and never touch the network.

**The Recipes tab and the bank** (issues 205 and 206; the words are those of "Recipes: the bank, my recipes and the rotation"). The Recipes tab lists my recipes (`GET /api/recipes`). A choice above the list, All, Rotation or Not in rotation, each with the number of recipes it shows, narrows it (kept like the search text; "Meals only" and the search work within it). A row's switch puts the recipe in the rotation or takes it out without opening it, and fails with the server's sentence in the usual line at the bottom, the row going back. A long press on a row, or "Select recipes" in the menu (for whoever has no long press, as on the web), selects several: the bar at the bottom has "Select all" (what the choice shows), "Add to rotation", "Take out of rotation" and "Remove from my recipes". The last leaves out the recipes made in the app, which have no bank to go back to (the server answers 409 for one), and the message says how many stayed; they are deleted from their own screen (menu → Delete). A refusal (a recipe planned this week or later, or the breakfast or snack routine) shows the server's sentence. A rotation recipe the planner will still skip says so on its row (no amounts or ingredients still to link: the existing marks; a sauce: "Not a meal"), and a recipe whose file left the cooking site is marked "No longer on the cooking site". With no recipes the tab offers "Browse the bank" and "New recipe". Sync stays in the Recipes tab's bar; when it found new recipes the snackbar says "3 new recipes in the bank" and its "Show" opens the bank.

The bank (`Route.Bank`, `#/recipes/bank`; `GET /api/recipes/bank`) is opened by "Browse the bank" on the Recipes tab, which says how many recipes wait there. It has the search (title and tags), a chip per section of the cooking site, rows as on the Recipes tab with an Add button, and selecting several for "Add to my recipes" (taking off the last tick leaves selecting, and Back leaves selecting before it leaves the screen, on Android and in the browser, where only the innermost back handler answers a back); the cooking-site line with the sync time ("This server's cooking site, synced …" when the bank is the server's) and the Sync button are here, as they are about the bank. Tapping the line opens the cooking-site dialog, which offers where the bank comes from: this server's cooking site (named by its address; only when the server offers it), a repository of my own (with the field for its address), or none; saving imports from the chosen site at once (issue 208). Against a server from before that, the dialog offers a repository of my own or none, which clears it. A row opens the recipe read-only: picture, nutrition, ingredients and steps, "Add to my recipes" and under it "Let the planner pick it" (on; after "Remove from my recipes" it shows what the recipe had, so that adding it again keeps that); no rating, rotation, "Plan as" or routine until it is added. After adding, a snackbar names it with "Undo" and, when ingredients of it are not linked to a food yet, how many, with "Link" to the linking screen. On one of my recipes the screen has the switch "In my rotation" above "Plan as" with a line under it; from the site a recipe is taken out with "Remove from my recipes" in the menu (a 409 says why not), one made in the app with "Delete". The recipe picker for a meal lists my recipes, those in the rotation first and the rest under their own heading (a recipe only in the bank is not offered; by hand any of mine can be planned), with "Browse the bank…" under the list; the Plan says when slots stay empty because the rotation is empty, with a link to the Recipes tab. Against a server from before the bank (no `GET /api/recipes/bank`: it answers 400 or 404, and the recipes carry no flags) every recipe is mine and in the rotation, "Browse the bank" is not offered, and a switch that cannot be changed fails like any other change.

Recipe photos (from the cooking site) show on the Recipes list, the recipe screen, Today and the Plan, for the recipes that have one; a recipe without one keeps the plain layout, with no placeholder. They come from our own server, so the same loader fetches them with the login token, and the "Show product pictures" setting does not affect them.

Screens keep their place. Opening a recipe (or the pantry, offers and so on) from a tab and going back leaves the tab as it was: the Plan's week and scroll, Today's day, the recipe list's scroll, search and choice, the bank's scroll and search; so does Android re-creating the activity (rotation, dark mode, language). This is kept in memory and the saved instance state, not on disk: a cold start begins on Today, this week, at the top. A screen that comes back shows what it showed at once and fetches again behind it; a failed fetch keeps what is shown and says so in a line at the bottom with a Retry (a recipe the server says is gone shows the error instead). A new client (login, logout, another server) makes every open screen fetch again. When the app returns to the front (Android: started again; browser: the tab visible again) every screen in front fetches once, and Today and the Plan read the date again: they follow the new day or week when they were showing today or this week, and stay where the user stepped to otherwise. While the app is in front the clock is read every 30 seconds to notice midnight.

Cook mode: the **Cook** button on a recipe (one of mine or from the bank) opens big rows to tick the ingredients, with the amounts of the recipe screen (scaled to the servings to cook when it was opened from a meal, with its "As written" switch), and the steps one per card: the numbered list of the instructions, or, when there is none, every non-empty line (steps typed one per line in the editor). Tapping a row ticks it (dimmed, not moved); tapping a step marks it done and makes the next one the current, highlighted one. The screen stays on while it is open (Android: the window flag; browser: the Screen Wake Lock API where the browser has it, taken again when the tab shows). Ticks live in the saved instance state and go with the recipe screen; nothing is sent to the server. With the last step done, a meal that is still planned offers "Mark as eaten" (`PUT /api/plans/{week}/meals/{day}/{slot}/status`); Back leaves cook mode for the recipe.

**Notifications** (Android only, issue 225). Progress → Settings → Notifications has a switch per kind, kept on
the device; each kind is an Android notification channel of its own, so the system's settings list the same kinds:

| Kind | Posted when | Tap opens | Default |
|---|---|---|---|
| App update | A newer release exists (release builds only), once per version | The app, which offers the update | On |
| Offers ending | An offer at one of the account's stores ends today or tomorrow, for a row of this week's shopping list still to buy (not ticked, still needed by a meal, not covered by a known amount at home), or a buy-ahead or stock-up row not ticked or dismissed; once per day, one notification naming them all ("3 offers for your list end today") | Shopping | On |
| Plan next week | On Saturday and Sunday, while next week has no meals, whatever the offers ("Next week has no plan yet"; it works for every store, since only Jumbo lists next week's offers early); once per week | Plan, on next week | On |
| Weigh-in | The newest weigh-in is 7 or more days old and there is a profile; once per 7 days, until one is entered | Progress | Off |
| Dinner today | Today has a planned dinner that is neither eaten nor skipped; once per day | The recipe, from that meal (its servings to cook) | Off |
| Mark what you ate | A meal of today is still planned; once per day | Today | Off |
| Prepped food to throw away | The morning after a prepped batch's last day (its own: in a box or vacuum sealed), when the batch was made (a meal of it eaten) and a share of it was skipped and not eaten elsewhere ("Throw away 1 portion of Chili con carne" / "It kept until Mon 23 Nov."), or a meal or side of it was never marked ("1 portion of Chili con carne was not marked: eaten, or to throw away?"); sides say "side". Several batches go into one notification; once per batch, and only on the day after its last day | Plan, on the week of the first meal not marked, else on the week the batch was made | On (only an account that made a prep gets it) |

The server says what is due, the app decides whether to tell: `GET /api/notifications` answers, for the calling
account and the server's today, the facts behind kinds 2 to 7 as data (`NotificationFacts`: names, dates, counts,
the recipe; for kind 7, `prepsPastLastDay`: per batch whose last day was yesterday, the recipe, the shares left and
the meals not marked), and the app words them in its language (`Messages.kt`). One WorkManager job (`NotificationWorker`)
replaces the daily update check: every four hours on any network, as Android allows, it checks the release and,
when logged in, asks the server. Android decides when the periodic run happens, so it may never fall inside the two
hours of "Mark what you ate"; therefore every run also schedules a one-off run five minutes after the next window
opens (08:00, 15:00 or 20:00 by the device's clock), and the app's start does too, so each window gets a run
whatever the phase of the periodic one (`NotificationWindows`). The schedule outlives the app being swiped away and a reboot (WorkManager keeps it
in JobScheduler and sets it again at boot); a force stop (Settings → Apps → Force stop, or a phone maker's battery
saver that force-stops on a swipe) cancels it until the app is next opened, which schedules it again. What decides (the kind, its switch, what was told already per kind and day, week
or version, and the windows) is common code (`NotificationPlanner`): kinds 1 to 4 and 7 from 08:00, dinner from 15:00,
"mark what you ate" from 20:00, and nothing between 22:00 and 08:00, by the device's clock; Android only schedules,
asks and posts. A logged-out app posts nothing but the update; the check never shows an error, a failure tells
nothing until the next run, and its renewals never end the session (see "Accounts and login"). Turning a switch on
asks for the permission where Android needs it; while a switch is on and Android does not let the app post, the
card says so with "Open Android settings", read again each time the app resumes. A tapped notification carries its
screen as a route (`Opening` in `Routes.kt`) with an id of its own, which `MainActivity` hands to the app once
(`NotificationInbox`): from a closed app, from an open one, and after Android killed the process with the task still
in Recents, when the tap arrives with the screens' saved state and opens its screen over them; the recipe editor and
the profile stay in front when they are open, so nothing typed is lost. "Plan next week" opens the Plan on next
week (the `Opening` carries the week, `PlanScreen` starts on it), afresh each time it is tapped.

Emulator on this machine (`avdmanager` stores devices under `~/.config/.android/avd`, where the
emulator does not look by default; the emulator window needs X11, so on Wayland it runs through XWayland):

```bash
QT_QPA_PLATFORM=xcb ANDROID_AVD_HOME=$HOME/.config/.android/avd \
  ~/Android/Sdk/emulator/emulator -avd mealplanner_test
./gradlew :androidApp:installDebug
```

## API compatibility

Installed apps outlive server deployments: the server deploys itself the night after a merge, an app
only when the owner runs the Release workflow. The app skips JSON fields it does not know, but fails
on a field it needs that is missing, and on an enum value it does not know (the whole response then
fails to read). So, for a response or request that an installed app uses:

- Safe: a new optional field (with a default or `= null`), a new route, a new type.
- Not safe: removing or renaming a field, changing what a field means, a new required field, and a new
  value of an enum that the installed app knows (`Store`, `MealStatus`, `MealSlot`, ...). Put new data
  in new routes or types, and keep the old field for a release.

`ReleasedAppTest` (server) enforces this. It reads every response of the API with the shared API types as
they were at the latest release (read the tag in the header of the files under
`server/src/test/kotlin/dev/tobins/mealplanner/server/released/`, where they are frozen), and
checks that no enum has a value the frozen one lacks. A pull request that breaks the rule fails `:server:test`.

The app's `ApiClient` turns an unknown enum value into the field's default, so a new value is safe for an
app from the first release after v0.1.3 on, for a field that has a default; a list of enums is not covered.
Give enum fields added from now on a default, or make them nullable. The rule above holds until then.

After an app release: `scripts/freeze-api.sh vX.Y.Z`, commit the result. The script copies `api/*.kt`,
`Health.kt` and what they import from elsewhere (`recipes/ParsedRecipe.kt`, `units/UnitTable.kt`,
`planner/PortionRange.kt`), and fails when a frozen file imports something it did not copy; add that file to
the list in the script. The history of single fields is in
the test and in `git log`, not here.

## Screenshot tests

Layout is checked without an emulator: `ScreenshotTest` (`composeApp/src/androidHostTest`) renders the
Today (also in Dutch), Plan (also in Dutch and with an empty rotation; both with an adjusted meal; with a prep and a share in the box, also in Dutch), the Prep dialog (also in Dutch and at font scale 2, also eaten as a side), the Plan and Today with sides next to meals (also in Dutch), the question that removes a prep and a share in the box on its last day (both also in Dutch), the Adjust screen of a meal with its quick add-ons and a later meal left short (also in Dutch), Shopping (also in shopping mode, with an empty week, per store and at one store), Pantry, Recipes (on each choice, selecting several, and in Dutch), the recipe bank, a recipe from it and the cooking-site dialog's choices, recipe detail (with its optional toppings, also in Dutch; in the rotation, out of it, no longer on the site), the recipe picker, Offers, Prices, Progress, the profile (in English and Dutch),
Settings and login screens (the login with a known server and with the address asked for), the Notifications card (in English and Dutch, with notifications refused by Android, and at font scale 2 in both languages), the empty states of Recipes, Offers and Prices, and the whole app shell (with the bottom bar, logged in
with a fake session) on the JVM with Robolectric and Roborazzi, and compares each with its picture in
`composeApp/src/androidHostTest/screenshots`. The screens talk to `FakeBackend`, a local server with fixed
sample data, with the date, time zone and locale (English, UK) fixed, so a picture only changes when the
UI does. A `Shot` with `dutch = true` is taken in Dutch (the screens whose Dutch texts are the longest). Every screen is taken twice: 360 dp wide, font scale 1.3, light (where text is cut off first) and
411 dp, font scale 1.0, dark. A screen that scrolls is rendered as tall as its content (the `heightDp` of its
`Shot`), so what is below the first screenful is checked too. The pictures are saved at half size to keep the
repository small.

```bash
./gradlew :composeApp:verifyScreenshots   # fails when a picture differs; ~15 s once built
./gradlew :composeApp:recordScreenshots   # when the change is intended: rewrite the pictures, then commit them
```

Name the task on the command line as above: running the test from the IDE's gutter icon does not record or
verify anything, because the switch is read from the requested Gradle tasks.

After a failure `composeApp/build/outputs/roborazzi` holds `*_actual.png` (the new picture) and `*_compare.png`
(reference, difference, new, side by side); in CI they are the artifact `screenshot-differences`. A pull
request that changes how a screen looks shows the changed pictures in its diff; that diff is the record of a
design change, so pull requests and tickets do not need screenshots attached. A new screen or a new
variant goes into the `Shot` enum or the `Look`s in `ScreenshotTest`. The emulator remains for flows such
as the login and updates. `allTests` leaves these tests out.

Robolectric runs offline. It would fetch the `android-all-instrumented` jar of the SDK the tests run at
(`15-robolectric-14663667-2-i7` for SDK 35) from Maven Central when the first test starts, and a failed
fetch failed every Robolectric test of the run (issue 239). `gradle/robolectric.gradle.kts`, applied by the
root build to every module with Robolectric among its dependencies, resolves that jar as a normal Gradle
dependency (`robolectric-android-all` in `gradle/libs.versions.toml`, so Gradle's retries and the CI cache
apply and a failure says so at dependency resolution) and sets `robolectric.offline`. A new Robolectric
version, or a test at another SDK (`@Config(sdk = ...)`), needs the matching `android-all-instrumented`
version in the catalog; without it the test fails at once, naming the missing jar. The Wasm npm install is
checked before webpack (`verifyWasmNpmInstall` in the root build file): a package of
`kotlin-js-store/wasm/yarn.lock` missing from `build/wasm/node_modules` fails there with that message.

## Database changes

The schema lives in `server/src/main/sqldelight/.../db/*.sq` and always describes the current
version. Every change also needs a migration file `N.sqm` (version N to N+1) next to it; the server
applies pending migrations on start. `MigrationTest` builds a version 1 database from
`server/src/test/resources/schema/v1.sql` and checks it ends up identical to a fresh one.

## Working on issues with Claude Code

`main` takes pull requests with green checks only, also from the owner's terminal.

Changes go through a pull request: an issue is implemented on a branch `issue-<n>-…` in its own git
worktree, CI runs on the pull request, and a reviewer that knows nothing about how the code was
written checks it against the issue and squash-merges it. In Claude Code, `/work-issues 27 21`
runs that for the given issues; the session you type it in directs the work and the sub-agents in
`.claude/agents/` do it:

| Agent | Model, effort | Does |
|---|---|---|
| `coder` | Sonnet, medium | One well-scoped issue, opens the pull request |
| `coder-deep` | Opus, high | The same for schema migrations, login, account separation, concurrency, cross-module refactors |
| `reviewer` | Sonnet, high (Opus for `coder-deep`'s work) | Checks a pull request against its issue; merges when all is met and CI is green |
| `merger` | Sonnet, medium | Works out the order for pull requests that wait for you, and on your approval merges them, rebases the rest in between and, when you ask for it, starts the app release afterwards |
| `emulator-tester` | Sonnet, medium | Looks at a UI change on the Android emulator |
| `qa` | Sonnet, high | Tests the whole app on the emulator and files what it finds as issues labelled `qa`, with a report |
| `ci-triage` | Haiku, low | Finds the first real error of a failed build |
| `price-auditor` | Sonnet, medium | Flags wrongly matched AH prices |

Issues whose files don't overlap run side by side (three at most); others one after the other.
The reviewer never merges changes to the workflows, signing or `.claude/`, and nothing releases:
that stays manual, below. A merged server change is live the next night (see `docs/deploy.md`), the
app only after a release, so every pull request states whether the app must be released: the
reviewer labels those `needs-app-release`, reports it first, and leaves a pull request whose server
change would break the installed app for you to merge together with a release. When several wait, the `merger` agent says in
which order they must go (a pull request built on another one merges after it), and once you say
which ones to merge it merges them and fixes the conflicts in between; ask for a release in the
same breath and it starts the Release workflow once CI on `main` is green. Only the reviewer and the
merger can run `gh pr merge`, and only the merger can start the release, which it does without a further prompt when you asked for
one beforehand
(`.claude/hooks/gate-pr-merge.sh`).

### What the agents cost

`scripts/agent-cost` (Python 3, nothing to install) reads the transcripts Claude Code keeps of the
sub-agent runs of a session and prints, per agent type: runs, tokens (fresh input, cache write,
cache read, output), the context at the first request, requests per run, the peak context, where
the tool results came from (Read, Grep/Glob, shell reads, `gh`, git, Gradle) and repeat reads of a
file. The cost is what the agents' tool lists and briefs are judged by: most of it is the context
size times the number of requests, not the text read.

```
scripts/agent-cost                      # newest session of the project you run it in
scripts/agent-cost --agent coder --since 2026-11-17T00:00:00Z --tools
scripts/agent-cost --json               # for comparing runs by machine
scripts/agent-cost --self-test          # on made-up lines, no transcripts needed
```

It prints counts only, writes nothing and skips lines it does not understand. The cost column is
in relative units from the list-price ratios at the top of the script, not weighted by model: use
it to compare runs of the same agent, not to read a bill.

Baseline of 2026-11-16 (189 sub-agent runs of one long session, issue #172):

| Agent | Runs | Context at first request | Requests per run (median / max) | Peak context (median / max) |
|---|---|---|---|---|
| `coder` | 46 | 32 k | 36 / 133 | 101 k / 345 k |
| `coder-deep` | 25 | 33 k | 76 / 209 | 232 k / 481 k |
| `reviewer` | 96 | 6 k | 14 / 42 | 40 k / 124 k |
| `emulator-tester` | 14 | 7 k | 46 / 103 | 90 k / 240 k |
| `architect` | 2 | 8 k | 46 / 56 | 338 k / 426 k |

Of the text the tools returned, 40% was files read or searched through the shell, 24% the Read
tool, 12% `gh`.

### A map of the code for the agents

`scripts/code-map` (Python 3, nothing to install) prints a map of the checked-out tree, so it is
never stale and never committed: per module and source set the Kotlin files with their line
counts and, under each, its top-level declarations (and the public and internal functions of a
class) with line number and the first sentence of the KDoc; for `.sq` files the tables and the
queries; of the migrations only the newest number. The agents' briefs tell them to start from it
instead of `find` and `grep -r`. Declarations are found with regular expressions, not a parser:
those inside strings and comments, private and nested ones, and `override` members are left out.

```
scripts/code-map                        # every file with its line count (under 200 lines)
scripts/code-map server                 # declarations of a module, or of a path inside one
scripts/code-map --files shared         # files and line counts only
scripts/code-map --grep recipeBank      # declarations whose name contains this, with file and line
scripts/code-map --tests composeApp     # include the test sources, left out by default
scripts/code-map --self-test            # on made-up sources
```

## Releases

Releases are made by hand, not on every commit: Actions → **Release** → Run workflow, on `main`.
The workflow checks that CI passed for that commit, builds a signed APK, tags the commit `vX.Y.Z`
and publishes a GitHub release with `mealplanner-vX.Y.Z.apk` attached. Leave the version empty to
raise the patch number of the latest tag (the first release is `0.1.0`), or enter one such as
`0.2.0`. The version code is derived from it (`1.2.3` → 10203), so later releases install as
updates. The release's notes are written by `scripts/release-notes.sh`: one line per squash commit
title since the previous `v*` tag (without the ` (#n)` suffixes, newest first), only for commits that
change a file under `composeApp/`, `androidApp/`, `shared/` or `server/` outside the test source
folders, so merges that only change tests are left out. The app shows them under "What's new"
where it offers the update; a release without notes is offered without that heading. Only an app that
already has this feature shows them, so the first release that displays notes is the one after it.

One-time setup: `scripts/create-release-key.sh` creates the signing key in
`~/.android/mealplanner-release.jks`, adds the `mealplanner.*` signing properties to
`~/.gradle/gradle.properties` and stores the key in the repo's GitHub secrets
(`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`). Back up the keystore
and its password: Android only installs an update signed with the same key. The server's address, which
a release has built in, is the secret `MEALPLANNER_DEFAULT_SERVER` (e.g. `https://mealplanner.example.nl`):
add it under Settings → Secrets and variables → Actions. The workflow passes it as `-PreleaseServerUrl`
and stops when it is missing or not an `https://` address; it is kept out of this public repository.
`scripts/backup-release-key.sh` (run in a terminal) stores both in Bitwarden as the secure note
`mealplanner-android-signing` and checks the copy; the note says how to restore it.

Installed release builds update themselves: on start, from Settings → Check for updates, and
every few hours in the background (a notification, which Settings → Notifications can turn off), the app looks at the latest GitHub release. If it
is newer, **Update** downloads the APK and Android installs it over the current app; Android asks
once to allow installs from the app. Debug builds don't check, since a release can't replace them.

With those properties set, `./gradlew :androidApp:assembleRelease` also builds a signed
`androidApp/build/outputs/apk/release/androidApp-release.apk` locally. `-PreleaseServerUrl=https://mealplanner.example.nl`
builds a server's address into it: a fresh install opens that server's login page at once, and the App
Link (`/app/login`) is for that host. The address is not kept in this public repository. Without it a
release asks for the address on its login screen, as a debug build does, and the login comes back
through `/app/login`'s forward to the app's own scheme. It only talks HTTPS. A release and a debug build cannot be
installed side by side: they share the application id but not the signing key.

## Server configuration

| Variable | Default | Meaning |
|---|---|---|
| `MEALPLANNER_OIDC_ISSUER` | unset | OpenID Connect issuer whose access tokens are accepted, e.g. `https://authelia.example.nl`; without it nobody can log in |
| `MEALPLANNER_OIDC_CLIENT_ID` | `mealplanner` | The app's client id at the issuer |
| `MEALPLANNER_OWNER` | unset | Username that claims the data from before accounts; unset: the first login |
| `MEALPLANNER_ANDROID_CERTS` | unset | SHA-256 fingerprints of the app's signing certificates, for `assetlinks.json` |
| `MEALPLANNER_DATA_DIR` | the database's directory | Where per-account cooking repo clones go (and the server's, without `COOKING_REPO_PATH`) |
| `MEALPLANNER_IMAGE_DIR` | `recipe-images` in the database's directory | Where web-sized copies of the cooking sites' recipe pictures go, one folder per account. Keep it on the same persistent volume as the database: the database says which copies exist |
| `PORT` | `8080` | Listen port |
| `MEALPLANNER_DB_URL` | `jdbc:sqlite:.runtime/mealplanner.db` | SQLite database |
| `COOKING_REPO_URL` | unset | The server's cooking site: the recipe bank of every account that has not chosen another site or none in the app; cloned once and re-synced every six hours. Shown to accounts other than the owner without its user name or password |
| `COOKING_REPO_PATH` | `.runtime/cooking` with a URL | The server's checkout of that site; without `COOKING_REPO_URL` it is used as is (and synced only when asked) |
| `COOKING_REPO_SHARED` | on | Set to `0` (or `off`, `false`) to keep the server's cooking site to the owner, as before issue 208; other accounts then set a site of their own or have none |
| `COOKING_REPO_FETCH_INTERVAL_MINUTES` | `5` | The least time between two fetches of the server's cooking site, which any account's Sync asks for while the others' imports wait; a Sync within it imports the clone as it is. `0` fetches on every Sync. A value that is not a whole number of minutes stops the server at start |
| `ANTHROPIC_API_KEY` | unset | Enables recipe generation (`POST /api/recipes/generate`) with Claude; pay per use |
| `MEALPLANNER_MODEL` | `claude-opus-5-5` | Claude model for generation |
| `MEALPLANNER_FETCH_OFFERS` | on | Set to `off` to stop fetching store offers and regular prices in the background |
| `NEVO_CSV_PATH` | unset | NEVO main csv (`NEVO2025_v9.0.csv`). Imported on first start; `POST /api/foods/import` re-imports |

The NEVO dataset is not in this repository (see `docs/data-sources.md`). Keep it under `data/`,
which is ignored by git.

`shared`'s `CookingRepoParseTest` parses every page of the cooking repo. It looks in
`../cooking`, or wherever `-PcookingRepo=<path>` points, and is skipped when that is missing.

API overview: `/api/recipes` (my recipes; `GET /api/recipes/bank` the bank, `PUT /api/recipes/{id}/added` with
`{"added": true, "inRotation": true}` and `PUT /api/recipes/{id}/rotation` with `{"inRotation": false}` answering the
recipe, `PUT /api/recipes/added` and `PUT /api/recipes/rotation` with `{"ids": [1, 2, 3], ...}` for many at once
answering my recipes; see "Recipes: the bank, my recipes and the rotation"), `/api/sync/cooking`, `/api/foods` (NEVO search), `/api/ingredients` (catalogue),
`/api/ingredients/sections` (each ingredient's shop section, which orders the shopping list; `PUT
/api/ingredients/{id}/section` corrects one for the account, `PUT /api/ingredients/{id}/keeps` whether it
keeps until next week), `/api/mappings` (links from recipe
ingredient texts to catalogue ingredients), `/api/profile` (body data, daily targets and the portion range; see
"Portion per meal"), `/api/shops` (the stores the account shops at; only their offers count for plans and costs),
`/api/shopping-advice` (whether the shopping list advises buying ahead and stocking up; see
`docs/data-sources.md`, "Buying ahead and stocking up"), `/api/pantry` (staples and on-hand items), `/api/plans/{weekStart}` (week plans: `generate`, `shopping`
with `shopping/dismissed/{group}` and the account's own items, `POST shopping/extras` with `{"name": "Coffee"}` and
`DELETE shopping/extras/{id}`, rows of the list with keys `x:<id>`; see `docs/data-sources.md`, "Own items on the shopping list";
`PUT shopping/again` and `POST shopping/use-up` with `{"key": "i12"}` tick a row that needs more again and put a bought item
no meal needs on hand, see "What the app shows of it" there,
and per meal `reroll`, `lock`, `status` and delete; `PUT meals/{day}/{slot}` with `{"recipeId": 12}` puts the account's recipe there,
locked, and a skipped meal leaves the day's and the week's totals; a meal that was eaten, or the leftover lunch of an eaten
dinner, keeps its recipe and its place: `reroll`, `PUT` and delete answer 409, `POST meals/{day}/{slot}/generate` 422, and
`generate` keeps it; see `docs/data-sources.md`, "Un-eating"; `preps` and `preps/{id}` with `preps/{id}/spare` for prepped
meals, `DELETE meals/{day}/{slot}/side` for a side, and `GET /api/preps/spare/{date}`, see "Prepped meals"), `/api/days/{date}` and `/api/log` (what was eaten)
`/api/weights` (weight log, trend and target suggestion; the newest weighing is the profile's weight, so the targets follow it:
a weighing for an earlier day leaves the profile alone, and removing the newest one gives the profile the weight of the newest
one left. Removing the only one keeps the profile's weight, as what it was before is not stored; plans already made keep their
portions), `/api/offers` (weekly store offers;
`POST /api/offers/refresh` fetches them now; `GET /api/offers/verdicts` says whether each of the account's offers is a
good deal by its price history, for reading only: no app uses it yet, see `docs/data-sources.md`, "Is it a good deal?") and `/api/prices` (regular AH prices per ingredient, the
baseline for costing meals; `POST /api/prices/refresh` starts a lookup).

Ingredient texts are linked without manual work where the match is clear: through the default
catalogue in `server/src/main/resources/catalogue-seed.csv` (common ingredients, their NEVO food,
typical weights, and negligible items such as spices), and when a text is exactly the name of one
NEVO food. Only the rest shows up in the app's "Link ingredients" screen.

External data sources (store offers, nutrition data) are described in `docs/data-sources.md`.
