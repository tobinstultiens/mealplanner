# Meal planner

Plans a week of cheap, healthy, high-protein meals around calorie and protein targets,
using weekly offers from AH, Jumbo and Lidl and recipes from the cooking site.

| Module | Contents |
|---|---|
| `shared` | Domain model, API types, units, Hugo recipe parser. Kotlin Multiplatform (JVM + Android). |
| `server` | Ktor API with a SQLite database (SQLDelight). |
| `composeApp` | Compose Multiplatform UI (Android target for now). |
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
  java -jar server/build/libs/server-all.jar
curl -X POST -H "Authorization: Bearer dev-token" localhost:8080/api/sync/cooking
curl -H "Authorization: Bearer dev-token" localhost:8080/api/recipes
```

## Server configuration

| Variable | Default | Meaning |
|---|---|---|
| `MEALPLANNER_API_TOKEN` | required | Bearer token for every `/api` call |
| `PORT` | `8080` | Listen port |
| `MEALPLANNER_DB_URL` | `jdbc:sqlite:.runtime/mealplanner.db` | SQLite database |
| `COOKING_REPO_PATH` | unset | Local checkout of the cooking repo; unset disables the sync |

`shared`'s `CookingRepoParseTest` parses every page of the cooking repo. It looks in
`../cooking`, or wherever `-PcookingRepo=<path>` points, and is skipped when that is missing.

External data sources (store offers, nutrition data) are described in `docs/data-sources.md`.
