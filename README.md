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

A product's lookup at AH works so, without more settings (it asks AH, so it needs the internet). A barcode AH cannot
answer goes on to Open Food Facts only with `MEALPLANNER_OPENFOODFACTS_CONTACT` set; without it the form says Open Food
Facts is not set up.

To play a member and a non-member of the server's group, start the stand-in with users and their groups
(`--user anna=mealplanner --user eve=family`, or `--users users.json` with `{"anna": ["mealplanner"]}`,
read again for every token so a change shows at the next renewal) and the server with
`MEALPLANNER_OIDC_GROUP=mealplanner`. Without users anyone can log in, in the group `mealplanner`
(`--default-groups`). `python3 scripts/test_dev_oidc_provider.py` is the stand-in's own test.

To try registration (issue 314) without LLDAP, the stand-in plays the registration helper on its own directory: start it
with `--helper-secret <any 32 characters>`, and the server with `MEALPLANNER_REGISTRATION=on`,
`MEALPLANNER_OIDC_GROUP=mealplanner`, `MEALPLANNER_REGISTRATION_HELPER_URL=http://localhost:8090` and
`MEALPLANNER_REGISTRATION_HELPER_SECRET` set to the same secret. A registered user logs in on the stand-in's page (it asks
no password) in no group until approved. The real helper, `scripts/registration-helper.py`, is tested against a fake
LLDAP by `python3 -I scripts/test_registration_helper.py` (CI runs it).

## Accounts and login

Users log in with an OpenID Connect provider (Authelia in the homelab); every account it knows may
use the app, or with `MEALPLANNER_OIDC_GROUP` only the members of that group (see below), and each gets
its own recipes, ingredient catalogue, pantry, profile, plans, shopping lists, log and weights. Store
offers, regular prices, their history and the NEVO data are shared.

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
  started or exchanged, in the settings as on the login screen; of two logins started at once all the same,
  only the last one started may log in, whichever server answers first.
- An access token the server refuses is renewed with the refresh token. When the provider refuses that
  (400 or 401, or there is no refresh token) the session ends at once on the login screen, saying the login
  ran out or was ended. Another error from the token endpoint ends it only when it goes on (issue 198): on
  the third failed renewal in a row, when the first failure of the run was at least 24 hours earlier if any
  failure of the run came from a provider in trouble (5xx, 408, 429), or at least 15 minutes earlier if all
  were other client errors (403, 404, …; a proxy's 404 while the provider restarts lasts a minute or two).
  So a run that mixes both kinds, in whichever order, waits as long as a provider in trouble: a day of 503s
  followed by a 404 still waits the day. "In a row" means without a renewal that worked in between; the
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
- With `MEALPLANNER_OIDC_GROUP` set (issue 314), a token must list that group in its `groups` claim, an array of
  strings: Authelia's `groups` scope, put in the access token by the client's claims policy (`docs/deploy.md`,
  "Who may use it"). Any other token is refused on every route that needs a login with 403 and
  `{"error": "<a sentence>", "reason": "not_in_group"}`, or `"no_groups"` for a token without the claim at all (a
  login from before the setting, or a provider that does not put the claim in its access tokens). The refusal comes
  before the account is looked up, so a refused login gets no account; one that had an account keeps its data, and
  finds it again once it is in the group. `GET /api/auth/config` names the `groups` scope only while the setting is
  on (a provider refuses a login asking for a scope its client may not have), and the apps ask for exactly the
  scopes it names, installed ones up to v0.1.9 included. Authelia reads the account's groups again for every token
  it issues, a renewal too, so taking someone out of the group stops them at their next renewal (an access token
  lives an hour by default).
- On that 403 the app ends the session at once, as when the provider refuses a renewal, and the login screen says
  "This account may not use the meal planner yet. Ask the person who runs this server." (`not_in_group`), or asks
  to log in again (`no_groups`); it opens no login page by itself after it. Only when the refused token is still
  the stored one, so an answer for an old session ends none that replaced it, and not in the Android app's background
  check, which leaves it to the next request in front. Installed apps up to v0.1.9 do not end the session: they show
  the server's sentence where a screen fails to load, until the user logs out and in again.
- Data from before accounts existed belongs to account 1, which `MEALPLANNER_OWNER` (a username)
  claims on first login, or else the first account to log in.
- **Registration** (issue 314; `docs/deploy.md`, "Registration"): with `MEALPLANNER_REGISTRATION` on, the registration
  helper set up (`MEALPLANNER_REGISTRATION_HELPER_URL`, `..._SECRET`) and `MEALPLANNER_OIDC_GROUP` set, a person can ask
  for an account without logging in. `GET /api/registration` answers `{"open": true}` then (else `false`), and
  `POST /api/registration` with `{"username", "displayName", "email", "password"}` makes the account in the directory
  (LLDAP) through the helper at once, in no group, with that password, and answers 201 `{"username": "<in lower case>"}`.
  The server keeps the user name, name, address and time, never the password. Refusals are
  `{"error": "<a sentence>", "reason": "..."}` (`RegistrationRefusal`): 404 `registration_off`; 400 `invalid`, with
  `"field"` naming the field that breaks a rule of `RegistrationRules` (shared, so the form checks the same; an e-mail
  address is printable ASCII), or without one should the helper's own check refuse what the server let through (both
  sides' tests read `scripts/registration-rules-cases.json`, so that they agree); 409
  `unavailable` for a user name or e-mail address in use in the directory, a user name waiting already, and the user name
  of a meal planner account (Authelia would give the new person that account's subject), one answer for all, so the form
  tells nobody which; 429 `too_many` with `Retry-After` (5 requests an hour per address, the last one of
  `X-Forwarded-For`; 30 a day from everyone; counted before the body is read); 503 `full` (20 waiting) and
  `directory_down`. Until approved, every route answers that person's tokens 403 with
  `"error": "Your request is waiting for approval."`, `"reason": "not_in_group"` (which installed apps act on) and
  `"detail": "waiting_for_approval"`; when the access token has no user name the provider's userinfo is asked, and only
  while a request waits.
- The owner's account (account 1, and with `MEALPLANNER_OWNER` set only under that name) decides: `GET /api/me` says
  `"approvesRequests": true`, `GET /api/notifications` lists the user names waiting in `accountRequests`, oldest first,
  `GET /api/registrations` lists the requests (`AccountRequest`, with `requestedAt` and `expiresAt`), and
  `POST /api/registrations/{username}/approve` (the helper puts the account in the group) and `.../reject` (it deletes the
  account) answer the requests still waiting. Every other account gets 403 `not_owner`; a request not waiting is 404, one
  decided in LLDAP by hand meanwhile 409 (and gone from the list), the helper down 503 (the request stays). A request
  waiting 14 days is rejected by the server's hourly round. Requests, decisions and the expiry run one at a time, so none
  acts on an account another is still making or removing.
- In the app (from the release after v0.1.11) and the web client, the login screen of a known server asks
  `GET /api/registration` while nobody is logged in and offers **Register a new account** only on `{"open": true}`; a
  server from before the route, one that is off, or one out of reach offers nothing. The form (`RegistrationScreen.kt`)
  asks for a name, a user name, an e-mail address and the password twice, and checks them with the shared
  `RegistrationRules` as they are typed: a field says what it must hold once something typed in it is left, and every
  field after Send, which sends nothing while one is wrong. Each refusal is said by its `reason` in the app's language,
  not in the server's English sentence (`too_many` with the minutes of `Retry-After`, `invalid` marking the field it
  names); a taken request says that the account `<user name>` waits for the owner's approval. The passwords live only in
  the form's state while it is open (`remember`, never `rememberSaveable`), so they are in no stored setting and not in
  Android's saved instance state, which can outlive the process on disk: when Android re-creates the screen (a
  rotation) the two password fields are empty again while the rest stays. `RegistrationDraft` and `RegistrationRequest`
  print without them, so no log line or error shows one. A login the server refuses with `waiting_for_approval` ends
  as one outside the group does, and the login screen says "Your request is waiting for approval." (installed apps up
  to v0.1.11 say the not-a-member sentence). With Authelia's policy of `docs/deploy.md` in place Authelia itself
  refuses that login before the app has a token, so the sentence shows only where it lets the login through (issue
  314, item 3 of its definition of done is open).
- The owner's account (`approvesRequests`) has a card **Requests for an account** at the top of Settings: how many wait,
  and per request the name, user name, address, when it was made and the day it is rejected by itself, with Approve and
  Reject; Reject asks first, as it deletes the person's account. A request gone meanwhile (404) or decided in LLDAP
  (409) is said so and the list asked for again; the helper down (503) leaves it waiting. No other account has the card,
  nor asks for the requests. The card asks again whenever the app comes back to the front with Settings open, and each list it
  gets makes the notification forget the user names no longer waiting. The notification "Account requests" (see
  "Notifications") tells the owner of a new one.
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
  an edit on the site reaches every account's bank (not the recipes an account took from it: see the next section).
  A repository of an account's own is fetched as often. A new account
  finds the server's site in its bank on its first visit there (`GET /api/recipes/bank` imports it when the account
  was never synced), without waiting for the schedule. `POST /api/sync/cooking` fetches the account's site at once;
  the server's site at most once every five minutes (`COOKING_REPO_FETCH_INTERVAL_MINUTES`; issue 215), as every
  account's imports wait while it is fetched. A sync within five minutes of the last fetch (by any account, the
  schedule, or one that failed) imports the clone as it is, without an error, and `fetchedAt` in
  `GET /api/sync/cooking` says when the server's site was last fetched. Without a clone yet (the first fetch
  failed) such a sync fails at once rather than trying again. The accounts that waited for a fetch then import
  at once; their imports write one after another, each in a transaction of its own, so one account's failure
  takes back nothing of another's (issue 246).
- Choosing another site leaves the old one at once: its bank-only recipes leave the bank with their pictures, and
  those the account took stay its own as they are, pictures and all (a file of the new site at the path of one of
  them is not offered, as that path is taken); the new site
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
  one per line of the error, and the app shows those lines. A bank recipe whose file leaves the site is hidden
  until the file is back.
- **A recipe taken from the cooking site** (added from the bank) is the account's own copy from then on (issue
  340): it is changed (`PUT /api/recipes/{id}`) and deleted (`DELETE /api/recipes/{id}`) as one made in the app,
  and a sync leaves it as it is, whatever happens to its file on the site, with the picture it had. It stays
  `"source": "COOKING_SITE"` in the section of the site it came from, so an installed app shows it as before (it
  offers no editing for it, and "Remove from my recipes" puts it back; the app from issue 342 on edits it, see "Writing a recipe in the app"); `removedFromSite` is never set for it.
  Its row keeps the file's path, so the bank does not offer that file as new. Put back unchanged, it is a bank
  recipe again, and the next sync makes it the site's file as it is then (or hides it, when the file is gone).
  Once changed in the app it cannot be put back (409 "… delete it instead"), as the next sync would undo the
  change unseen; deleted, the site's file is in the bank again after the next sync, as a file never taken. A bank
  recipe is the site's: changing or deleting it is refused (409 "Add … to your recipes first"). The recipes taken
  before this (database version 26, `25.sqm`) became such copies; those whose file had left the site lost that
  mark.
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

### A recipe's lines and their ingredients

