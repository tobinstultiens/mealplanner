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
MEALPLANNER_API_TOKEN=dev-token COOKING_REPO_PATH=../cooking \
  NEVO_CSV_PATH=data/nevo/NEVO2025_v9.0.csv \
  java -jar server/build/libs/server-all.jar
curl -X POST -H "Authorization: Bearer dev-token" localhost:8080/api/sync/cooking
curl -H "Authorization: Bearer dev-token" localhost:8080/api/recipes
```

## Web client

`composeApp` also builds for the browser (`./gradlew :composeApp:wasmJsBrowserDistribution`), and the
server jar includes that build: the server serves it at `/`. On the first visit it asks for the API
token, which the browser keeps in `localStorage`. `kotlin-js-store/` pins the JavaScript toolchain's
dependencies; commit changes to it. The web tests run in a headless Chrome.

## Android app

The server address and token are set in the app under Progress → Server, with a connection test.
Until then the build's defaults apply: `http://10.0.2.2:8080` (the host machine, seen from an emulator)
and `dev-token`, which `-PserverUrl=… -PapiToken=…` can change at build time. The token is kept in the
app's private storage, unencrypted.

Emulator on this machine (`avdmanager` stores devices under `~/.config/.android/avd`, where the
emulator does not look by default; the emulator window needs X11, so on Wayland it runs through XWayland):

```bash
QT_QPA_PLATFORM=xcb ANDROID_AVD_HOME=$HOME/.config/.android/avd \
  ~/Android/Sdk/emulator/emulator -avd mealplanner_test
./gradlew :androidApp:installDebug
```

## Database changes

The schema lives in `server/src/main/sqldelight/.../db/*.sq` and always describes the current
version. Every change also needs a migration file `N.sqm` (version N to N+1) next to it; the server
applies pending migrations on start. `MigrationTest` builds a version 1 database from
`server/src/test/resources/schema/v1.sql` and checks it ends up identical to a fresh one.

## Release build for your phone

Create a signing key once and keep it (and its passwords) outside the repo; losing it means
the app can no longer be updated in place:

```bash
keytool -genkeypair -keystore ~/keys/mealplanner.jks -alias mealplanner -keyalg RSA -keysize 4096 -validity 10000
```

Then add to `~/.gradle/gradle.properties`:

```properties
mealplanner.keystore=/home/<you>/keys/mealplanner.jks
mealplanner.keystorePassword=...
mealplanner.keyAlias=mealplanner
mealplanner.keyPassword=...
```

`./gradlew :androidApp:assembleRelease` then writes a signed
`androidApp/build/outputs/apk/release/androidApp-release.apk`; install it with
`adb install -r` or by copying it to the phone. The release has no token built in and opens on
the server settings, with `https://mealplanner.tstultiens.com` filled in (`-PreleaseServerUrl=`
changes that). It only talks HTTPS. A release and a debug build cannot be installed side by
side: they share the application id but not the signing key.

## Server configuration

| Variable | Default | Meaning |
|---|---|---|
| `MEALPLANNER_API_TOKEN` | required | Bearer token for every `/api` call |
| `PORT` | `8080` | Listen port |
| `MEALPLANNER_DB_URL` | `jdbc:sqlite:.runtime/mealplanner.db` | SQLite database |
| `COOKING_REPO_URL` | unset | Git URL of the cooking repo; the server clones it, re-syncs every six hours, and reads its `data/units.yaml` |
| `COOKING_REPO_PATH` | `.runtime/cooking` with a URL | Checkout to read recipes from; without `COOKING_REPO_URL` it is used as is |
| `ANTHROPIC_API_KEY` | unset | Enables recipe generation (`POST /api/recipes/generate`) with Claude; pay per use |
| `MEALPLANNER_MODEL` | `claude-opus-5-5` | Claude model for generation |
| `MEALPLANNER_FETCH_OFFERS` | on | Set to `off` to stop fetching store offers in the background |
| `NEVO_CSV_PATH` | unset | NEVO main csv (`NEVO2025_v9.0.csv`). Imported on first start; `POST /api/foods/import` re-imports |

The NEVO dataset is not in this repository (see `docs/data-sources.md`). Keep it under `data/`,
which is ignored by git.

`shared`'s `CookingRepoParseTest` parses every page of the cooking repo. It looks in
`../cooking`, or wherever `-PcookingRepo=<path>` points, and is skipped when that is missing.

API overview: `/api/recipes`, `/api/sync/cooking`, `/api/foods` (NEVO search), `/api/ingredients` (catalogue),
`/api/mappings` (links from recipe ingredient texts to catalogue ingredients), `/api/profile` (body data
and daily targets), `/api/pantry` (staples and on-hand items), `/api/plans/{weekStart}` (week plans: `generate`, `shopping`,
and per meal `reroll`, `lock`, `status` and delete), `/api/days/{date}` and `/api/log` (what was eaten)
`/api/weights` (weight log, trend and target suggestion) and `/api/offers` (weekly store offers;
`POST /api/offers/refresh` fetches them now).

Ingredient texts are linked without manual work where the match is clear: through the default
catalogue in `server/src/main/resources/catalogue-seed.csv` (common ingredients, their NEVO food,
typical weights, and negligible items such as spices), and when a text is exactly the name of one
NEVO food. Only the rest shows up in the app's "Link ingredients" screen.

External data sources (store offers, nutrition data) are described in `docs/data-sources.md`.
