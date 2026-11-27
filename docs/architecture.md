# How the code is ordered

The server and the app are ordered by feature, with the same names. Someone who looks for the shopping list's code finds a `shopping` folder in the server; what
a change to it touches is in one place, and what several features share is in a place of its own.

| Feature | What it holds |
|---|---|
| `plan` | The week's plan: planning, meals and their changes, preps and batches, standard meals, what is due today (the notifications) |
| `shopping` | The shopping list, its advice and shops, the sections of a shop |
| `recipes` | The bank and the account's own recipes, their pictures and toppings, the cooking site, recipe generation |
| `ingredients` | The account's ingredients, their foods (NEVO), the stock, products found by label or barcode |
| `prices` | Offers, regular prices, the price history, deal verdicts, the stores' sources |
| `tracking` | What was eaten, planned or not, and the progress against the targets |
| `accounts` | Login, registration, the profile (weight, targets), the account's settings |

`shared` is not ordered this way: it is pure rules and the API's types, frozen per release by
`ReleasedAppTest`.

## The server

`server/src/main/kotlin/dev/tobins/mealplanner/server/` has the root package and one package per
feature (`plan`, `shopping`, `recipes`, `ingredients`, `prices`, `tracking`, `accounts`). A feature's
routes, rules and database code are in its package: its `*Routes.kt`, its repositories, the classes with
its rules. Packages a feature had for one outside source go under it, and keep their name:
`prices.offers` (the stores' offers), `prices.history` (price history and verdicts), `shopping.sections`
(a shop's sections), `recipes.generation` (the recipe writer), `ingredients.products` (label and barcode
lookup). The `.sq` files and the generated `db` package stay where they are: the tables are shared, not
a feature's.

### What stays in the root package

Only what starts the server, or what several features share:

| File | Why it is in the root |
|---|---|
| `Application.kt` | Starts the server and puts the routes together; the login (`call.user`, `call.accountId`) |
| `Database.kt` | Opens the database and the write lock every feature's changes take |
| `UserScope.kt` | Builds one account's repositories, so it names every feature |
| `Errors.kt` | The errors every route maps to an answer |
| `BackgroundJobs.kt` | The scheduled loops that refresh the shared data and sync the cooking sites |
| `BuildInfo.kt` | What version and commit run |
| `ServerToday.kt` | The server's today (Amsterdam), for plan, shopping and tracking |
| `PickedFood.kt` | A food picked in an amount of its unit, which a plan slot (`plan`) and the food log (`tracking`) check and count alike |
| `PublicRoutes.kt` | What needs no login: the web client, the health checks, the login's address and the app links; it belongs to no one feature |

The root may use every feature, and every feature may use the root.

### Which feature may use which

`FeatureUsesTest` holds the list of allowed uses between feature packages and fails for a file that uses
a feature the list does not allow, and for an entry no file uses any more. The list records what the code
has; it is not a design. Some of it one would rather not have, as these pairs of features use each other:
`plan` and `shopping` (the plan feeds the list and reads its rows; the list reads the plan's week and shortfalls),
`plan` and `recipes` (`RecipeRepository` settles meals), `plan` and `tracking` (the due notifications),
`ingredients` and `recipes`, `ingredients` and `prices` (offers excluded for an ingredient),
`ingredients` and `shopping` (`ShoppingKey`), and `accounts` and `recipes` (syncing the cooking site).
Removing such a use is later work; adding one is a reason to ask where the thing belongs instead.

### Where a new file goes

- Into the package of the feature it is about. A file that several features use goes to the feature
  with most of its users; in the root only when it starts the server or is part of what every feature
  stands on (the database, the errors, the account's scope).
- A route goes next to the rules it calls, in `<Name>Routes.kt`; a route stays thin: it reads the request,
  calls one thing and answers.
- A test is in the package of what it tests, with the same name plus `Test`. Tests that cover every
  feature stay in the root test package: accounts kept apart (`AccountsTest`), the migrations
  (`MigrationTest` and the `*RealDataTest`s that migrate a copy of a real database), the released app
  (`released/`, which is only ever added to, never edited), the write lock and `ApplicationTest`.
- Test helpers go with the feature that uses them most; `TestTokens`, `RefusingWrites` and
  `TestRecipes` are used by every feature and stay in the root.
- New data for an installed app goes in new routes and types, not in changed ones (README, "API
  compatibility").

### Rules and SQL go in different classes

A feature's rules (what to do) and its database code (what to read and write) are to be in different classes:
the class with the rules is to call no query, and the class with the queries to hold no rule and take and give
values. The route is to call the rules, and the rules the store. A change that must be stored together is one
transaction that takes the write lock, as before. This is the target, not yet the case: the server moves to it
feature by feature (the plan #385, recipes #386, the shopping list #387). Until a feature has been
split, its repository still holds both, and a new class in it should not add to that.

## The app

`composeApp/src/commonMain/kotlin/dev/tobins/mealplanner/app/` has the root package, one package per
feature (`plan`, `shopping`, `recipes`, `ingredients`, `prices`, `tracking`, `accounts`) and `ui`, what every
screen uses to draw. A feature's screens, the classes they read (`ShoppingSession`, `RecipeDraft`,
`ProductDraft`) and its text helpers are in its package. The Android and web source sets (`androidMain`,
`wasmJsMain`) and the tests use the package of the code they belong to: an `actual` is in the package of its
`expect`, so the web `rememberBarcodeScanner` is `ingredients/BarcodeScanner.wasmJs.kt`, apart from
`Platform.wasmJs.kt`. `androidApp` imports from these packages. The Android namespace in
`composeApp/build.gradle.kts` stays `dev.tobins.mealplanner.app`.

| Package | What it holds |
|---|---|
| `ui` | Theme, icons, illustrations, the bars and chrome, loading and failure sentences, formatting and dates, the focus ring, unsaved changes, markdown |
| `plan` | The plan, planning and today screens, adjusting a meal, preps, standard meals, a slot's foods, meal changes, sides |
| `shopping` | Every `Shopping*` file: the list, its rows, ticks, cache, server calls, advice, shops and mode |
| `recipes` | The recipe bank, lists, picker, editor and draft, pictures, cook mode, generation, toppings |
| `ingredients` | The ingredient mapping, the pantry, products and their lookups and pictures, the barcode scanner, foods |
| `prices` | Offers, prices, deal verdicts, the queue of excluded offers, "not this ingredient" |
| `tracking` | The progress screen and the logged foods |
| `accounts` | Login, registration, account requests and shops, the profile, the server settings |

### What stays in the root package

Only what starts the app, or what several features share:

| File | Why it is in the root |
|---|---|
| `App.kt`, `Routes.kt` | Start the app and put the screens together, so they name every feature |
| `ApiClient.kt` | Every feature's server calls; to be split per feature (#384) |
| `Messages.kt`, `Strings.kt` | Every feature's texts, and the language; to be split per feature (#384) |
| `Platform.kt` | The `expect`s every screen needs (back, system bars, keyboard, locale, the browser or not); the `actual`s are in the root of `androidMain` and `wasmJsMain` |
| `SettingsStorage.kt`, `ScreenMemory.kt` | What the app keeps on the device, and where each screen was left |
| `Notifications.kt` | What is due and when to notify, which the Android app posts |
| `AppUpdates.kt`, `StayCurrent.kt` | Checking for a newer app; they belong to the app, not to a feature |

The root may use every feature, and every feature may use the root. Test helpers that tests of several
features share (`FakeBackend`, `FakeImageLoader`, `UnreachableServer`), the screenshot test, and tests of
root files stay in the root test package, like the web client's files in `wasmJsMain`.

### Which feature may use which

`FeatureUsesTest` (`composeApp/src/androidHostTest`) holds the allowed uses between feature packages
(`ui` counts as one) and reads the imports of the main source sets. It fails for a file that uses a feature
the list does not allow, for an entry no file uses any more, and for a file whose package is not its folder.
The list records what the code has; it is not a design. What looks wrong in it is recorded here, as later
work, not as a rule:

- `ui` uses `recipes` (`MealToCook` in `Formatting`) and `shopping` (`plainReason` in `Loading`, `shortName`
  in `Formatting`); the building blocks should use no feature.
- These pairs use each other: `plan` and `ingredients`, `plan` and `recipes`, `plan` and `tracking`,
  `recipes` and `ingredients`, `shopping` and `prices`, `shopping` and `ingredients`, `shopping` and
  `accounts`.
- `accounts` (`ServerSettingsScreen`) uses `prices`, `recipes` (`SwitchRow`) and `shopping`, and `prices`
  uses `ShoppingSession`, `ShoppingServer` and `ShoppingCache` of `shopping`.

Adding a use is a reason to ask where the thing belongs instead.

### Where a new file goes

- Into the package of the feature it is about; a file several features use goes to the feature with most
  of its users, and what every screen draws with goes to `ui`. In the root only when it starts the app or
  is shared by features as the table above shows.
- A test is in the package of what it tests, with the same name plus `Test`; an `actual` or a platform file
  is in the package of the code it belongs to.

### Not done yet

Targets of #381, step by step; the code does not do these yet:

- A feature's texts and server calls in its package, the shared ones in one place (#384). Until then
  `Messages.kt`, `Strings.kt` and `ApiClient.kt` are in the root, and many screens call the server themselves.
- A screen's loading and saving in a class of its own, tested without drawing, which the composable
  reads (the plan screen first, #388; the recipe page, #389). `ShoppingSession`, `RecipeDraft` and
  `ProductDraft` are such classes already.

## Why

The server's code was 50 files in one package, with a feature's rules, SQL and answers in one class.
Ordering by feature comes first because it changes no behaviour and every later step needs somewhere
to put its files.