A recipe's ingredient line is an amount, a unit, a text and the `# Group` heading it sits under, and it can carry
the catalogue ingredient it is (issue 340). A line with one is that ingredient whatever its text says and whatever
the account's links of texts (`/api/mappings`) say; a line without one is looked up by its text as before: the
account's link for the text, then an ingredient of that name. Nutrition, cost, the shopping list, the stock, what a
meal had in it, standard meals, toppings, the planner's dislikes and "Link ingredients" all read a line's ingredient
through that one lookup (`IngredientRepository.resolver`), so a linked line counts everywhere and is never listed to
link. A line's ingredient is one of the account's own: another account's is refused (404). Lines read from the
cooking site carry none.

`POST /api/recipes` and `PUT /api/recipes/{id}` take a recipe's ingredients in either of two ways (`RecipeInput`):

- `"lines": [{"amount": 400, "unit": "g", "text": "stewing beef", "ingredientId": 12, "group": "Stew"}, ...]`, each
  line read as the same line of the import format would be (the unit `g` is a weight, an unknown unit counts pieces);
  `amount`, `unit`, `ingredientId` and `group` may be left out. An amount must be above 0; a line with no amount, unit
  or text, and a recipe with no line, are refused (400). So that the installed app, which edits a recipe as the text
  of the import format, one line per ingredient, can save it again (issue 342): a line break in a text, unit or group
  is saved as a space; a `|` in a text is kept as it is (the import format reads everything after a line's second `|`
  as its text, so the installed app reads it back unchanged); a unit with a `|` is refused (400 `Line 1: a unit cannot
  hold |`), as the import format would read part of it as the text.
- `"ingredients"`, the import format as one text (`200 | g | low-fat quark`, `# Group` lines for headings), as
  released apps send it; read only when `lines` is not sent. Its lines carry no ingredient of their own, except that a
  replaced recipe's line keeps the ingredient of the first line it had with the same text (ignoring case and spaces),
  whatever its amount and unit. So an installed app that saves a recipe as text keeps the ingredients of the lines it
  left alone; a line whose text it changed is looked up by its new text.

`GET /api/recipes/{id}` gives each line as stored in `MappedLine.line` (`amount`, `unit`, `item`, `group` and its own
`ingredientId`, which is `null`, not absent, when it has none) and the ingredient it means now in
`MappedLine.ingredientId`. A recipe read this way and sent back with those fields as `lines`, with its `title`,
`servings`, `instructions`, `tags` and `mealSlots`, is saved unchanged, and such a save writes nothing: it does not
count as a change in the app (issue 342), so a recipe taken from the cooking site can still go back to the bank. A recipe
with no `servings` is read as 1 there, as the editor shows and sends 1 for it: saved untouched it stays without.
`RecipeSummary.edited` says whether a recipe was saved with a change in the app (false from servers before it).

`POST /api/recipes/lines` with a list of such lines answers them as a recipe saved with them would have them, each a
`MappedLine` with the ingredient it means for the account and its weight, and saves nothing; lines are refused as a save
refuses them (400, also for one with no amount, unit and text; the answer is one per line sent, so the route does not skip
any). The app's editor looks pasted lines up with it and leaves such a line out first.

