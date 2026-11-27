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
`plan` and `recipes` (the plan reads recipes as candidates; toppings and generation read the plan's meals, and `RecipeStore` reads `Plan.sq`
to see where a recipe is planned), `plan` and `tracking` (the due notifications),
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

A feature's rules (what to do) and its database code (what to read and write) are in different classes: the class
with the rules calls no query, and the class with the queries, the feature's store, holds no rule and takes and gives
values. The route calls the rules, and the rules the store. The store is built for one account and filters every
query by it, so no rule and no route passes an account's id. A change that must be stored together is one transaction
that takes the write lock: a rule opens it with its store's `writing` (`Database.kt`), which joins a transaction the
thread has open already, so a rule that calls another rule's change still makes one transaction of the two.

`QueryCallersTest` holds the list of files under `server/src/main` that may call a query (`db.planQueries`, ...) and
fails for a file outside it, and for an entry that calls none any more, so that the list only gets shorter. The plan
(#385), the recipes (#386) and the shopping list (#387) are split. Until a feature has been split its repository
still holds both, is on the list as it is, and a new class in it should not add to that.

The plan (`plan`), as its classes came out of `PlanRepository`:

| Class | What it does |
|---|---|
| `PlanStore` | The store: reads and writes the plan's tables (`Plan.sq`, and what the stock gave a meal) for one account, and opens its transactions |
| `WeekMeals` | Reads a week's meals with their batches and sides, as the shopping list and the stock count them, and stores a meal in a slot with what stays there (its side, a share in the box, its foods) |
| `KeptMeals` | Which meals stay as they are, and why: those planning keeps, a meal the stock went down for or that makes a batch, a side that stays, the slots no standard meal can take |
| `WeekPlans` | Plans a week with the planner (Generate, and Done on the planning screen), and what the planner is given for a week |
| `MealChanges` | Changes one meal: chosen, rerolled, a dinner moved to another day with its leftovers, a standard meal in its place, locked, removed |
| `FoodChanges` | Puts foods in a meal's slot, changes them and takes them out |
| `Eating` | Cooks or eats a meal: eaten, skipped or planned again, what was in it, and what either takes from the stock or gives back |
| `WeekShortfalls` | What the adjusted meals of a week leave later meals short of, for the shopping list and the Adjust screen's warnings |
| `WeekCosts` | What a week costs: its prices and offers, what each meal and the week cost, the day's sums, the rests of fresh packs |
| `WeekView` | The week as the Plan shows it (`WeekPlan`), which every change answers |
| `PlanScope` | Builds the store and these classes for one account, as they call each other; `UserScope` builds it |

The recipes (`recipes`), as their classes came out of `RecipeRepository`:

| Class | What it does |
|---|---|
| `RecipeStore` | The store: reads and writes the recipes, their lines and the routines for one account, where a recipe is planned (which keeps it from being deleted or put back), and opens their transactions |
| `RecipeView` | The recipes as the app shows them: my recipes, the bank, one recipe with its lines, toppings, nutrition and this week's cost, which every change answers |
| `MyRecipes` | Which recipes are mine: adds them from the bank and puts them back, the rotation, the routines, deleting one; a recipe of another account or only in the bank is refused here (404, 409) |
| `RecipeEdits` | Writes a recipe: a new one, an edit, the lines of one being edited as they would be saved, its rating, kind and slots, how long it keeps, a standard meal or not |
| `RecipeCandidates` | The recipes as the plan, the planner and the shopping list read them: candidates to plan, the standard meals, their lines with their ingredients, fibre and cost |
| `BankSync` | Brings the account's bank in line with its cooking site as `CookingSync` read it: new, changed and removed recipes |
| `RecipeScope` | Builds the store and these classes for one account, as they call each other; `UserScope` builds it |

`RecipeToppings`, `RecipePictures`, `CookingSync` and `GenerationService` were classes of their own before, and are
not split yet.

The shopping list (`shopping`), as its classes came out of `ShoppingListRepository`:

| Class | What it does |
|---|---|
| `ShoppingStore` | The store: reads and writes a week's ticks, the dismissed advice groups among them, and the own items (`Shopping.sq`) for one account, and opens their transactions |
| `ShoppingListView` | The week's list as the app shows it (`ShoppingList`), which every change answers: its rows with their ticks, stock and offers, what was bought that no meal needs any more, and the advice next to it |
| `WeekNeeds` | What a week's list is to hold, row by row in its shop section: what the planned meals need, all of them and those still to cook, and the own items |
| `ListAdvice` | The advice next to the list, unless switched off or dismissed: which offers of the account's stores it goes by, stocking up, and buying ahead |
| `BuyingAhead` | What to buy this week for next week's meals: those planned, or a provisional pick when next week has no plan yet |
| `Ticking` | Ticks a row off or back on with what it remembers and puts on hand, ticks a row again, uses up a bought item no meal needs any more, hides an advice group |
| `OwnItems` | Adds an own item, ticks it off as bought in a week, deletes it |
| `ShoppingScope` | Builds the store and these classes for one account, as they call each other; `UserScope` builds it |

`ShoppingNeeds`, `ShoppingAdvisor`, `ShoppingKey` and `ShopSections` were classes of their own before and call no
query; `ShopsRepository` and `ShoppingAdviceRepository` were too, and are not split yet.

A store is a class, not an interface: no test needs another store. The tests run on an in-memory SQLite database, and
a write that fails halfway is made by the driver (`RefusingWrites`, as `MealChangesTest`, `RecipeEditsTest` and
`TickingTest` do). An
interface goes in front of a store only when a test needs one.

A new query of a split feature goes in its store; a new rule goes in the class of what a person does with it.

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
| `ApiClient.kt`, `SharedApiCalls.kt` | The client (the address, the login, the errors and the retries every call shares), and the server calls that more than one feature makes |
| `Messages.kt`, `AppMessages.kt`, `Strings.kt` | The `Msg` and `Plural` interfaces and the list of every enum of texts; the texts only the app's shell shows (tabs, notifications, updates); the language and how a text is filled in |
| `Platform.kt` | The `expect`s every screen needs (back, system bars, keyboard, locale, the browser or not); the `actual`s are in the root of `androidMain` and `wasmJsMain` |
| `SettingsStorage.kt`, `ScreenMemory.kt` | What the app keeps on the device, and where each screen was left |
| `Notifications.kt` | What is due and when to notify, which the Android app posts |
| `AppUpdates.kt`, `StayCurrent.kt` | Checking for a newer app; they belong to the app, not to a feature |

The root may use every feature, and every feature may use the root. Test helpers that tests of several
features share (`FakeBackend`, `FakeImageLoader`, `UnreachableServer`), the screenshot test, and tests of
root files stay in the root test package, like the web client's files in `wasmJsMain`. `FakeBackend` keeps
its state, its server and its routing table there; each feature's sample data and routes are extension
functions of it in `<feature>/Fake<Name>.kt` of the test source set.

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

### Where a new text and a new server call go

- A text goes into the enum of the feature that shows it: `PlanMsg` in `plan/PlanMessages.kt`, `ShoppingMsg`
  in `shopping/ShoppingMessages.kt`, and so on, with a count-dependent one in the feature's `*Plural`. Give
  it English and Dutch side by side and the same `{0}` placeholders in both; `StringsTest` checks every enum
  that `messageTables` and `pluralTables` (`Messages.kt`) list, and `MessageTablesTest` fails for an enum
  that is not in them. A text that two features show goes to `CommonMsg` in `ui/CommonMessages.kt`; one
  that only the app's shell shows (tabs, notifications, updates) to `AppMsg`. A feature never imports
  another feature's enum: that would be a use `FeatureUsesTest` does not allow.
- A server call is an extension function of `ApiClient` in `<feature>/<Name>Calls.kt`, using `http` (and
  `unlessUnknown` for a route an older server lacks); the client itself stays in `ApiClient.kt`. A call that
  two features make goes to `SharedApiCalls.kt` in the root. A new file of texts or calls of a feature that
  passes about 400 lines is split by screen.

### Where a new file goes

- Into the package of the feature it is about; a file several features use goes to the feature with most
  of its users, and what every screen draws with goes to `ui`. In the root only when it starts the app or
  is shared by features as the table above shows.
- A test is in the package of what it tests, with the same name plus `Test`; an `actual` or a platform file
  is in the package of the code it belongs to.

### A screen's state class

What a screen loads, what it sends to the server and what it shows while that runs or fails is in a class of
its own, not in the composable: the composable reads the class and tells it what the person did. The class
is tested without drawing, with a server that answers what the test says. `ShoppingSession`, `RecipeDraft`,
`ProductDraft` and, the example to follow, `PlanState` (`plan/PlanState.kt`) are such classes.

How the plan screen's is made:

- **The server it asks** is an interface of its own, `PlanServer` (`plan/PlanServer.kt`), with one method per
  call the screen makes and `ApiClient.planServer()` to make it from the real client. A test gives the class a
  fake that answers, waits or fails as the test says; `FakeBackend` stays for the tests that draw. The methods
  throw like the calls they stand for, and the class turns that into what the screen shows (`load`).
- **A plain class, not a `ViewModel`.** The web client has no `ViewModel`, the screen keeps nothing that must
  outlive it (what it loaded stays in `ScreenMemory`, which the app already keeps for every screen), and a
  plain class is made and tested like the others above. It runs in the screen's own coroutine scope, so
  leaving the screen ends what it was doing, as it always did. The screen makes it with `rememberSaveable`:
  `PlanState.Place` (the week shown) is what a re-created activity keeps, the rest is loaded again.
- **What it holds** is what the screen draws and nothing of Compose's drawing: the week as `LoadState` (so
  that it is kept in `ScreenMemory` and a failed refresh keeps what was shown), the shares in the box, the
  standard meals, my recipes, whether a change is running (`busy`) and the offer after a skipped meal. Which
  dialog is open and what is chosen in it stays in the composable: it is what the person is doing, not what
  was loaded.
