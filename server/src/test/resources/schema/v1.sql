-- Schema version 1, as created by the server before migrations existed (2026-11-13).
-- MigrationTest builds an old database from it and checks that it migrates to the current schema.
CREATE TABLE food (
    nevoCode INTEGER PRIMARY KEY,
    nameNl TEXT NOT NULL,
    nameEn TEXT NOT NULL,
    synonym TEXT,
    groupNl TEXT NOT NULL,
    groupEn TEXT NOT NULL,
    basis TEXT NOT NULL,               -- PER_100_G | PER_100_ML
    kcal REAL,
    protein REAL,
    fat REAL,
    saturatedFat REAL,
    carbs REAL,
    sugar REAL,
    fibre REAL,
    sodiumMg REAL
);
CREATE TABLE meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
CREATE TABLE ingredient (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE COLLATE NOCASE,
    nevoCode INTEGER,
    gramsPerPiece REAL,                -- weight of one piece/clove/can, for count lines
    gramsPerMl REAL,                   -- density, for volume lines
    category TEXT,
    pricePerKg REAL,
    isStaple INTEGER NOT NULL DEFAULT 0,
    negligible INTEGER NOT NULL DEFAULT 0   -- herbs, spices, leaveners: left out of nutrition
);
CREATE TABLE ingredientAlias (
    text TEXT PRIMARY KEY,
    ingredientId INTEGER NOT NULL REFERENCES ingredient(id) ON DELETE CASCADE
);
CREATE TABLE onHand (
    ingredientId INTEGER PRIMARY KEY REFERENCES ingredient(id) ON DELETE CASCADE,
    addedAt TEXT NOT NULL
);
CREATE TABLE planMeal (
    weekStart TEXT NOT NULL,           -- ISO date of the plan's first day
    day INTEGER NOT NULL,              -- 0-based offset from weekStart
    slot TEXT NOT NULL,                -- BREAKFAST | LUNCH | DINNER | SNACK
    recipeId INTEGER NOT NULL,
    title TEXT NOT NULL,
    portions REAL NOT NULL,
    kcal REAL NOT NULL,
    proteinG REAL NOT NULL,
    leftover INTEGER NOT NULL,
    locked INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'PLANNED',   -- PLANNED | EATEN | SKIPPED
    PRIMARY KEY (weekStart, day, slot)
);
CREATE TABLE shoppingCheck (
    weekStart TEXT NOT NULL,
    itemKey TEXT NOT NULL,
    PRIMARY KEY (weekStart, itemKey)
);
CREATE TABLE offer (
    store TEXT NOT NULL,
    title TEXT NOT NULL,
    deal TEXT,
    price REAL,
    originalPrice REAL,
    packSize TEXT,
    category TEXT,
    validFrom TEXT NOT NULL,
    validUntil TEXT NOT NULL,
    url TEXT
);
CREATE TABLE offerSource (
    store TEXT PRIMARY KEY,
    fetchedAt TEXT,
    error TEXT
);
CREATE TABLE recipe (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source TEXT NOT NULL,              -- COOKING_SITE | MANUAL | GENERATED
    sourcePath TEXT UNIQUE,            -- path under content/ for COOKING_SITE rows
    contentHash TEXT,
    section TEXT NOT NULL,
    title TEXT NOT NULL,
    tagsJson TEXT NOT NULL,
    servings INTEGER,
    instructions TEXT NOT NULL,
    hasIngredientsBlock INTEGER NOT NULL,
    problemsJson TEXT NOT NULL,
    kind TEXT NOT NULL,                -- MEAL | COMPONENT. App-owned: set on insert, kept on sync.
    mealSlotsJson TEXT,                -- App-owned: slots this recipe suits; NULL means the section's default
    removedAt TEXT                     -- set when the file disappears from the cooking repo
);
CREATE TABLE recipeIngredient (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    recipeId INTEGER NOT NULL REFERENCES recipe(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    amount REAL,
    unit TEXT,
    dimension TEXT,
    baseAmount REAL,
    item TEXT NOT NULL,
    groupName TEXT
);
CREATE TABLE foodLog (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    date TEXT NOT NULL,
    description TEXT NOT NULL,
    kcal REAL NOT NULL,
    proteinG REAL NOT NULL
);
CREATE TABLE weight (
    date TEXT PRIMARY KEY,
    kg REAL NOT NULL
);
CREATE INDEX offer_store ON offer(store);
CREATE INDEX recipeIngredient_recipe ON recipeIngredient(recipeId, position);
CREATE INDEX foodLog_date ON foodLog(date);
