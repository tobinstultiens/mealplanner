# How the code is ordered

The server is ordered by feature; the app is to be ordered with the same names (see "The app", which says
what is true now). Someone who looks for the shopping list's code finds a `shopping` folder in the server; what
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

Today the app's code (`composeApp/src/commonMain/kotlin/dev/tobins/mealplanner/`) is one package; its
screens call the server themselves, and its texts and server calls are two files every feature shares.
The agreed target (#381), step by step:

- Feature packages with the server's names, and a package (`ui`) for what every screen uses to draw;
  only what starts the app or is shared stays in the root, and a test holds the allowed uses between
  features (#383).
- A feature's texts and server calls in its package, the shared ones in one place (#384).
- A screen's loading and saving in a class of its own, tested without drawing, which the composable
  reads (the plan screen first, #388; the recipe page, #389). `ShoppingSession`, `RecipeDraft` and
  `ProductDraft` are such classes already.

This part is rewritten as each step lands.

## Why

The server's code was 50 files in one package, with a feature's rules, SQL and answers in one class.
Ordering by feature comes first because it changes no behaviour and every later step needs somewhere
to put its files.
