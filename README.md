# Meal planner

Plans a week of cheap, healthy, high-protein meals around calorie and protein targets,
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
  to (with "Change server", which opens the settings) and why the last session ended (logged out, or the
  login ran out or was ended). They open the login page by themselves once per logged-out state: on a
  fresh install (or the first start logged out after an update) with a known server. Once that page
  was opened, closed, or answered, after logging out and after an expired session, only the Log in
  button opens it (kept in storage, so neither Back, a rotation, the app returning to the front, a
  re-created screen nor a restart opens it again). Android opens the page in a Custom Tab over the app
  (`androidx.browser`), or in the browser when no browser offers Custom Tabs; never in a WebView, and the
  app never sees the password. The tab closes when the answer reaches the app.
- The answer goes to `LoginExchange` (`Login.kt`), which lives as long as the process (Android) or the
  page (web), not the screen: the token exchange finishes when Android re-creates the screen during it,
  and the screen that is there then takes the result. One answer is exchanged at most once.
- An access token the server refuses is renewed with the refresh token. When the provider refuses that
  (400 or 401, or there is no refresh token) the session ends on the login screen, saying why; a network
  error or a provider in trouble (5xx, 429, 404) ends nothing, and the next request tries again.
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
  failed) such a sync fails at once rather than trying again.
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
  (`DELETE /api/recipes/{id}`), and putting it back is refused. An added recipe whose file leaves the
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
the login screen. The tokens are kept in the app's private storage.

The app is in English and Dutch. Progress → Settings → Appearance → Language picks System (Dutch when the
device, or the browser for the web app, is set to a `nl` language, English otherwise), English or Nederlands;
it applies at once and is kept. Numbers, prices and dates follow the same choice (a decimal comma, `vr 13 nov`
in Dutch); with System they follow the device. The texts are in `composeApp/.../Messages.kt`, each with its
English and Dutch side by side, and the notification of an update in `androidApp/src/main/res/values*/strings.xml`.
Texts that come from the server (error messages, store deals, recipes) are shown as they are.

Offers and shopping-list rows show the product's picture from the store (Coil 3; docs/data-sources.md, "Product pictures"), with the first letter of the product as placeholder. Progress → Settings → Appearance → "Show product pictures" turns the loading off. The web app shows no pictures for Jumbo, which sends no CORS headers. The screenshot tests use a fake loader and never touch the network.

**The Recipes tab and the bank** (issues 205 and 206; the words are those of "Recipes: the bank, my recipes and the rotation"). The Recipes tab lists my recipes (`GET /api/recipes`). A choice above the list, All, Rotation or Not in rotation, each with the number of recipes it shows, narrows it (kept like the search text; "Meals only" and the search work within it). A row's switch puts the recipe in the rotation or takes it out without opening it, and fails with the server's sentence in the usual line at the bottom, the row going back. A long press on a row, or "Select recipes" in the menu (for whoever has no long press, as on the web), selects several: the bar at the bottom has "Select all" (what the choice shows), "Add to rotation", "Take out of rotation" and "Remove from my recipes". The last leaves out the recipes made in the app, which have no bank to go back to (the server answers 409 for one), and the message says how many stayed; they are deleted from their own screen (menu → Delete). A refusal (a recipe planned this week or later, or the breakfast or snack routine) shows the server's sentence. A rotation recipe the planner will still skip says so on its row (no amounts or ingredients still to link: the existing marks; a sauce: "Not a meal"), and a recipe whose file left the cooking site is marked "No longer on the cooking site". With no recipes the tab offers "Browse the bank" and "New recipe". Sync stays in the Recipes tab's bar; when it found new recipes the snackbar says "3 new recipes in the bank" and its "Show" opens the bank.