**Writing a recipe in the app** (issue 342). "New recipe" on the Recipes tab, and Edit (the pencil) on any of my
recipes, also one taken from the cooking site, open the editor (`RecipeEditorScreen.kt`, the same on Android and the
web). Its ingredients are rows: "Add an ingredient" searches the account's ingredients (by their name and their NEVO
food's English or Dutch name) and, after a pause in typing, the NEVO foods it has no ingredient for yet; picking one of
those makes it an ingredient of the account first (`POST /api/ingredients`, named after the food, " (NEVO)" added when
the name is taken); in a row's dialog it is made only when the row is saved, so a cancelled dialog leaves nothing behind. A picked row starts in the unit the account last saved that ingredient in (issue 346): its unit in the recipe the
account saved most recently that has a line saved with the ingredient itself (`Ingredient.lastUnit`, which `GET /api/ingredients`
works out from the saved lines, so it is the account's own on every device; a line that means the ingredient only
through its text, as the cooking site's do, is not a pick and does not count), as one piece, one portion, 100 ml or 100 g. An ingredient
the account has not used, or whose remembered unit it no longer has (its weight per piece or density was removed), starts at
one piece when it has a weight per piece, else 100 ml of what is counted per 100 ml, else 100 g. Pasted lines keep
the unit of their text, a row already there keeps its unit, and an ingredient made on the spot from a food has no history.
Each row has its amount selected for typing (the keyboard's Next goes on to the next row's amount,
and from the last to the search). While the editor is open the keyboard makes room instead of sliding the screen up
(Android: the window's soft input mode is "adjust resize" and the column ends above the keyboard, `imePadding`), and
the focused amount field and what the search finds are brought wholly into view as they come and as the keyboard
opens. Its unit is chosen from those the ingredient has
(grams, millilitres when a millilitre's weight is known or its values are per 100 ml, pieces when a piece's weight is, its portions,
and the unit a line of the site had), and another unit keeps the quantity where it can be worked out: a weight as a weight
and a volume as a volume (a portion as what it holds, in g or ml, a piece by its weight), and the one into the other only
through a known density; else the number stays. The quantity is not rounded: the field shows a converted amount to a
thousandth (grams and millilitres from 10 on to a tenth) and the row keeps the exact one, so 100 g changed to a pack of
650 g shows 0.154 and back is 100 g again. A row of a volume whose weight is not known says its millilitres. A portion's
name is a size, not the ingredient: "1 pack" of a drink is called by the drink's name, and its saved text has no unit. Nothing is typed in the import format, and a
picked row is saved with its ingredient (`lines`). The bar under the title says what a serving has and what is not
counted, worked out in the app from the catalogue and each row's ingredient with the server's own code
(`NutritionCalculator.ofLines`, reading each line as the server stores it): the saved recipe has the same numbers.
Each row says its ingredient with its weight and kcal, or why it is not counted. "Paste lines" takes plain lines, one
per ingredient, in Dutch or English (`200 g kipfilet`, `2 el olijfolie`, `1 ui` for one piece, `zout`; `PlainLines.kt`:
an amount, a unit of the unit table or a word that portions such as `blik`, then the text; a line that does not start
with an amount is all text; a line of only `|` and blanks is left out, as an empty one), and lines of the import format with their `# Group` headings; each row is looked up as a
save would look it up. A row that means nothing says so ("Not counted: tap to choose the ingredient"); tapping it (or
any row) searches with its text to pick its ingredient, and changes its text and group (opening a dialog lets go of the
field being typed in, so the keyboard goes; in the browser the paste field has the focus at once). Left as it is, it is saved as
text, without an ingredient. Lines of a site recipe that the site could not read are offered as rows. The steps are the
recipe's Markdown as one text, saved as it was when not edited. Tags are added and taken off, groups named ("New
group") and renamed (the pencil on a heading), the meals it is planned as and how long it keeps once made are chosen
there; how long it keeps is saved with `PUT /api/recipes/{id}/keeps` after the recipe. A row, tag, title or step left
alone is saved exactly as it was read, so opening a recipe and saving it changes nothing. On the recipe's screen, one
taken from the cooking site and not changed in the app offers "Remove from my recipes"; once changed, and any made in
the app, "Delete" (for a taken one the dialog says the site's recipe comes back to the bank). "Remove from my recipes"
on a selection leaves out those made or changed in the app and says how many.

### An ingredient's nutrition and its portions

**What an ingredient counts by** (issue 351). An ingredient with values of its own (`Ingredient.label`: a product's
label, per 100 g or per 100 ml) counts by those; one without counts by its NEVO food, as before; one with neither is
not counted (the recipe says so). An ingredient may have both: its own values win, and its NEVO food still gives it a
shop section. A value the label does not give is `null`, unknown, never 0; in a recipe's sum it adds nothing, as a NEVO
value that is missing does. Salt is typed and shown as grams of salt, as on a Dutch label, and kept as sodium in mg,
as NEVO has it: sodium mg = salt g × 400 (`Salt`, the one place that converts; see `docs/data-sources.md`). A label
without energy (kcal) is refused (400), since it would add nothing to a day and still count as counted. A label's
values per 100 ml count a line in ml by its volume, without a density, as a drink of NEVO does.

**Portions.** An ingredient has any number of named portions (`Ingredient.portions`: "bar" of 45 g, or for a drink
"glass" of 250 ml; each is a weight or a volume, not both). A portion is a unit of that ingredient: a recipe line of it
with the portion's name as its unit (`2 | bar | Protein bar`, saved with its `ingredientId`) is weighed by the portion
each time it is counted, in nutrition, cost, the shopping list (as grams, not pieces), the stock and what a meal had in
it; so correcting a portion's weight corrects every recipe that uses it. Names are compared without regard to case. A
name must not be empty, hold `|` or a line break, or be a unit of the unit table (`g`, `el`, `tbsp`, `piece`, `stuks`
and so on, which a line would be read in instead), and two portions of one ingredient may not share one: each is
refused with a 400 that says why. A word such as `blik` or `plak`, which the table does not know, may be a portion; a
line in it is then weighed by the portion rather than by the weight per piece. A portion that is removed is kept as
removed (`Ingredient.removedPortions`): a line in it is said not to be counted, never counted as grams or pieces, and
"Link ingredients" does not ask a weight per piece for it; given again (in any case) it counts again. `gramsPerPiece`
and the unit "piece" are as before.

`POST /api/ingredients` and `PUT /api/ingredients/{id}` take `label` (null: none, counted by the NEVO food) and
`portions` (the whole list, in its order; left out, a `PUT` keeps the portions there are). Another account's ingredient
is 404, as for a line's `ingredientId`. The app sends neither field when it has none, so its body for a NEVO food is the
one servers before them take. The installed app (v0.1.12) reads a recipe with a line in a portion as any other: its row
editor offers the line's unit as the unit the row already has and sends it back through `lines`, and an older app's
text keeps the line's ingredient as any unchanged text does, so the line counts by the portion either way
(`ReleasedAppTest`). Its own totals while editing do not know portions and show such a row as not counted; the saved
recipe counts it.

**In the app.** "Link ingredients" (the Recipes tab's menu) lists the account's products, those with values or portions
of their own, under "Products", with "New product". The product's form (`ProductScreen.kt`, the same on the web) has a
name, the values per 100 g or per 100 ml (energy in kcal, fat, saturated fat, carbohydrates, sugars, fibre, protein,
salt; a decimal comma or point), and its portions as rows of a name and a size, in g or ml (a tap on the unit changes
it; a new row starts in the unit of the values). Whatever keeps Save off is said in red at the field that causes it: a
name or the energy that is needed, a value that is no number or below 0, a portion without a name or a size, one named
like a unit (`“g” is a unit already: give the portion another name`) or like another. The keyboard's Next goes through
the values in the label's order and from the salt on into the portions (a portion's name, its size, the next portion);
the last field's key is Done. Saving without a portion the product had (removed or renamed) asks first, saying that
recipes with a line in it will no longer count that line; "Keep it" puts it back. The form does not know how many
recipes use it: that would need a route of its own. What the form does not show of an ingredient (its NEVO food,
weights, price, being a staple) is saved as it was. A product is found by the recipe editor's search by its name, with
its own values, and its portions are units of its row after grams, millilitres and pieces; a portion removed is not
offered, and a row in one keeps it on its button and says it is gone. A picked product whose last saved unit is one of
its portions starts at one of it. Under what the editor's search finds, "New product “…”" opens the product's form with
the text typed as its name and in its lookup (issue 354); saved, the product is a row of the recipe, and the recipe
being written waits as it was (cancelled, nothing changes).

**Looking a product up** (issue 354). At the top of the product's form, "Look up at AH" takes a name or a barcode. A
name is searched at AH, which lists what it finds with brand and pack (a multi-pack with how many it holds); the one
picked fills the form. A barcode (8 to 14 digits) fills it at once: from AH, or from Open Food Facts when AH does not
have it or has no table that reads, and only when the server has a contact for it (`MEALPLANNER_OPENFOODFACTS_CONTACT`).
Filled are the name, per 100 g or per 100 ml, the eight values (each the source does not give, or gives in a form that
cannot be read, left empty, and the form says which), and the pack's net content as a portion "pack" (of a multi-pack
its single item, "item") to rename or remove. Under the name the form says "Values from AH" or "Values from Open Food
Facts" (with the credit and link Open Food Facts' licence asks); a value or the basis changed afterwards makes it
"Values from AH, changed by hand", which is saved with the product. A product a source has without any value that reads
(a table per portion only, or AH's without a table) gives its name and pack only: the form says its values are to be
typed, shows no source, and saves none. Values whose basis the source does not say (Open Food Facts without a quantity)
fill the form with neither "per 100 g" nor "per 100 ml" chosen, and Save waits until one is. A source that does not answer is said in the form,
which is typed by hand as before. Nothing is saved until Save. The source is kept in `LabelNutrition.source` as
`ah:<AH's product id>` or `off:<barcode>`, followed by `;changed` after a change by hand. A value is saved as it was
given or typed: the form shows at most three decimals (a salt of `0,005` g as such), and a value left as shown is saved
as the number it stood for, so a product opened and saved without a change is the same.

**Scanning a barcode** (issue 356). In the Android app, on a phone with a camera, "Scan barcode" sits next to "Look up":
the camera opens full screen with a frame to aim at, reads the first EAN-13, EAN-8 or UPC-A in view (the check digit must be
right; the code may be held either way up) and closes itself; the digits go into the lookup's field and are looked up as
if typed, by the same route (`GET /api/products/barcode/…`). Only the digits leave the scanner: no picture is saved, shown
afterwards or sent, and a frame is dropped once read. The decoder is ZXing's (`com.google.zxing:core`, Apache-2.0), which
ships inside the app, with CameraX for the preview: no Google Play services (`:androidApp:assemble…` and `lint…` fail when
the app's runtime dependencies hold `com.google.android.gms`), so it works on a phone without them. The camera's permission is
asked when "Scan barcode" is first used, not at start; refused, the form says so under the lookup, with what to do (type
the digits, or allow the camera in the phone's settings) and a button to the app's page there; the next tap on "Scan barcode"
asks again, or opens the scanner when the camera was allowed in the meantime. The scanner stays open when the phone is turned.
Its close and torch buttons sit on dark discs, so that they show on a white pack. The
manifest marks the camera `required="false"`: a phone without one installs the app and has no scan button. The web client
has no scanner and types the barcode. The decoding (`BarcodeReader`) is kept apart from the camera and tested on pictures
made with ZXing's writer.

The routes, behind the login like every other, read and save nothing of the account; what the app sends is checked
before anything is asked (400 otherwise): `GET /api/products/search?q=<2 to 80 characters>` answers `ProductSearch`
(`products`: AH's `id`, `name`, `brand`, `netContent` and of a multi-pack `packOf`, its `id` being that of one item);
`GET /api/products/ah/{id}` (digits; `?packOf=` as the search gave it) and `GET /api/products/barcode/{8 to 14 digits}`
answer `ProductLookup`: `product` (`source`, `name`, `brand`, `basis`, `per100` with salt as sodium, `unread`, `pack`,
`packOf`), null when not found, and `problems`, codes of what went wrong at a source (`ah_failed`, `ah_no_table`,
`off_failed`, `off_busy`, `off_not_configured`; an app takes one it does not know as a problem). A source failing is in
`problems`, never a failed request. Open Food Facts is asked at most 15 times a minute for the whole server; beyond that
the answer is `off_busy`. Where the values are read and under which terms: `docs/data-sources.md`, "Nutrition: a
product's label".

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

## Planning the week

The week's dinners can be chosen by hand from suggestions, after which the planner puts them on days and fills the
rest (issue 255). In the app, "Plan the week" on the Plan opens the planning screen (`PlanningScreen.kt`); there is
no one-tap Generate any more, and "Fill the rest" with nothing chosen is what Generate did. The screen, titled "Plan the
week" (short enough for the largest font), names the week and how many dinners are still to choose, shows the strip
(the week's cost and saving "for 9 meals", as each chosen dinner counts with its leftover lunch, which is why one
dinner of ≈ €3.07 is ≈ €6.14 for 2 meals there and on the Plan; and its calories, protein and fibre against what
those meals should give: only shown, never a warning; its explanation is the last line of the list), the dinners
chosen (one line of titles with their count, "Show" opens each with a button to take it out, so that the suggestions
keep the screen on a phone; one whose recipe lost its amounts says so and is open from the start, as Done refuses until
it is out), about six suggestion cards with a line per
reason ("On offer: chicken thigh fillet at AH (25% korting)", "Uses what is there: feta", "Favourite: rated 5 of 5 ·
not eaten for 4 weeks", "Not tried yet"), the price of a serving with the saving, and what the planner's portion gives.
Tapping a card adds it; "Not this week" hides it, and the hidden recipes are listed with "Show again"; "Show others" asks
for the next ones with those shown so far, and with nothing to suggest at all the screen says why (no dinner of the
rotation fits that is not chosen or hidden) with "Open recipes"; "Something else" searches every meal of mine, a chosen
one marked "✓ Chosen for this week". The button at the bottom says
"Done" once every open day has a dinner chosen, "Fill the rest" before. While dinners are chosen and not on days, the
Plan's button says "Continue planning: 2 dinners chosen" (`WeekPlan.dinnersChosen`). On the Plan, "Another recipe" on a
dinner opens the same suggestions for that one meal, with "Let the planner choose" for one that is not locked; a
dinner's menu has "Move to another day…" where the server lets it move (`canMove` and `whyStays` in `MealKept.kt`),
listing the other days with the dinner it would swap with; a day that has passed, or whose dinner stays, is greyed and
says why ("Lentil soup stays on its day: it was eaten"). The "Plan next week" notification opens the
planning screen of next week. A server from before the screen answers 404, and the screen then plans the week in one
go, as before; "Another recipe" says the same sentence there (the planner's own choice stays). A week wholly in the
past says that it is over and offers nothing to plan: the app decides it from the week's dates. Everything is under
`/api/plans/{weekStart}/planning` and per account:

- `GET planning` (`WeekPlanning`): the days whose dinner is still to choose (`openDays`: today and the days after it
  without a dinner the week keeps when it is planned, which is one locked, prepped, eaten, skipped, with a prepped side or foods
  or with a skipped share in the box under it; a day that has passed is no day to choose a dinner for, and of a week
  wholly ahead every day counts), the dinners chosen so far in the order they were, each at the portion the planner gives
  a dinner of it, the recipes hidden for the week, and `totals`, the strip: what the week's kept meals and the chosen
  dinners (on the days they would get, with their leftover lunches) cost with and without offers, and their calories,
  protein and fibre against the share of the daily targets those meals stand for. They are summed by the Plan's own
  code, and after "Done" the Plan has those same meals with those same numbers.
- `GET planning/suggestions` (`DinnerSuggestions`, 6 unless `?count=`, at most 50): recipes of the rotation the
  planner would take as a dinner (a portion within the profile's range reaches at least half a dinner's share of the
  calories; lighter recipes are never suggested, though the search finds them), each on at least one ground: **on offer** (an ingredient is on offer at one of the
  account's stores during the week and that takes at least 5 cents off a serving; each offer named with the store,
  its deal and what it alone saves), **uses what is there** (an ingredient on hand, or at least 5 cents' worth of the
  rest of a fresh pack a chosen dinner opens, named with that dinner), **favourite** (rated 4 or 5, or last eaten
  three weeks or more before the week) and **not tried yet** (never planned, or only ever skipped, before the week or
  in it). A recipe without a ground is not suggested; search finds it. They are rated by the planner's own score for
  a dinner (calories, protein, fibre, price with offers, rating, repeats, last week, the packs the chosen dinners
  open), without a day or chance, and mixed from the grounds in turn, in that order, so that a screen holds every
  ground that has recipes and none crowds out the others, however many it has. Each ground's best comes first in
  its turn; among those not tried yet, the rating comes first (unrated counting as 3) and the score after. A recipe of a batch prepped this
  week is not suggested while any other recipe is, as the planner plans it again only when nothing else fits. Chosen and hidden recipes are left out, and so are those in `?seen=12,15`:
  "Show others" sends the ids it showed. A cursor would skip or repeat recipes, as the order changes after every
  pick; the ids do not, and the server keeps no state per screen. With `?q=` it is a search ("Something else"):
  every meal of mine whose title has each word, in the rotation or not, best first, whatever its grounds.
- `PUT` and `DELETE planning/picks/{recipeId}` choose a dinner and take it out; `PUT` and `DELETE
  planning/not-this-week/{recipeId}` hide a recipe for the week and show it again. Each answers `WeekPlanning`.
  They are stored as they are made (`planPick`), so leaving the screen or the app loses nothing, and
  `WeekPlan.dinnersChosen` says how many dinners are chosen and not yet on a day ("Continue planning"). Any of my
  recipes that is a meal with amounts can be chosen, whatever its calories: nothing is refused or warned about for
  those. More dinners than open days is a 409. A chosen dinner whose recipe has since lost its amounts stays in
  `picks`, with `plannable` false and no numbers, so that it can be taken out: Done refuses (409) until it is. Hiding
  changes nothing in the rotation; a hidden recipe chosen is no longer chosen. Both are forgotten once their week is over.
- `POST planning/done` ("Done", or "Fill the rest") answers the `WeekPlan`. Each chosen dinner goes on an open day of
  its own, locked as a recipe chosen by hand, with its leftover lunch the next day where that lunch is free. Of all
  the ways to put them on days, the one taken leaves the least rest of fresh packs, by the planner's own bookkeeping
  (`OpenPacks`), so dinners that share a pack land within the days it keeps; then the fewest leftovers without a
  lunch to go to (with a dinner for every day, one that leaves no leftovers goes on Sunday); then the fewest dinners with the same first tag as the
  day before. A chosen dinner never goes on a day that has passed. Then the planner fills everything else as
  `generate` does, keeping what it keeps, past days of the week too (an empty or replaceable meal of a day that has
  passed is planned, as Generate has always done), and never plans a
  hidden recipe: it is no candidate, though the planner still knows it (`PlanRequest.known`), so a kept dinner of it
  keeps its leftover lunch. With nothing chosen nor hidden it is `generate` itself, by the same code. The chosen dinners are
  then no longer "chosen"; the hidden recipes stay hidden for the week.

`POST /api/plans/{weekStart}/meals/{day}/DINNER/move` with `{"toDay": 3}` moves a dinner to another day of the week,
swapping it with the dinner there, each as it is (recipe, portions, lock, status, what was in it). The lunches after
the two days follow: each dinner's leftover lunch goes with it, with what was in it, or the dinner gets the leftovers
the planner would give it there; a day left without leftovers keeps a lunch of its own, or takes the one the
leftovers pushed out, and else its lunch goes (it was the leftovers of the dinner that left). A lunch that is locked,
eaten, skipped or has a side stays as it is. Refused (409, nothing changes) for a dinner that keeps its recipe (eaten,
or of a batch made or that it makes, with the server's own sentences), a later meal of a prepped batch, one with a
prepped side or a share in the box under it, and one whose leftover lunch can't follow, and to or from a day that has
passed ("Tuesday 17 November has passed: a dinner moves only between today and the days after it"); another slot than
dinner, or the same day, is a 400.

"Another recipe" (`reroll`) leaves out the recipes hidden for the week, as Fill the rest does: they are no candidates,
and known, so a kept dinner of one keeps its leftovers.

The planner knows the recipes of locked meals that are not in the rotation (`PlanRequest.known`): such a dinner,
chosen by hand, leaves its leftovers for the next lunch when the week is planned again, as a dinner of the rotation
does. It never picks them by itself.

## Prepped meals

A recipe can be made once and eaten over several days (issue 234), as the meals themselves, as a side next to
them, or one batch partly each way (issue 269). Only by hand: the planner never starts a prep nor adds a side, it plans around them.

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
  prepped dinner leaves no leftover lunch, and the recipe of a batch in the week (as meals or sides, also one made the
  week before) is planned again only when no other recipe fits the slot, even when that means repeating the others
  in a small rotation (for a lunch, neither a lunch recipe nor a dinner recipe cooked for lunch); then every meal or
  side of the batch counts as a use of it.
  The made-on day's meal can be neither replaced nor removed on its own (409, naming the later meals: `DELETE
  /api/plans/{weekStart}/preps/{id}` removes the prep with all its meals, in every week, after the app named
  them); a later meal can, which makes the batch smaller, until the batch is made. Unlocking a prepped meal is a 409.
- **Bought and taken once.** The shopping list of the made-on day's week buys the whole batch, also for meals the
  week after, which buys nothing for them; the planner's pack rest counts it as one purchase. The batch is made
  when the first of its meals is eaten, whichever: each meal's share is then taken from the stock of the made-on
  week, and un-eating the last eaten meal gives all of it back (`docs/data-sources.md`, "Un-eating"). Once made,
  the batch and its meals stay (409 for removing, in words of sides for a batch eaten as sides): a meal not eaten
  is skipped.
- **A skipped meal's share** stays in the batch. `GET /api/preps/spare/{date}` lists the shares still in the box on
  that date (`SparePortion`), each with the later lunches and dinners up to its last day that have room (empty, or
  a meal still to eat that is not locked and of no prep; for a side a meal still to eat without a side, of another
  recipe), and the next free one, in whichever week (`proposed`: the earliest that is empty; for a side the earliest
  such meal; null when none is). On its last day it is to eat that day or throw away; from
  the day after, it is not listed, and it is never planned on its own. `POST
  /api/plans/{weekStart}/preps/{id}/spare` with `{"from": {"date": ..., "slot": ...}, "to": {...}}` eats it there:
  the meal there goes, the skipped meal keeps a share of 0 (and can no longer be marked eaten), and what the stock
  gave for it goes along, so the batch, the list and the stock stay as they were. A standard meal may take the skipped
  meal's slot meanwhile; its share stays in the box all the same ("Standard meals"). The morning after the last day,
  the "Prepped food to throw away" notification (issue 264) names what may still be in a box: the shares of skipped
  meals or sides not eaten elsewhere, and the meals of the batch never marked, portions and sides counted apart
  (`PrepPastLastDay.left` and `unmarked` count both, `leftSides` and `unmarkedSides` the sides among them;
  `firstUnmarked` and `firstLeft` are the days of the first of each, for the tap to open; `side`, true when every
  share is a side, is what apps before issue 269 word it by). A batch never made (no meal of it
  eaten) has nothing in a box and is not named.
- **Into the next week.** A prep may run over the week's end. Its meals are in the next week before that week is
  planned, and generating it keeps them; a week with only those counts as not planned yet (the "Plan next week"
  notification, buying ahead, whose suggested dinners are then no more than the dinners those meals leave free).
  Its shopping list buys nothing for them and says which week's list did (`ShoppingList.preppedBoughtIn`).
  Today's "Dinner today" notification is only for a dinner with something to cook: not for a prepped later dinner,
  unless a side's batch is made next to it that evening, when it is about the side (`DinnerToday.side`: its tap opens
  the side's recipe with the dinner's place, and as a side follows its meal, cook mode's "Mark as eaten" marks the
  dinner, side and all); the dinner
  that makes a batch says so (`DinnerToday.prep`), so its recipe speaks of the prep's later meals.
  A shortfall warning ("What was in a meal") counts what the batch took for its meals of the next week in the week
  it was made, whose stock it was cooked from.
- **Sides.** A meal of `PrepInput.meals` with `"side": 0.25` or `0.5` (else 400) eats the batch there as a side of
  that many servings next to the slot's meal, which must hold a meal still to eat without a side, whose main is
  another recipe, as no plate holds one recipe twice (409 naming the slot otherwise); the meals without one eat it as
  the meal itself, so one batch may be both, with sides of both sizes
  (issue 269). It is the sum of all its shares, and made with its first, a meal or a side, on the made-on day.
  `PrepInput.side`, as apps before issue 269 send it, makes every meal such a side; both at once is a 400. A batch
  still to make grows by a side: `POST /api/plans/{weekStart}/preps/{id}/sides` with `{"date": "2026-11-19", "slot":
  "DINNER", "side": 0.5}` puts one next to a lunch or dinner after its first share, up to its last day, today or later,
  that has a meal still to eat without a side, of another recipe, and is not a meal of the batch (409 otherwise, and
  once the batch was made; 404 for another account's batch); it is bought and cooked with the batch. The batches a
  week's sides can be taken from are in `WeekPlan.batchesToMake` (`BatchToMake`: the recipe, its title and the batch):
  the account's own batches still to make whose days, from the made-on day to the last, reach into that week, also
  those with no meal or side in it (issue 263).
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
  made with or one of a batch that was made (409). Removing a prep eaten as sides takes the sides, the meals stay;
  of one eaten both ways, its meals go and its sides leave the meals they go with.
  A skipped meal's side is a share in the box (`SparePortion.side`) whose room is the later meals still to eat
  without a side; eaten there, it is the side of that meal. The side the batch is made with stays where it was at
  0, as a skipped meal of a batch does: the batch is still made that day, with that meal, which shows the batch to
  cook and can't be removed on its own.
- Kept in `prep` and `prepPortion` (version 20 of the database). A `prepPortion` row is a meal's share of a batch,
  with a role: `MEAL` for the slot's meal, `SIDE` for a side next to it, with its own portions, and `BOX` for a skipped
  meal's share whose slot a standard meal took ("Standard meals"). What a side takes from the stock is kept under its
  meal apart from the main's (`stockUse.role`, version 21), and so is what a share in the box took.

**Eaten meals on the Plan (issue 191).** A meal marked eaten shows "✓ Eaten" on the Plan as it does on Today (a skipped one is
struck through). The menu behind its swap button offers only what the server does: for a meal that keeps its recipe (eaten;
the leftover lunch of an eaten dinner; any meal of a prep that was made; the meal that makes a prep) it has no "Another
recipe", "Choose a recipe…", "Write a new recipe…" or "Prep this recipe…" (each answers 409), and says why in a greyed first
line; "Remove the prep…" stays, as its dialog explains what goes or that a made batch stays. A side of a made batch, or one that
makes it, has no "Remove the side" but "Remove the side's prep…"; a prepped meal whose share is eaten elsewhere has no "Eat it
after all". A Monday lunch whose dinner is in last week's plan is still offered what the server may refuse, and its 409 says why.

**In the app.** A recipe's settings have "Keeps once made": in a box and vacuum sealed, each "not prepped" or 1 to 7
days. On the Plan, "Prep a recipe…" under the replan button, or "Prep this recipe…" in the menu of a lunch or dinner
whose recipe keeps (that day and meal already chosen, from today on), opens the Prep dialog: the recipe, the day it is
made (a day of the week shown, today or later: a day that has passed is not offered, as the server refuses it, and a
week that is over has no "Prep a recipe…"), in a box or vacuum sealed, and per day up to the last day it keeps its lunch and dinner; it says why
it can't be made yet (no meal on the made-on day, fewer than two meals); above it, a line says what a prep needs and
that each meal picked takes the place of what is planned there. The dialog's recipes are read again whenever it opens
and when the app comes back to the front, so keeps set meanwhile (also on another device) are there. A prepped meal is
labelled "Dinner · prep day" where it is made and "Lunch · prepped Mon 16 Nov" after, on the Plan and on Today, with "·
vacuum sealed" added for a batch kept that way (its sides too); its lock can't be undone, and its
menu has "Remove the prep…", which names every meal that goes, with those eaten or skipped already, before it removes
them; once one was eaten the batch was made, and the dialog says beforehand that it stays and its meals are to be
skipped instead (of a batch eaten as sides, in words of sides), with nothing to confirm. The recipe opened from the prep
day says "Cook 4 servings: 1 now, 3 for the prep's later meals", as cook mode does too; from a later day "Prepped on
Mon 16 Nov: nothing to cook", with what is in that meal's box ("In this meal: 1.25 servings") rather than the recipe
as written. A skipped meal's share still in the box today is a card above the days of the weeks it can still be eaten
in (not on a week that is over): when it was skipped and until when it keeps (on its last day "eat it today or throw
it away"), a button that eats it in the next free lunch or dinner within the days it keeps in one tap, as the server
proposes it (an empty one, in whichever week; for a side a meal still to eat without one), none when no lunch or dinner
is free, and "Eat it on…" with the others, where taking the place of a planned meal is the user's choice. A week whose only meals are of prepped batches, made in it or in the week before, still reads "Plan this
week" and names no slot as lacking recipes; a week holding only last week's batch has a Shopping that says the meals
were bought with last week's list. "Keeps once made" is only on a meal (a building block is
never prepped), and while a choice is saved both numbers wait for the answer.

The Prep dialog also asks how it is eaten: "The meal", or "A side" of 0.25 or 0.5 servings, which is how each lunch
or dinner picked next is eaten, so one batch can be the meal on some days and a side of either size on others (issue
269); each picked one says so ("Lunch · side 0.5"). As a side, only lunches and dinners with a meal still to eat and no
side yet, whose main is of another recipe than the batch, can be picked (of the week shown and the next); one picked another way can always be taken off. "Add a
prepped side…" in such a meal's menu offers the account's batches that are still to make, made before the meal
and keeping until its day at least, of another recipe than the meal's, to take a side of 0.25 or 0.5 servings from,
which makes that batch larger: also one made the week before with no share in the week shown (`batchesToMake`; from a
server before that, only those with a share in the week shown). "Prep a new side…" there, or the menu itself when no
batch can take one, opens the Prep dialog as a side of half a serving with that meal chosen. The side shows under the
meal's main, "Side · prep day" where it is made and "Side · prepped Wed 18 Nov" after, with its numbers and what
the meal comes to with it; a tap opens its recipe (the batch to cook on the prep day). The meal's menu then has
"Remove the side" and "Remove the side's prep…". A day card with sides that is over its calories says by how much
and what its sides add, as the day may have been over without them; Today's forecast counts them. A side needs a
second meal to go with, as a prep is made once for several meals, and the dialog says so.

An installed app that knows nothing of preps shows the later meals as locked leftovers with nothing to cook and
the made-on day's meal as cooking for the leftovers; it marks them eaten or skipped as any meal, and a refusal to
remove the made-on day's meal comes as a sentence. Of a meal with a side it shows the main alone, with the main's
numbers, while the day's totals count the side; marking the meal eaten eats the side with it. Of a batch eaten both
ways it shows the meals as above and each side's meal with its main, and the meal that makes it cooks all of it,
the sides' share too.

## Standard meals

A standard meal (issue 265) is one of my meals I can always make from what is at home, such as eggs on bread, offered
in one tap where a meal falls away. Only by hand: the planner never places one for being one, and picks a standard
meal in the rotation as any other recipe.

- **Marking.** `PUT /api/recipes/{id}/standard` with `{"standard": true}` makes one of my meals a standard meal, per
  account, and answers `StandardSwitch`: the recipe (`RecipeSummary.standardMeal`), the ingredients it made staples
  (`addedStaples`: those its lines are linked to that were not yet, each once, without those that count for nothing,
  such as spices and water, which the list never buys) and its lines linked to no ingredient yet (`unlinked`, without
  those used to taste), which could not be made one. `{"standard": false}` unmarks it and leaves the staples. 409 for a
  bank recipe, a building block and a recipe without amounts; 404 for another account's. Its own route, so a released
  app saving `PUT .../settings` leaves it alone; making the recipe a building block, or putting it back in the bank,
  ends it.
- **Offered.** `GET /api/plans/{weekStart}/standard-meals` (`StandardMeals`) lists the standard meals for each of the
  slots the recipe is set to be planned as (its "Plan as"), each at the portion that slot gets within the profile's
  range, as a recipe chosen by hand ("Portion per meal"), with its kcal, protein and fibre there, and in `missing` the
  ingredients of it the pantry has not at home that week: neither a staple nor on hand. `closed` names the slots whose
  meal no standard meal can take, and why: an eaten meal, the leftover lunch of an eaten dinner, the meal that makes a
  prepped batch (skipped or not), a later meal of a batch that was made and is not skipped (skipping it opens the
  slot), and a meal with a prepped side, which is eaten or skipped with whatever is in the slot.
- **Chosen.** `PUT /api/plans/{weekStart}/meals/{day}/{slot}/standard` with `{"recipeId": 12, "eaten": true}` puts it
  in the slot in place of what is there, sized as above and locked, as a recipe chosen by hand; with `eaten` it is
  marked eaten at once (what Today asks for a meal of today or a day before), so the day's calories, protein and fibre
  count it and it takes what it eats from the stock, given back when it is un-eaten, as for any meal. Its ingredients
  that were `missing` are made staples again, as a standard meal's ingredients are: the shopping list then names them
  among the staples, to restock, and has no row of them, as for every staple; the answer (`StandardChosen`) has the
  week and those names. 409 for a recipe that is not one of my standard meals or not set for the slot, and for a
  `closed` slot; 404 for another account's recipe.
- **Prepped meals.** A standard meal takes the place of a skipped later meal of a prepped batch too: the skipped share
  stays in the batch, in the box (`GET /api/preps/spare/{date}`), and is eaten elsewhere as any skipped share is; the
  batch is as large as it was and still cooked on its made-on day. Replanning, rerolling or choosing another recipe for
  that slot keeps the share in the box; removing the standard meal puts the skipped meal back in its slot with its
  share. A prep or a side of that batch can't take the slot then (409). What was in the skipped meal (issue 226) goes
  with its slot: a share in the box is eaten as its recipe has it. Kept as a `prepPortion` row of role `BOX` under the
  slot, with what the stock gave for it under `stockUse.role` `BOX`; the recipe's switch is `recipe.standardMeal`
  (version 22 of the database).

**In the app.** One of my meals' recipe screen has "Standard meal: I can always make this" under "In my rotation"
(not on a building block); the whole line toggles it, as it does "In my rotation". Switched on, it says which
ingredients are now staples and which lines are linked to no ingredient, while the recipe is open. On Today, a skipped
meal shows "Instead, one of my standard meals:" with a chip per standard meal for its slot, other than the recipe just
skipped, its calories at the slot's portion and, on a second line, an ingredient that is not at home ("no egg at home",
by the catalogue's name); one tap puts it there, eaten for today or a day before and still to eat for a day to come.
On the Plan, "Not eating this" opens the skip sheet with the same chips at once, and one tap puts the meal there still
to eat; "Leave it empty" keeps the skip. No chips where the server says the slot can't take one, and none when no
standard meal is set for the slot, so the skip is then as before. "Choose a recipe…" and an empty slot's picker list
the standard meals for the slot first, under "My standard meals", put in by the same route; on a later meal of a prep
still to make, still planned, the picker says that another recipe there makes the batch smaller. A standard meal's
menu on the Plan has "Remove from the plan" (unless it keeps its recipe, such as one eaten), which asks first by naming the meal, as the plan does
not say whether the planner or the user put it there: the slot is empty again,
or holds again the skipped prepped meal whose place it took. Choosing one whose ingredient was out says that it is
back among the staples, to restock; on Today the chips are off while one is being put in, so a double tap sends one request; the shopping list names it among the staples it uses, without marking it as one
to restock, as the server keeps no record of which staples a standard meal made again. Against a server from before standard meals
the app shows none of this, and the switch's change fails with the server's answer.

An installed app that knows nothing of standard meals shows one as the meal it is, locked, and the skipped prepped
meal's share in the box as before.

## Fibre and what the planner weighs most

Fibre counts next to protein (issue 223). The daily targets (`DailyTargets.fibreG`) suggest 14 g of fibre
per 1000 kcal of what keeps the weight (`TargetCalculator`), not of the lower target when losing it: the
Gezondheidsraad advises 3.0 to 3.5 g per MJ of the energy requirement (Voedingsnormen 2026, as the
Voedingscentrum gives it), and 14 g per 1000 kcal is 3.35 g per MJ. The profile can set its own target
(`Profile.fibreOverrideG`, 10 to 100 g). A recipe's fibre is its ingredients' per serving (their own values or their NEVO food's); one without a
fibre value counts as none, and the recipe is planned all the same. Extras typed by hand have no fibre; a food logged
from the catalogue has its own, stored with its entry (issue 362).

The planner rewards fibre as it does protein: how far a meal goes towards its slot's share of the target,
up to 1.5 (`WeekPlanner.score`). `Profile.priority` raises one part of the score and lowers none:

| Priority | Effect |
|---|---|
| Balanced (default) | calorie fit ×2, protein fit ×2, fibre fit ×1, price as is (+0.6 to −1.2) |
| Protein | protein fit ×3 |
| Fibre | fibre fit ×2 |
| Price | price doubled (+1.2 to −2.4) |

Calorie fit and the penalties for repeats and recently eaten recipes are the same under every priority.
The fibre of planned meals (`PlannedMeal.fibreG`) and of a plan's days (`PlanDay.fibreG`) is not stored: it follows
from the recipes' nutrition as it is now, as a meal's cost follows from this week's prices, so meals planned before
fibre counted have it too. The day view's (`DayView.eatenFibreG`, `expectedFibreG`) is that of the plan's meals the
same way, with the fibre stored with each food logged off the plan (issue 362) added.
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
  restores nothing. An added ingredient without values (its own or a NEVO food's) counts as nothing and the Adjust screen names it
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
pack being that of the ingredient's regular AH price, and is wary of a recipe that opens a new pack for a small amount. The Plan says under the week's cost what small rests (under half a pack) the week leaves ("About €2.40 of fresh ingredients left over: 100 g crème fraîche, ..."; `WeekPlan.rest`, an optional field). See `docs/data-sources.md`, "Packs".

## Foods in a slot

A slot of the plan holds a recipe, foods, or a recipe with foods next to it (issue 358): two snacks, a banana and a
protein bar, or a dinner put together from foods, without a recipe made by hand. On the Plan, "Add a food…" under a
day offers every slot of the day that takes foods, empty or not (next to "Add a meal…" for an empty one); a meal's menu
offers it too, and a slot with foods has "Add another food…" under them. A food is searched and picked
as in the recipe editor (the account's own ingredients and products first, then NEVO foods, "New product" last), in an
amount of one of its units: grams, millilitres where its weight per millilitre is known or it counts per 100 ml,
pieces where a piece's weight is known, or one of its portions ("bar"). It starts in the unit it was last picked or
saved in, in a slot or a recipe, and keeps the unit it was picked in: the slot shows "2 bar · 342 kcal" and the
amount is changed in it. Foods alone are listed where a recipe's title is, foods next to a recipe under its numbers; a
tap opens one to change its amount or unit, or take it out with its cross. Where one line names a slot of foods alone
(the move dialog, the skip sheet), the app words it from the foods in its language ("Banana + 2 meer"). A shopping row
names the slot a food is planned in ("snack on Mon 16 Nov"), never another food or the recipe next to it
(`ShoppingItem.foodSlots`, `toComeFoodSlots`; the food is not in `recipes`).

How it counts:

- A slot of foods alone is one portion of what is listed, each food in its own amount; its kcal, protein and fibre are
  its foods' as they are now. Foods next to a recipe count on top of the recipe's portions. Both count in the day's and
  the week's totals, Today, the cost, the week's shopping list (by their weight, as a recipe's lines are) and the stock
  when the meal is eaten; changing a food of an eaten meal moves the stock by the difference, taking it out gives
  back what it took.
- The slot is eaten, skipped, moved (a dinner) and removed as any meal; a single food is taken out rather than
  marked not eaten. Foods leave a slot only when they are taken out or the slot is removed from the plan: choosing a
  recipe, "Another recipe" or a standard meal for a slot with foods puts the recipe in next to them, also in a slot of
  foods alone. A prepped batch does not take such a slot (409: take the foods out first), and a share in the box is
  not offered one. The last food taken out of a slot of foods alone takes the slot with it.
- The planner leaves a slot with foods as it leaves a locked meal and never fills one with foods itself. A slot of
  foods is no recipe to the person: it is not in my recipes, the bank, the planning screen's suggestions or search,
  the recipes eaten before, the counts of "Link ingredients", or the titles a new recipe must differ from.
- Foods are not added to a skipped meal, nor to a meal of a prepped batch (409); a slot of foods alone has no recipe
  to adjust ("Adjust what was in it" is greyed) and no lock to take off.

On the server a slot's foods are the lines of a recipe made for that slot alone (`recipe.slotFoods`, never added, one
serving), which `planMeal.foodsId` names (version 28 of the database, `27.sqm`); with foods alone it is the slot's
recipe as well. So the shopping list, the stock and the nutrition read them as any recipe's lines, and an installed app
that knows nothing of foods reads a slot of foods alone as a meal of one portion of a recipe titled by its foods
("Banana, Protein bar", "Banana + 2 more"), with their numbers, and opens it as a recipe that lists them; a recipe with
foods next to it keeps its title, with the foods in its kcal and protein. That recipe goes once no meal holds it.

- `POST /api/plans/{weekStart}/meals/{day}/{slot}/foods` with `{"ingredientId": 7, "amount": 2, "unit": "bar"}` adds a
  food (an empty slot becomes a slot of foods), `PUT .../foods/{id}` with the same body replaces one, `DELETE
  .../foods/{id}` takes one out; each answers the week. `PlannedMeal.foods` lists a slot's foods (`MealFood`: its id,
  ingredient, amount, unit, weight and numbers), `PlannedMeal.foodsOnly` says there is no recipe. 404 for an empty slot
  or a food not in it, and for an ingredient not in the account's catalogue; 400 for an amount of 0 or less, one above
  5000 g, a unit the ingredient does not have, or more than 30 foods.

## Food eaten outside the plan

What was eaten besides the plan is logged on Today, under "Also eaten" (issue 362). Its main button, "Add a food", opens
the food screen of a slot (see "Foods in a slot") for that day: a food of the catalogue, an ingredient or a product, is
searched and picked in an amount of one of its units, starting in the unit it was last picked or saved in. The lesser
one, "Type kcal yourself", types a description with kcal and, if known, protein, as before. A logged food shows its
name, its amount in its unit and what it counted ("1 bar · 171 kcal · 14 g protein"); a tap opens it under "Change food"
to change its amount, its unit or the food, and Remove (or its cross) takes the entry away. A tap on a typed entry types
it over in the dialog, under "Change what you ate".

How a logged food counts:

- The server counts it from the food's values when it is logged, as a food in a slot is counted (its label's values, or
  else its NEVO food's), and keeps the kcal, protein and fibre it found with the entry. The day counts those in what was
  eaten and in the forecast, and its fibre with the plan's fibre (a typed entry has none). A food that counts as nothing
  (no values, or an amount it cannot weigh) is logged at 0 and says "not counted".
- The numbers stay what they were: a product's values changed later do not reach a day logged before, so a day that is
  over keeps its totals. Changing the amount (or unit, or food) counts it again from the values of that moment. Saved in
  the amount and unit it has, it is not counted again: the app sends nothing, and the server leaves an unchanged amount
  and unit as they were, whoever sends it. Opened after its food's values changed, the screen shows what the entry
  counted and says that the values have changed since and that another amount counts by the values of now; the numbers
  it shows for another amount are those.
- A food gone from the catalogue leaves its entries as they were, with its name, amount and numbers: they can no longer
  be counted again (409), and a tap opens the typed dialog, which says so, to type its numbers over or remove it. Typed
  over, an entry is a typed one.
- It was eaten outside the plan, so a logged food is not on the shopping list and takes nothing from the stock.
- Its unit counts as the one last picked for that food, as in a slot or a recipe.

The log is `foodLog`; a food's columns (version 29 of the database, `28.sqm`) hold the ingredient (`ON DELETE SET NULL`),
its name, amount and unit as picked, its fibre, whether it counted, and when it was picked. An installed app that does
not know foods reads such an entry as a typed one: the server writes its description in English from the food and amount
("2 bar Protein bar", "1 piece Banana", "150 g Rice"), with its kcal and protein; the app that knows foods words it
itself from `LogEntry.food`.

- `POST /api/log/foods` with `{"date": "2026-11-18", "ingredientId": 7, "amount": 2, "unit": "bar"}` logs a food (201),
  `PUT /api/log/{id}/food` with `{"ingredientId": 7, "amount": 1, "unit": "bar"}` changes it and counts it again (the
  food, amount and unit it has already change nothing), `PUT
  /api/log/{id}` with `{"description": "Apple", "kcal": 80, "proteinG": 0.5}` types any entry over, and `POST /api/log`
  (a `LogEntry` without `food` or `fibreG`) and `DELETE /api/log/{id}` add and remove a typed entry as before; each but the
  removal answers the day. `LogEntry.food` (`LoggedFood`: its ingredient, null once gone, name, amount, unit and whether
  it counted) and `LogEntry.fibreG` are null for a typed entry. 404 for an entry or an ingredient not in the account; 400
  for an amount of 0 or less, one above 5000 g or a unit the food does not have; 409 for counting again an entry whose
  food is gone, or a typed one.

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
shows first and the address is restored when it returns. Showing another screen rewrites the address of the
history entry the page is on and adds none; the entries come from the back handlers (below), and Android does
not use routes. Keyboard focus is shown by a ring in the theme's
primary colour (`FocusRing.kt`), on Android with a keyboard too; a text field in the browser keeps Material's own
focus line and gets no ring, which would run through its label. Before the app has started the page takes its
background from the Light/Dark choice in Settings, which `index.html` reads from `localStorage` (key `theme`).
The ingredients and steps of a recipe can be selected and copied, on Android too. A new fragment typed or pasted
into the address of an open page is followed (`hashchange`, `Address.wasmJs.kt`) as a tapped notification is on
Android: the screen opens, and what holds unsaved typing (the profile, the recipe editor) stays in front of it.
Every history entry the app makes carries its place in `history.state`, and its address is the screen that was
open on it. Back goes to the innermost back handler first (selecting, then the screen, "Discard your changes?"
asking again); when none answers, the screen of the entry stepped to opens, so Back after a typed address closes
what it opened (a recipe: to its tab) and then returns to the screen before it, and Forward opens the screens
again (a Forward never calls a back handler). Closing a screen from inside the app leaves its entry behind; it
then carries the address of the screen that replaced it. An entry without a place is an address the user made.
After a login round trip the entry before the app's is the login page: a guard entry is pushed on top, and a
Back onto the entry the login returned to stays on the screen (the page steps forward to the guard entry again, so
the Forward list is kept), and Back never reaches the login page. The screen a Back or Forward opened does not push an entry for
its back handlers, until the user's next click or key or another screen: no time limit decides it.
Without a login a typed address is not followed.
A second browser tab left on the login screen takes a login made in another tab (the `storage` event).
Home, End, Page Up and Page Down scroll the open list (`ScrollKeys.wasmJs.kt`; not in a text field). With the
crash page up the focus stays on its Reload button. The texts that say "tap" on a phone say "click" in the browser
(the `_BROWSER` messages, chosen with `Strings.onWeb`).
On a wide window a wheel turned over the empty margins scrolls the column (`MarginWheel.wasmJs.kt`: the event is
sent on from the middle of the column). The focus ring shows only while the keyboard was the last thing used (on Android the keys an on-screen keyboard sends, such as its delete, do not count: issue 342)
and stays inside the window. The recipe picker's search field has the focus when it opens, in the browser only. Tab out of a text field leaves
the page's focus on `body`, where Enter and Space reach nothing; it is given back to the app (`PageBehaviour.wasmJs.kt`).
`<html lang>` follows the app's language.
The few texts that say "phone" or "tap" have browser wording (`runsInBrowser()`): Settings' picture hint
and the store headers; others ("Tap Refresh") are not yet changed. Product pictures: AH and Lidl load, Jumbo's
do not (no CORS headers, see "Product pictures" in `docs/data-sources.md`): that is the letter tile, not a fault.
What no screen catches (an `error` or `unhandledrejection` on the page, which stops Compose's recomposer and
leaves a page that ignores every click) shows a plain message over the page with Reload and the technical text
behind "Details", in the app's language (`CrashPage.wasmJs.kt`); nothing is sent anywhere.
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

**The Recipes tab and the bank** (issues 205 and 206; the words are those of "Recipes: the bank, my recipes and the rotation"). The Recipes tab lists my recipes (`GET /api/recipes`). A choice above the list, All, Rotation or Not in rotation, each with the number of recipes it shows, narrows it (kept like the search text; "Meals only" and the search work within it). A row's switch puts the recipe in the rotation or takes it out without opening it, and fails with the server's sentence in the usual line at the bottom, the row going back. A long press on a row, or "Select recipes" in the menu (for whoever has no long press, as on the web), selects several: the bar at the bottom has "Select all" (what the choice shows), "Add to rotation", "Take out of rotation" and "Remove from my recipes". The last leaves out the recipes made in the app or taken from the site and changed in the app since, which have no bank to go back to (the server answers 409 for one), and the message says how many stayed; they are deleted from their own screen (menu → Delete). A refusal (a recipe planned this week or later, or the breakfast or snack routine) shows the server's sentence. A rotation recipe the planner will still skip says so on its row (no amounts or ingredients still to link: the existing marks; a sauce: "Not a meal"), and a recipe whose file left the cooking site is marked "No longer on the cooking site". With no recipes the tab offers "Browse the bank" and "New recipe". Sync stays in the Recipes tab's bar; when it found new recipes the snackbar says "3 new recipes in the bank" and its "Show" opens the bank.

The bank (`Route.Bank`, `#/recipes/bank`; `GET /api/recipes/bank`) is opened by "Browse the bank" on the Recipes tab, which says how many recipes wait there. It has the search (title and tags), a chip per section of the cooking site, rows as on the Recipes tab with an Add button, and selecting several for "Add to my recipes" (taking off the last tick leaves selecting, and Back leaves selecting before it leaves the screen, on Android and in the browser, where only the innermost back handler answers a back); the cooking-site line with the sync time ("This server's cooking site, synced …" when the bank is the server's) and the Sync button are here, as they are about the bank. Tapping the line opens the cooking-site dialog, which offers where the bank comes from: this server's cooking site (named by its address; only when the server offers it), a repository of my own (with the field for its address), or none; saving imports from the chosen site at once (issue 208). Against a server from before that, the dialog offers a repository of my own or none, which clears it. A row opens the recipe read-only: picture, nutrition, ingredients and steps, "Add to my recipes" and under it "Let the planner pick it" (on; after "Remove from my recipes" it shows what the recipe had, so that adding it again keeps that); no rating, rotation, "Plan as" or routine until it is added. After adding, a snackbar names it with "Undo" and, when ingredients of it are not linked to a food yet, how many, with "Link" to the linking screen. On one of my recipes the screen has the switch "In my rotation" above "Plan as" with a line under it; from the site a recipe not changed in the app is taken out with "Remove from my recipes" in the menu (a 409 says why not), one made or changed in the app with "Delete"; any of them is edited with the pencil (see "Writing a recipe in the app"). The recipe picker for a meal lists my recipes, those in the rotation first and the rest under their own heading (a recipe only in the bank is not offered; by hand any of mine can be planned), with "Browse the bank…" under the list; the Plan says when slots stay empty because the rotation is empty, with a link to the Recipes tab. Against a server from before the bank (no `GET /api/recipes/bank`: it answers 400 or 404, and the recipes carry no flags) every recipe is mine and in the rotation, "Browse the bank" is not offered, and a switch that cannot be changed fails like any other change.

Recipe photos (from the cooking site) show on the Recipes list, the recipe screen, Today and the Plan, for the recipes that have one; a recipe without one keeps the plain layout, with no placeholder. They come from our own server, so the same loader fetches them with the login token, and the "Show product pictures" setting does not affect them.

Screens keep their place. Opening a recipe (or the pantry, offers and so on) from a tab and going back leaves the tab as it was: the Plan's week and scroll, Today's day, the recipe list's scroll, search and choice, the bank's scroll and search; so does Android re-creating the activity (rotation, dark mode, language, font size), which also leaves an open Prep, "Remove the prep…" or "Add a prepped side…" dialog open with what was chosen in it. This is kept in memory and the saved instance state, not on disk: a cold start begins on Today, this week, at the top. A screen that comes back shows what it showed at once and fetches again behind it; a failed fetch keeps what is shown and says so in a line at the bottom with a Retry (a recipe the server says is gone shows the error instead). A new client (login, logout, another server) makes every open screen fetch again; logging out (or a login that ran out) also makes every screen forget its place, and the Plan a week a notification asked for, so whoever logs in next starts afresh on this week, at the top. When the app returns to the front (Android: started again; browser: the tab visible again) every screen in front fetches once, and Today and the Plan read the date again: they follow the new day or week when they were showing today or this week, and stay where the user stepped to otherwise. While the app is in front the clock is read every 30 seconds to notice midnight.

Cook mode: the **Cook** button on a recipe (one of mine or from the bank) opens big rows to tick the ingredients, with the amounts of the recipe screen (scaled to the servings to cook when it was opened from a meal, with its "As written" switch), and the steps one per card: the numbered list of the instructions, or, when there is none, every non-empty line (steps typed one per line in the editor). Tapping a row ticks it (dimmed, not moved); tapping a step marks it done and makes the next one the current, highlighted one. The screen stays on while it is open (Android: the window flag; browser: the Screen Wake Lock API where the browser has it, taken again when the tab shows). Ticks live in the saved instance state and go with the recipe screen; nothing is sent to the server. With the last step done, a meal that is still planned offers "Mark as eaten" (`PUT /api/plans/{week}/meals/{day}/{slot}/status`); Back leaves cook mode for the recipe.

**Notifications** (Android only, issue 225). Progress → Settings → Notifications has a switch per kind, kept on
the device; each kind is an Android notification channel of its own, so the system's settings list the same kinds:

| Kind | Posted when | Tap opens | Default |
|---|---|---|---|
| App update | A newer release exists (release builds only), once per version | The app, which offers the update | On |
| Offers ending | An offer at one of the account's stores ends today or tomorrow, for a row of this week's shopping list still to buy (not ticked, still needed by a meal, not covered by a known amount at home), or a buy-ahead or stock-up row not ticked or dismissed; once per day, one notification naming them all ("3 offers for your list end today") | Shopping | On |
| Plan next week | On Saturday and Sunday, while next week has no meals, whatever the offers ("Next week has no plan yet"; it works for every store, since only Jumbo lists next week's offers early); once per week | The planning screen of next week | On |
| Weigh-in | The newest weigh-in is 7 or more days old and there is a profile; once per 7 days, until one is entered | Progress | Off |
| Dinner today | Today has a planned dinner that is neither eaten nor skipped and something to cook: its main, or else a side's prepped batch made next to it that evening ("Tonight's side: …"); a dinner that makes a prepped batch says its servings are for tonight and the prep's later meals; once per day | The recipe, from that meal (its servings to cook; on a prep day for the prep's later meals) | Off |
| Mark what you ate | A meal of today is still planned; once per day | Today | Off |
| Prepped food to throw away | The morning after a prepped batch's last day (its own: in a box or vacuum sealed), when the batch was made (a meal of it eaten) and a share of it was skipped and not eaten elsewhere ("Throw away 1 portion of Chili con carne" / "It kept until Mon 23 Nov."), or a meal or side of it was never marked ("1 portion of Chili con carne was not marked: eaten, or to throw away?"); sides say "side". Several batches go into one notification; once per batch, and only on the day after its last day; a batch told later the same day is a notification of its own next to the first (an Android tag per batch) | Plan, where there is something to do: on the week of the first meal not marked, where it can be marked, else on that of the first skipped share left in the box, which may be the week after the batch was made (from a server before issue 263: the week the batch was made) | On (only an account that made a prep gets it) |
| Account requests | Someone asked for an account and waits for the owner (issue 314): once per request ("1 request for an account waits" / "New: zoe. Approve or reject in Settings."); a request decided and made again is told again (the check forgets a name once it sees a list without it, and the Requests card does so at once on a decision). Only the owner's account (`approvesRequests`, kept on the device from `/api/me` until a log-out) gets it, asks the server for it and shows the switch | Settings, with the Requests card at the top | On (only the owner's account gets it) |

The server says what is due, the app decides whether to tell: `GET /api/notifications` answers, for the calling
account and the server's today, the facts behind kinds 2 to 8 as data (`NotificationFacts`: names, dates, counts,
the recipe; for kind 7, `prepsPastLastDay`: per batch whose last day was yesterday, the recipe, the shares left and
the meals not marked; for kind 8, `accountRequests`, the user names waiting, empty for all but the owner's account),
and the app words them in its language (`Messages.kt`). One WorkManager job (`NotificationWorker`)
replaces the daily update check: every four hours on any network, as Android allows, it checks the release and,
when logged in, asks the server. Android decides when the periodic run happens, so it may never fall inside the two
hours of "Mark what you ate"; therefore every run also schedules a one-off run five minutes after the next window
opens (08:00, 15:00 or 20:00 by the device's clock), and the app's start does too, so each window gets a run
whatever the phase of the periodic one (`NotificationWindows`). The schedule outlives the app being swiped away and a reboot (WorkManager keeps it
in JobScheduler and sets it again at boot); a force stop (Settings → Apps → Force stop, or a phone maker's battery
saver that force-stops on a swipe) cancels it until the app is next opened, which schedules it again. What decides (the kind, its switch, what was told already per kind and day, week
or version, and the windows) is common code (`NotificationPlanner`): kinds 1 to 4, 7 and 8 from 08:00, dinner from 15:00,
"mark what you ate" from 20:00, and nothing between 22:00 and 08:00, by the device's clock; Android only schedules,
asks and posts. A logged-out app posts nothing but the update; the check never shows an error, a failure tells
nothing until the next run, and its renewals never end the session (see "Accounts and login"). Turning a switch on
asks for the permission where Android needs it; while a switch is on and Android does not let the app post, the
card says so with "Open Android settings", read again each time the app resumes. A tapped notification carries its
screen as a route (`Opening` in `Routes.kt`) with an id of its own, which `MainActivity` hands to the app once
(`NotificationInbox`): from a closed app, from an open one, and after Android killed the process with the task still
in Recents, when the tap arrives with the screens' saved state and opens its screen over them; the recipe editor and
the profile stay in front when they are open, so nothing typed is lost. "Plan next week" opens the planning screen of
next week (the `Opening` carries the week and `|planning`, `PlanScreen` starts on it), afresh each time it is tapped; one
posted by an app before that opens the Plan on that week.

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
Today (also in Dutch; with foods logged off the plan, also in Dutch and at font scale 2, and the log's food screen with a food picked and with a logged food whose values changed, each also in Dutch and at font scale 2), Plan (also in Dutch and with an empty rotation; both with an adjusted meal; with a prep and a share in the box, also in Dutch, and with that prep kept vacuum sealed, also in Dutch and at font scale 2), the Prep dialog (also in Dutch and at font scale 2, also eaten as a side), the Plan and Today with sides next to meals (also in Dutch), the Plan with one batch eaten as sides and as a meal (also in Dutch), the Prep dialog with meals picked both ways (also in Dutch and at font scale 2), "Add a prepped side…" with batches still to make (also in Dutch and at font scale 2), the question that removes a prep and a share in the box on its last day (both also in Dutch; the question also of a prep that was made, of meals and of sides, in both languages, and of a prep of meals and sides, made or not, in both languages), a week holding only a meal of last week's batch on the Plan and Shopping (Shopping also in Dutch), the recipe of a later prepped meal and cook mode on a prep day (both also in Dutch), the Adjust screen of a meal with its quick add-ons and a later meal left short (also in Dutch), Shopping (also in shopping mode, with an empty week, per store and at one store), Pantry, Recipes (on each choice, selecting several, and in Dutch), the recipe bank, a recipe from it and the cooking-site dialog's choices, recipe detail (with its optional toppings, also in Dutch; in the rotation, out of it, no longer on the site), the recipe editor (a recipe taken from the cooking site, also in Dutch and at font scale 2; its search with "New product" last, also in Dutch), the product form (also in Dutch and at font scale 2, and new with what keeps Save off; and its lookup at AH, what it found and the form filled with the one picked, each also in Dutch and at font scale 2), the recipe picker (also with standard meals first and the line on a prep still to make, in Dutch and at font scale 2), Today with a skipped lunch and its standard-meal chips (also in Dutch), the Plan's skip sheet and a recipe's standard-meal switch with what it made staples (both also in Dutch and at font scale 2), Offers, the price verdicts on Offers (early with one opened, and later, also in Dutch and at font scale 2 in both languages) and on Shopping (later, also in Dutch, at font scale 2 in both languages down to the advice, per store and in shopping mode in Dutch; the dialog of a tapped deal, early and later, also in Dutch and at font scale 2 in both languages), "Not this ingredient" on Offers and on a deal of Shopping with its snackbar (each also in Dutch and at font scale 2), Prices, Progress, the profile (in English and Dutch),
Settings and login screens (the login with a known server and with the address asked for, offering "Register a new account", and saying a request waits for approval, also in Dutch), the form that asks for an account (empty, after Send with every field wrong, refused by the server, and sent; each also in Dutch and at font scale 2), the owner's Requests card of Settings (also in Dutch and at font scale 2), the "Not this ingredient" list of Settings (also in Dutch and at font scale 2), the Notifications card (in English and Dutch, with notifications refused by Android, at font scale 2 in both languages, and the owner's with "Account requests"), the empty states of Recipes, Offers and Prices, and the whole app shell (with the bottom bar, logged in
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

The other host tests drive the screens against `FakeBackend` too. A screen fills in from several answers that
come in any order, so a test waits for the node it asserts on (or for a call the fake has answered), never for
time and never for a call it merely received. On the Plan that is `PlanActions.kt`: `waitForPlan` (with the
share-in-the-box card, the last answer that moves the days), `openSwapMenu`, which finds a meal's button in its
day's card instead of by a list index or by where it is drawn, and `waitUntilPlanIsIdle` after a change (issue 306).

Robolectric runs offline. It would fetch the `android-all-instrumented` jar of the SDK the tests run at
(`15-robolectric-14663667-2-i7` for SDK 35) from Maven Central when the first test starts, and a failed
fetch failed every Robolectric test of the run (issue 239). `gradle/robolectric.gradle.kts`, applied by the
root build to every module with Robolectric among its dependencies, resolves that jar as a normal Gradle
dependency (`robolectric-android-all` in `gradle/libs.versions.toml`, so Gradle's retries and the CI cache
apply and a failure says so at dependency resolution) and sets `robolectric.offline`. A new Robolectric
version, or a test at another SDK (`@Config(sdk = ...)`), needs the matching `android-all-instrumented`
version in the catalog; without it the test fails at once, naming the missing jar. See also [CI](#ci).

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

### Helpers for testing on the emulator

`scripts/emulator/` holds what the `emulator-tester` and `qa` agents use instead of writing their
own each run: `tap.sh` (taps the clickable node whose whole text matches, then prints the screen),
`texts.sh`, `shot.sh`, `seed-recipes.py` (recipes with amounts for a test account) and
`with-clock.sh` (server and dev login provider on another day). Each has a self-test that needs no
emulator; `scripts/emulator/README.md` says how to run them.

## CI

`.github/workflows/ci.yml` runs on every push to `main` and every pull request: the jobs `test`, `android`,
`screenshots`, `server-jar` and `scripts` side by side, then `image`. A run's result must depend on the commit
and on nothing else (issue 306). A failure that is not the commit's fault is recorded in that issue with its
run id and its cause is removed; a job is not re-run to get a green check.

What a run takes from outside the repository, and what holds each still:

- **Maven artifacts** (Google, Maven Central; Robolectric's jar also from Google's mirror of Central): resolved
  by Gradle and cached by `gradle/actions/setup-gradle`. Robolectric runs offline (see
  [Screenshot tests](#screenshot-tests)).
- **Node.js and yarn**: downloaded by the Kotlin plugin through Gradle, at versions the Kotlin version fixes.
- **npm packages for Wasm**, from the npm registry by yarn, each checked against the integrity hash of its lock
  entry. Two installs: the Kotlin plugin's tooling (webpack, Karma, Mocha; locked by the plugin's own
  `yarn.lock`) into `~/.kotlin/kotlin-npm-tooling`, and the project's packages (`kotlin-js-store/wasm/yarn.lock`)
  into `build/wasm`. A lock that would change fails `kotlinWasmStoreYarnLock`. They run one at a time (the
  build service `OneYarnAtATime` in the root build file): the plugin makes concurrent yarns wait for each
  other with `--mutex network`, and a yarn that waits over a minute quits, reporting success, without
  installing anything, which left `@js-joda/core` and `ws` out of CI builds whose tooling install was slow
  (issue 306). `verifyWasmNpmInstall` checks before webpack and before every Wasm browser test that each
  package of `kotlin-js-store/wasm/yarn.lock` is in `build/wasm/node_modules`, and fails with a message
  saying so. Another build on the same machine can still hold yarn's mutex that long; locally, delete
  `build/wasm` and build again. In CI the tooling and yarn's cache come from a cache entry keyed on
  `gradle/libs.versions.toml` and that lock, so the registry is needed only when one of them changes. Only
  the `test` job saves it, at its end, when everything before passed, its browser tests included, and
  `scripts/verify-npm-tree` finds every package the tooling's own `yarn.lock` requires at its locked version;
  `server-jar` only restores it. Both check a restored tree the same way, and one that is not whole is removed
  with a warning and installed afresh, so a bad entry cannot decide a run. After a key change neither job has
  a cache: each installs from the registry, as before, and the `test` job saves the new entry.
- **The browser** for the Wasm tests: Chrome for Testing at the version `CHROME_VERSION` in the `test` job.
  Its zip is downloaded only when the cache has no copy (a retry says "retrying the download", with the
  attempt), and checked against `CHROME_SHA256` on every run, cached or not, before it is unpacked and handed
  to Karma as `CHROME_BIN`, with its setuid sandbox helper (`CHROME_DEVEL_SANDBOX`: Ubuntu forbids the user
  namespaces of Chrome's other sandbox). A new version needs both values (the hash of `chrome-linux64.zip`).
  Locally Karma uses the Chrome it finds, or `CHROME_BIN`.
- **The JDK** is the latest Temurin 17 that `actions/setup-java` offers, and Gradle the wrapper's; each Gradle
  job prints them first (`./gradlew --version`), the `test` job also the browser's version.
- **No test reaches the network.** Tests that need a server start one on the loopback; the one that checks
  a failing fetch of the cooking site points at `127.0.0.1:1`, where nothing listens, and logs JGit's
  "connection failed" on purpose. CI checks every test it runs: after the tests pass, the `test`, `android` and
  `screenshots` jobs run them again offline (`./gradlew --stop`, then `scripts/without-network`, a network
  namespace with only the loopback), with nothing they need left to fetch: the tasks of `allTests`, `server`'s
  tests, `:androidApp:testDebugUnitTest` and `:composeApp:verifyScreenshots`.

A failed test prints its message and stack in the log (`exceptionFormat = FULL` for every test task in the
root build file): a browser test's failure used to show only its class, `Error` for a Mocha timeout.

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
| `MEALPLANNER_OIDC_GROUP` | unset | Only accounts whose access token lists this group (`groups` claim) may use the server; unset: every account of the issuer. Switch it on only as `docs/deploy.md`, "Who may use it", says, or people are locked out |
| `MEALPLANNER_REGISTRATION` | off | `on` lets people ask for an account (issue 314; `POST /api/registration`, the login screen's form from part 3's app release on), when the two settings below and `MEALPLANNER_OIDC_GROUP` are set too; otherwise it stays off and the server logs what is missing. See `docs/deploy.md`, "Registration" |
| `MEALPLANNER_REGISTRATION_HELPER_URL` | unset | The registration helper next to LLDAP (`scripts/registration-helper.py`), e.g. `http://mealplanner-registration:8091`; with the secret, the owner can decide on requests (also with registration off) |
| `MEALPLANNER_REGISTRATION_HELPER_SECRET` | unset | The secret the server and the helper share (the helper's `HELPER_SECRET`) |
| `MEALPLANNER_OWNER` | unset | Username that claims the data from before accounts, and with registration the only account that decides on requests; unset: the first login |
| `MEALPLANNER_ANDROID_CERTS` | unset | SHA-256 fingerprints of the app's signing certificates, for `assetlinks.json` |
| `MEALPLANNER_DATA_DIR` | the database's directory | Where per-account cooking repo clones go (and the server's, without `COOKING_REPO_PATH`) |
| `MEALPLANNER_IMAGE_DIR` | `recipe-images` in the database's directory | Where web-sized copies of the cooking sites' recipe pictures go, one folder per account. Keep it on the same persistent volume as the database: the database says which copies exist |
| `PORT` | `8080` | Listen port |
| `MEALPLANNER_DB_URL` | `jdbc:sqlite:.runtime/mealplanner.db` | SQLite database |
| `COOKING_REPO_URL` | unset | The server's cooking site: the recipe bank of every account that has not chosen another site or none in the app; cloned once and re-synced every six hours. Shown to accounts other than the owner without its user name or password |
| `COOKING_REPO_PATH` | `.runtime/cooking` with a URL | The server's checkout of that site; without `COOKING_REPO_URL` it is used as is (and synced only when asked) |
| `COOKING_REPO_SHARED` | on | Set to `0` (or `off`, `false`) to keep the server's cooking site to the owner, as before issue 208; other accounts then set a site of their own or have none |
| `COOKING_REPO_FETCH_INTERVAL_MINUTES` | `5` | The least time between two fetches of the server's cooking site, which any account's Sync asks for while the others' imports wait; a Sync within it imports the clone as it is. `0` fetches on every Sync. A value that is not a whole number of minutes stops the server at start |
| `ANTHROPIC_API_KEY` | unset | Enables recipe generation (`POST /api/recipes/generate`) with Claude; pay per use. Without it both generate routes answer 503 `{"error": "...", "reason": "generation_off"}`; the optional `reason` lets the app tell this from the 503 of a write that waited too long, and say "This server does not write recipes" (the setting's name stays behind Details) |
| `MEALPLANNER_MODEL` | `claude-opus-5-5` | Claude model for generation |
| `MEALPLANNER_FETCH_OFFERS` | on | Set to `off` to stop fetching store offers and regular prices in the background |
| `MEALPLANNER_OPENFOODFACTS_CONTACT` | unset | How Open Food Facts can reach whoever runs the server (an e-mail address or a web page), sent in the `User-Agent` its terms ask for (`MealPlanner/<version> (<contact>)`). Unset: a barcode AH cannot answer is not looked up at Open Food Facts, and the product form says so. See "An ingredient's nutrition and its portions" and `docs/data-sources.md` |
| `NEVO_CSV_PATH` | unset | NEVO main csv (`NEVO2025_v9.0.csv`). Imported on first start; `POST /api/foods/import` re-imports |

The NEVO dataset is not in this repository (see `docs/data-sources.md`). Keep it under `data/`,
which is ignored by git.

`shared`'s `CookingRepoParseTest` parses every page of the cooking repo. It looks in
`../cooking`, or wherever `-PcookingRepo=<path>` points, and is skipped when that is missing.

API overview: `/api/registration` and `/api/registrations` (asking for an account and the owner's decisions, see
"Accounts and login"), `/api/recipes` (my recipes; `POST` and `PUT /api/recipes/{id}` with the ingredients as `lines`,
each with the catalogue ingredient it is, or as the text of the import format, see "A recipe's lines and their
ingredients"; `PUT` and `DELETE` also for a recipe taken from the cooking site, the account's own copy; `POST /api/recipes/lines` looks lines up without saving;
`GET /api/recipes/bank` the bank, `PUT /api/recipes/{id}/added` with
`{"added": true, "inRotation": true}` and `PUT /api/recipes/{id}/rotation` with `{"inRotation": false}` answering the
recipe, `PUT /api/recipes/added` and `PUT /api/recipes/rotation` with `{"ids": [1, 2, 3], ...}` for many at once
answering my recipes; see "Recipes: the bank, my recipes and the rotation"), `/api/sync/cooking`, `/api/foods` (NEVO search), `/api/ingredients` (catalogue), `/api/products` (a product's label
looked up at AH and Open Food Facts, saving nothing; see "An ingredient's nutrition and its portions"),
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
meals, `DELETE meals/{day}/{slot}/side` for a side, and `GET /api/preps/spare/{date}`, see "Prepped meals"; `GET standard-meals`
and `PUT meals/{day}/{slot}/standard`, with `PUT /api/recipes/{id}/standard`, see "Standard meals"; `planning` with its
suggestions, picks, `not-this-week` and `done`, and `POST meals/{day}/DINNER/move`, see "Planning the week"), `/api/days/{date}` and `/api/log` (what was eaten; a food of the catalogue logged with `/api/log/foods`, see "Food eaten outside the plan")
`/api/weights` (weight log, trend and target suggestion; the newest weighing is the profile's weight, so the targets follow it:
a weighing for an earlier day leaves the profile alone, and removing the newest one gives the profile the weight of the newest
one left. Removing the only one keeps the profile's weight, as what it was before is not stored; plans already made keep their
portions), `/api/offers` (weekly store offers;
`POST /api/offers/refresh` fetches them now; today's offers, like the shopping list's, carry whether each of the
account's offers is a good deal by its own pack's price history (`Offer.verdict`), and `GET /api/offers/verdicts` gives
the verdicts with what they rest on, for reading; see `docs/data-sources.md`, "Is it a good deal?"; `GET`, `PUT` and
`DELETE /api/offers/exclusions` keep the offers the account said are not one of its ingredients, see "Which ingredient an
offer is for" there) and `/api/prices` (regular AH prices per ingredient, the
baseline for costing meals; `POST /api/prices/refresh` starts a lookup).

Ingredient texts are linked without manual work where the match is clear: through the default
catalogue in `server/src/main/resources/catalogue-seed.csv` (common ingredients, their NEVO food,
typical weights, and negligible items such as spices), and when a text is exactly the name of one
NEVO food. Only the rest shows up in the app's "Link ingredients" screen.

External data sources (store offers, nutrition data) are described in `docs/data-sources.md`.