- **What it is told.** The composable calls a function for what the person did (`reroll`, `move`,
  `toggleLock`, `makePrep`, ...) and runs the loads in `LaunchedEffect`s keyed as before (`refresh`,
  `refreshSpares`, ...): when to load stays with the composable, how stays with the class. A sentence for the
  person goes out through a function the screen gives (`say`, the snackbar); a dialog that needs to close
  when something went through is given a callback to do it.
- **The check.** `ScreensCallNoServerTest` lists the files of each screen that has a state class and fails
  when a composable in them calls the server (a call on `api`, or a function of the feature's `*Calls.kt`).
  A screen that gets a state class is added to its list.

A screen's state class is added to a feature the same way: the calls it needs in a `<Screen>Server` interface
next to the state, the state in `<Screen>State.kt`, its test in `commonTest` with a fake server.

The recipe page's (`recipes/RecipeState.kt`, `RecipeServer.kt`) follows it, with these differences: nothing is
kept for a re-created activity (the page held nothing the plan's `Place` is for), so it is made with plain
`remember`; the snackbars go through a small `RecipeVoice` (say a sentence, announce an adding with "Undo", offer
"Undo"), because two of them have an action to wait for; and what stays with the composable and is handed in as a
callback (`onPutBack`, `onEaten`, `onDeleted`) is what the page keeps for itself across rotation (cook mode, the
choice to let the planner pick an added recipe). The toppings' own composable keeps its busy state and gets the
save as a function, so it calls no server either; the ingredient picker it opens still does (see below).

### Not done yet

Targets of #381, step by step; the code does not do these yet:

- Many screens still call the server themselves, among them the screens the plan screen opens (planning,
  another dinner, a slot's foods, picking a recipe).
- The recipe page opens the ingredient picker (`plan/IngredientPicker`) for a topping, which searches the server itself.

## Why

The server's code was 50 files in one package, with a feature's rules, SQL and answers in one class.
Ordering by feature comes first because it changes no behaviour and every later step needs somewhere
to put its files.