The bank (`Route.Bank`, `#/recipes/bank`; `GET /api/recipes/bank`) is opened by "Browse the bank" on the Recipes tab, which says how many recipes wait there. It has the search (title and tags), a chip per section of the cooking site, rows as on the Recipes tab with an Add button, and selecting several for "Add to my recipes"; the cooking-site line with the sync time ("This server's cooking site, synced …" when the bank is the server's) and the Sync button are here, as they are about the bank. Tapping the line opens the cooking-site dialog, which offers where the bank comes from: this server's cooking site (named by its address; only when the server offers it), a repository of my own (with the field for its address), or none; saving imports from the chosen site at once (issue 208). Against a server from before that, the dialog offers a repository of my own or none, which clears it. A row opens the recipe read-only: picture, nutrition, ingredients and steps, "Add to my recipes" and under it "Let the planner pick it" (on); no rating, rotation, "Plan as" or routine until it is added. After adding, a snackbar names it with "Undo" and, when ingredients of it are not linked to a food yet, how many, with "Link" to the linking screen. On one of my recipes the screen has the switch "In my rotation" above "Plan as" with a line under it; from the site a recipe is taken out with "Remove from my recipes" in the menu (a 409 says why not), one made in the app with "Delete". The recipe picker for a meal lists my recipes, those in the rotation first and the rest under their own heading (a recipe only in the bank is not offered; by hand any of mine can be planned), with "Browse the bank…" under the list; the Plan says when slots stay empty because the rotation is empty, with a link to the Recipes tab. Against a server from before the bank (no `GET /api/recipes/bank`: it answers 400 or 404, and the recipes carry no flags) every recipe is mine and in the rotation, "Browse the bank" is not offered, and a switch that cannot be changed fails like any other change.

Recipe photos (from the cooking site) show on the Recipes list, the recipe screen, Today and the Plan, for the recipes that have one; a recipe without one keeps the plain layout, with no placeholder. They come from our own server, so the same loader fetches them with the login token, and the "Show product pictures" setting does not affect them.

Screens keep their place. Opening a recipe (or the pantry, offers and so on) from a tab and going back leaves the tab as it was: the Plan's week and scroll, Today's day, the recipe list's scroll, search and choice, the bank's scroll and search; so does Android re-creating the activity (rotation, dark mode, language). This is kept in memory and the saved instance state, not on disk: a cold start begins on Today, this week, at the top. A screen that comes back shows what it showed at once and fetches again behind it; a failed fetch keeps what is shown and says so in a line at the bottom with a Retry (a recipe the server says is gone shows the error instead). A new client (login, logout, another server) makes every open screen fetch again. When the app returns to the front (Android: started again; browser: the tab visible again) every screen in front fetches once, and Today and the Plan read the date again: they follow the new day or week when they were showing today or this week, and stay where the user stepped to otherwise. While the app is in front the clock is read every 30 seconds to notice midnight.

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
they were at the latest release (v0.1.3 now; v0.1.2 differs from it only by additions), frozen in
`server/src/test/kotlin/dev/tobins/mealplanner/server/released/` with a header naming the tag, and
checks that no enum has a value the frozen one lacks. A pull request that breaks the rule fails `:server:test`.

The app's `ApiClient` turns an unknown enum value into the field's default, so a new value is safe for an
app from the first release after v0.1.3 on, for a field that has a default; a list of enums is not covered.
Give enum fields added from now on a default, or make them nullable. The rule above holds until then.

After an app release: `scripts/freeze-api.sh vX.Y.Z`, commit the result. The history of single fields is in
the test and in `git log`, not here.

## Screenshot tests

Layout is checked without an emulator: `ScreenshotTest` (`composeApp/src/androidHostTest`) renders the
Today, Plan (also with an empty rotation), Shopping (also in shopping mode, with an empty week, per store and at one store), Pantry, Recipes (on each choice, selecting several, and in Dutch), the recipe bank, a recipe from it and the cooking-site dialog's choices, recipe detail (in the rotation, out of it, no longer on the site), the recipe picker, Offers, Prices, Progress,
Settings and login screens (the login with a known server and with the address asked for), the empty states of Recipes, Offers and Prices, and the whole app shell (with the bottom bar, logged in
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
once a day in the background (a notification), the app looks at the latest GitHub release. If it
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
ingredient texts to catalogue ingredients), `/api/profile` (body data
and daily targets), `/api/shops` (the stores the account shops at; only their offers count for plans and costs),
`/api/shopping-advice` (whether the shopping list advises buying ahead and stocking up; see
`docs/data-sources.md`, "Buying ahead and stocking up"), `/api/pantry` (staples and on-hand items), `/api/plans/{weekStart}` (week plans: `generate`, `shopping`
with `shopping/dismissed/{group}` and the account's own items, `POST shopping/extras` with `{"name": "Coffee"}` and
`DELETE shopping/extras/{id}`, rows of the list with keys `x:<id>`; see `docs/data-sources.md`, "Own items on the shopping list";
`PUT shopping/again` and `POST shopping/use-up` with `{"key": "i12"}` tick a row that needs more again and put a bought item
no meal needs on hand, see "What the app shows of it" there,
and per meal `reroll`, `lock`, `status` and delete; `PUT meals/{day}/{slot}` with `{"recipeId": 12}` puts the account's recipe there,
locked, and a skipped meal leaves the day's and the week's totals; a meal that was eaten, or the leftover lunch of an eaten
dinner, keeps its recipe and its place: `reroll`, `PUT` and delete answer 409, `POST meals/{day}/{slot}/generate` 422, and
`generate` keeps it; see `docs/data-sources.md`, "Un-eating"), `/api/days/{date}` and `/api/log` (what was eaten)
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
