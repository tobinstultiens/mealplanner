# External data sources

Findings from the Phase 0 spikes, checked on 2026-11-12. None of the store sources is an
official API, so each can change without notice.

## Store offers

| Store | Source | What it gives | Notes |
|---|---|---|---|
| AH | App API on `api.ah.nl` | Product, bonus mechanism ("2 voor 2.49"), price, price before bonus, bonus dates, category | Works anonymously |
| Jumbo | Offers page `www.jumbo.com/aanbiedingen/nu` | Promotion title, subtitle, tag text ("voor 9,99", "2e gratis"), start and end date | Promotion level, no original price |
| Lidl | Offers page `www.lidl.nl/c/aanbiedingen/…` | Product, price, pack size, in-store dates, category | Old price only on some items |

How the server uses them (`server/src/main/kotlin/.../offers/`): every six hours it fetches each
store whose last successful fetch is over a day old; `POST /api/offers/refresh` fetches all at once.
A store that fails keeps its previous offers and reports the error under `/api/offers`. When that error cannot be recorded either (the database stayed
busy), it is only logged and the next store is fetched all the same; `/health/data` shows the store as
stale once its last success is old enough. A round that fails altogether is logged and the loop runs again at its next time (every six hours); so do the price lookup and the cooking sync, each on its own schedule. Each successful
fetch is also kept in the price history (see "Price history" below). Parsers are
tested against trimmed real responses in `server/src/test/resources/offers/`; the live check is
`LIVE_OFFERS=1 ./gradlew :server:test --tests '*OfferSourcesTest.liveStores'`.
On 2026-11-13 it returned 111 AH, 137 Jumbo and 39 Lidl offers.

### A changed page cannot wipe the stored offers
Before a store's offers are replaced, the result is compared with what is stored. It is refused, and
the stored offers kept, when it holds no offers, or fewer than a third of the stored offers that are
still valid today (both numbers are in the message). A store whose stored offers have all ended takes
any result. A refusal records the message as the store's error under `/api/offers`, writes an error to
the server log (so the nightly refresh does not fail silently) and nothing to the price history; the
other stores are fetched and stored as usual. A third is judgement: a quiet week is rarely that small, a
changed page usually yields none or a handful. A truly small week after a big one is refused once and
accepted the next time, once the big week's offers have ended: that is the price of the guard.

### AH
1. `POST /mobile-auth/v1/auth/token/anonymous` with body `{"clientId":"appie"}` returns an
   access token valid for about a week.
2. `GET /mobile-services/product/search/v2?query=&filters=bonus%3Dtrue&size=…&page=…` with
   `Authorization: Bearer <token>`, `x-application: AHWEBSHOP` and an `Appie/…` user agent.
3. The week's sections come from `GET /mobile-services/bonuspage/v3/metadata`: under
   `periods[0].tabs[].urlMetadataList[]`, the entries with `bonusType` `NATIONAL` (food categories
   only; drugstore, household and partner shops are skipped). Each `url` is fetched under
   `/mobile-services/` and holds `bonusGroupOrProducts`: a `product`, or a `bonusGroup` covering
   several products (no single price).
4. Relevant product fields: `title`, `salesUnitSize`, `isBonus`, `bonusMechanism`,
   `currentPrice`, `priceBeforeBonus`, `bonusStartDate`, `bonusEndDate`, `mainCategory`, `subCategory`,
   `images` (see "Product pictures" below).

The product search with `filters=bonus%3Dtrue` also works but is capped at 10,000 results.
"Volume voordeel" entries (3–5% off multi-packs) are flagged as bonus and should be filtered out.

### Jumbo
The old `mobileapi.jumbo.com` endpoints return 404. The offers page embeds its data in
`<script id="__NUXT_DATA__">`: a flat JSON array in which objects refer to other entries by
index. Promotion objects have `title`, `subtitle`, `tags[].text`, `start.iso`, `end.iso`, `url`,
`image`.
A GraphQL endpoint (`www.jumbo.com/api/graphql`) answers too, but its queries are not explored yet.

### Lidl
Each product tile on the offers page carries a `data-grid-data` attribute holding HTML-escaped
JSON: `fullTitle`, `price.price`, `price.oldPrice`, `price.packaging.text`, `storeStartDate`,
`storeEndDate` (Unix seconds), `category`, `keyfacts.wonCategoryPrimary`, `image`. The page holds only
the week's tiles (47 on 2026-11-13, 46 on 2026-11-15) and makes no paging request. Only the
tile categories `Food` and `F+V` are used. Tiles without `storeStartDate` are regular-priced items
with a Lidl Plus app discount, and are skipped. The offers page address
(`/c/aanbiedingen/a10008785`) may change; the fetch reports an error when it finds no tiles.

The page can also silently stop showing new offers while its tiles still parse. After each
fetch the server logs a warning (`LidlGuard`) when every Lidl offer has already ended, or when
it is Friday or later and no Lidl offer runs into next week. The store keeps its offers. The
same text is sent as `OfferSourceStatus.warning` and the Offers screen shows it under the
store's source line, in the warning colour, with the text behind "Details". Apps up to v0.1.1
reject that field; the first app release after v0.1.1 reads it.

Looking for offers beyond this page (2026-11-13 and 2026-11-15, plain page fetches only) found
none, so the page is the whole list as far as can be found:
- `/q/api/search` and `/q/api/category` return no items for the offers category.
- The page already holds both of the week's batches: on 2026-11-15, 45 food tiles (39 `Food`,
  6 `F+V`), all dated, 32 from Monday 16 and 13 from Thursday 19, all until Sunday 22 November.
  Offers starting later in the week are therefore not missed.
- `/c/store/aanbiedingen/a10008785` is the same page (46 tiles, same products). Since 2026-11-15 the
  old address redirects there (301); the fetch follows the redirect.
- Next week's offers are not published yet: the folder `hah-wk48-2026` does not exist, and the
  current folder `hah-wk47-2026` (`/l/folders/…/view/flyer/page/1`) is a script-rendered viewer
  without tiles.
- Other pages linked from the offers page: `/c/pre-black-week/s10080606` has 48 tiles, none with a
  food category or store dates (webshop non-food); the supermarket assortment pages
  (`assortiment-supermarkt-in-groente-en-fruit`, `-in-vlees`) hold no offer tiles (0 and 2
  non-food). The navigation links only to non-food campaign pages.
Next week's offers should appear on the same page once they start, so the fetch picks them up
without changes.

### How a deal text is read

One reader serves the shopping list, the plan's cost and the price history: `Discounts.kind` in
`shared/.../pricing/`. `Discounts.factor` (the list) and `DealTerms` (the history) both start from its result, so
the same text gives the same share in both. Only text after a "·" ("· Alleen online") is left out; the rest must
be one of these forms as a whole, or the kind is `UNKNOWN`, kept with its text and no share ("2 stapelen tot
35%", "voor 1,00 per hamburger", "Gratis beanie bij 2 stuks"): rather no saving than one that may be wrong.

Around a form, wordings that change nothing for the customer are read: a trailing "!" and a leading "Nu"
("Nu 25% korting!"), "op alles" after a percentage ("25% korting op alles"), the spelled "Tweede halve prijs" and
"Derde halve prijs", and "bij N stuks": after a percentage it is the packs the deal wants ("25% korting bij 2
stuks" wants 2); after a deal that already wants N packs ("1+1 gratis bij 2 stuks") it is read as that deal. After
a price or an amount off ("voor 1,99 bij 2 stuks") it may mean each pack or all of them, so the text stays
`UNKNOWN`, as does every "Tot 25% korting" (not a firm percentage). The packs a deal wants (`packsNeeded`) come
from the same reading; only an unknown deal that says "N stapelen" still wants N.
A number the reader cannot use makes the text `UNKNOWN` rather than an error: a count of 0 (in every position) or
above 1000 packs, or a price or weight too long to be a number. A pack size below a milligram or above a million
tonnes, or with a count that is no Int, gives no size, and a price per kg, piece or litre that is not finite or not
above 0 is left out of the history.

Each refresh logs a warning per store when some of its deal texts are not read: how many of its offers, and the
most common texts, so that a new wording of a store is noticed.

| `dealKind` | Store text | Numbers | Share |
|---|---|---|---|
| `LOWER_PRICE` | "voor 1,99", "14,99 · OP=OP", no text with a price (Lidl) | `dealEuros`: the price | against the regular price of a pack |
| `PERCENT_OFF` | "25% korting" | `dealPercent` | 1 - percent (25% korting 0.75) |
| `AMOUNT_OFF` | "1,00 korting", "2 euro korting" | `dealEuros`: off one pack | against the regular price of a pack |
| `N_FOR_PRICE` | "2 voor 3,00" | `dealPacks`, `dealEuros`: for all of them | against the regular price of a pack |
| `X_PLUS_Y_FREE` | "1+1 gratis", "2 + 1 gratis" | `dealPacks` (3 for 2+1), `dealFree` (1) | paid / (paid + free) (1+1 0.50, 2+1 0.67) |
| `NTH_HALF_PRICE` | "2e halve prijs" | `dealPacks` (2) | (n - 0.5) / n (2e 0.75, 3e 0.83) |
| `PRICE_PER_KG` | "1,00 per kilo", "1,09 per 500 gram", "100 gram voor 1.59" | `dealEuros`: per kg | none (a price per kg) |

### Which ingredient an offer is for

Each account's offers are matched to its own ingredients by the words of their titles (`OfferMatcher`), with the
ingredient's name and aliases as terms; the longest term that occurs wins.

- A term matches a whole word or its Dutch plural (-s, -en, -n, "banaan" to "bananen", "kip" to "kippen", and -zen
  after two vowels: "saus" to "sauzen", "kaas" to "kazen"). A Dutch three-letter word that doubles its last letter
  ("kip", "vis", "bes", "rog", "pit") takes no plain -s, so "Kips" (a liver sausage brand) is not chicken; other
  short terms do, so an English alias "fig", "rib" or "nut" matches "figs", "ribs" and "nuts". "Kips" was the only
  such word in the offers of 16 to 20 November 2026, and it is the first word of its titles like any brand, so a
  rule by capital letters could not tell it apart.
- A term of five letters or more also matches the end of a compound ("makreelfilet" ends with "filet"), unless the
  front names a food of its own: a term of another of the account's ingredients that shares no name word with this
  one ("kaneelbroodjes" is no white bread roll when cinnamon is "kaneel"; but "tomatenketchup" is "Tomato ketchup"),
  or an animal of game or a less common meat ("hertenbiefstuk" is no beef steak).
- A known name of another food that ends in an ingredient's word ("zoete aardappel") is not that ingredient, unless
  the account knows an ingredient by that name.
- Words after "met", "with" or "in" are a side ingredient; prepared products (a word ending in "saus" or "sauzen",
  "mix", "sap", "smaak", "oven", "worst", "paté", "schnitzel" and so on) don't count unless the ingredient is itself
  one ("sojasaus"); and some store categories (ready meals, cold cuts, snacks, drinks) are never an ingredient.

**"Not this ingredient" (issue 316).** Rules will never be complete ("Hela curry" is a sauce, "Bolletje" a brand), so
an account can say an offer is not one of its ingredients: `PUT /api/offers/exclusions` with the store, the offer's
title and the ingredient. It is kept per account (`offerExclusion`) by the store, the title's key and the
ingredient. The key is the title's words in lower case (runs of letters and digits), without its pack sizes (a
number, or numbers in a row such as "125-150", followed by a unit: g, gr, gram, kg, kilo, ml, cl, l, liter,
literflessen, stuk, stuks, pack, packs) and without a first "Alle" (`OfferExclusion.titleKey`). AH writes the pack
into the title and Jumbo and Lidl beside it, so the same product at the same store has the same key in a later
week and another pack: "AH Blauwe bessen 150 gram" and "AH Blauwe bessen 300 gram" are both "ah blauwe bessen", and
Jumbo's "Conference peren", "Croky" and "Lipton" kept their titles from the week of 11 November to that of 18
November under other packs ("Los per kilo" then "Bak 1 kilo"; bags of 150 or 200 gram then of 75 to 160; bottles of
1.5 litre then of 500 ml). A number without a unit stays ("Heineken 0.0" is not "Heineken").

Wherever the account's offers are matched, the pair is left out, and the offer is matched as if the ingredient
were not in the catalogue (it may still be another ingredient whose term it has): `/api/offers`, the shopping list's
offers (the deal chips and the savings counted from them), buying ahead and stocking up, the plan's cost with
offers, the planning suggestions' "on offer" reason, the offers named to recipe generation, and the verdicts
("Is it a good deal?", whose history rows are matched with the same exclusions). A row ticked before keeps the
saving it stored, as one whose offer ended does: it says what the shopping saved then; a tick made after it saves
nothing from that offer. The shared offers, regular prices and price history are not touched: the history's
`ingredient` and the regular-price lookup match with every account's names and no account's exclusions.
`GET /api/offers/exclusions` lists the account's exclusions, the latest first, with the title as first excluded,
the key, the store, the ingredient's name and when; `DELETE /api/offers/exclusions?store=…&title=…&ingredientId=…`
takes one back by any title of the same key. Both writes are idempotent (an exclusion sent again keeps the first
title and time; taking back one that is gone changes nothing), so a phone may send them twice or late. An
ingredient that is not the account's is a 404; a title without words or over 300 characters a 400.

## Regular prices (AH)

Offers only say how much is taken off; to know what a meal costs, and so which offers make it
cheap, the server keeps a regular price per kg for each ingredient (`PriceRepository`, `prices/`).

- Once a week it searches AH's product search (`/mobile-services/product/search/v2?query=…`, same
  anonymous token as the offers) for up to four of the ingredient's names, Dutch ones first.
  `POST /api/prices/refresh` starts a lookup now; it takes a few minutes and `GET /api/prices`
  shows the result with the product each price came from.
- A product counts for an ingredient when the offer matcher would give it that ingredient (see "Which ingredient an
  offer is for"), with every account's names for it and no account's exclusions: the prices are shared.
- `priceBeforeBonus` is the regular price, also while the product is on offer. Per kg comes from
  the pack size (pieces need the ingredient's weight per piece), or else from the unit price text.
- The product must sit in an AH aisle where the ingredient's kind of food is sold: its NEVO food
  group maps to AH main categories (`AhAisles`; vegetables to "Groente, aardappelen", canned goods
  to "Pasta, rijst, wereldkeuken", sugar to "Koffie, thee"). This keeps out flavoured products
  from other aisles such as "croissants cinnamon" or cumin cheese.
- A name for which nothing is found loses its stored price (with its shop section and picture) only
  when the lookup still found a price for at least half of the names that had one. Otherwise nothing is
  deleted, a warning with the numbers goes to the log and the lookup is not recorded as done, so the next
  round tries again: AH answering in a changed shape finds nothing for every name. With fewer than five
  stored prices the rule is not applied.
- Of the matching products, only those in the category holding most of them count (stray matches
  such as "croissants cinnamon" sit elsewhere); of those, the middle of the three cheapest per kg is used.
- A price set by hand on the ingredient (`pricePerKg`) wins. Ingredients without a price are
  costed at the median price per kg and named in the recipe's cost.

### Packs: the rest of an opened pack (issue 222)

The pack size and pack price of the product a regular price comes from (`IngredientPrice.packSize`,
`packPrice`) also tell the planner which recipes go well together: a fresh ingredient (one that does not keep
until next week, by its shop section or by hand) is bought in a pack, and what a meal leaves of it is
thrown away if no meal uses it in the days after. `PriceRepository.freshPacks` gives each such ingredient its
pack, read by `PackSize.grams` (an ingredient whose size is no weight, or with a price by hand, has none); a
recipe's `PlanCandidate.freshUses` lists the grams a serving takes of each, leaving out ingredients on hand.
`WeekPlanner` replays the week's meals so far (`OpenPacks`): the rest of a pack counts for a meal on the day of the
meal that opened it and up to three days after, the same for every ingredient; a pack of the regular AH
price is used, an offer's different pack is ignored. The term has two sides, in euros of pack price
(`PackWeights`, a request can set others): a reward of 0.75 for what the candidate's portion takes from open
packs (so a dinner that uses up half a tub of crème fraîche the Monday dinner opened is preferred on Wednesday),
and a penalty of 0.2 for the rest it leaves by opening a new pack for what the open ones cannot give (a dinner that
opens a 500 g pack for 10 g is wary: 9.8 euros of rest, 1.96 off its score). The planner stays greedy: Monday is
not chosen because Thursday could use its tub, only the penalty guesses that the rest may not be finished.
Leftover lunches open nothing and the priorities do not change the term.

The Plan tells what the week leaves over (issue 244, `WeekPlan.rest`, `OpenPacks.smallRestsOf`): the same replay over
the meals as the shopping list buys them (a prepped batch is one purchase of all its portions, sides included, on the
day it is made; a skipped meal buys nothing), and the rest of every pack closed at the end. A rest is told only when it
is under half of the pack it is left of, as bought: each pack opened is judged by itself, so an ingredient whose week
opens two packs adds up the rests of those under half (a pack opened on Monday and another on Saturday, each left
with a quarter, tell half a pack's worth; one left with 70 percent is not counted). Pieces and litres are grams of the
pack by then (`PackSize.grams` with the weight per piece or per ml), so the half is of weight. A rest worth under
5 cents is left out. The app shows the euros of all rests and names the three largest ("and 2 more").

A pack written in pieces ("3 stuks", "per stuk") is counted through the ingredient's weight per piece
(`gramsPerPiece`), which the issue had left out of scope; 10 of the 43 covered ingredients depend on it (bell pepper,
cucumber, egg, lime, mango, onion, red onion, romaine lettuce, baguette, bread roll). Three more (yoghurt, milk,
whipping cream) are packs in litres, counted at the ingredient's density or 1 g/ml.

Measured on the cooking repo's recipes with AH's prices of 2026-11-19 (`PackRestMeasurementTest`: 50 seeds, 40
recipes in the rotation, 53 fresh ingredients with a pack; protein and fibre are the mean share of the daily target):

| reuse | waste | rest EUR/week | calorie error % | protein % | fibre % |
|---|---|---|---|---|---|
| 0 | 0 (without the term) | 25.19 | 5.14 | 89.82 | 40.02 |
| 0.75 | 0 (reward only) | 22.77 | 5.54 | 90.04 | 39.87 |
| 0.75 | 0.1 | 23.42 | 4.89 | 88.52 | 39.15 |
| **0.75** | **0.2 (shipped)** | **20.75** | **4.89** | **87.97** | **38.14** |
| 0.75 | 0.25 | 16.59 | 5.77 | 87.08 | 36.47 |
| 0.75 | 0.5 | 13.59 | 7.19 | 87.01 | 35.72 |
| 1.5 | 0.2 | 19.52 | 5.49 | 87.79 | 37.74 |

The reward alone cuts the rest by 2.42 euros a week; with the penalty at 0.2 by 4.44 (18 %), with the calorie
error 0.25 points lower than without the term. The price is in the nutrition: the penalty steers away from recipes
with fresh vegetables, so protein falls 1.9 points and fibre 1.9 points of the daily target. A stronger penalty
lowers the rest more and the calories and fibre much more (at 0.5 the error is 7.19 %), and the rest has a floor of
13.59 euros that the breakfast and snack routines and the few recipes that must open a pack account for.
Before the term was built, the fresh ingredients the 80 recipes of
the cooking repo use were counted: 43 of 49 have a pack in grams, the other six (chili pepper, egg yolk, lettuce,
spring onion, sweetcorn, Swiss chard) have no price, or no pack size.

With each price the lookup keeps the product's `mainCategory` and `subCategory`; they also give the
ingredient's shop section (below).

An offer becomes a share of the regular price (`Discounts.factor`), from the deal text read as in "How a deal
text is read" (above): "25% korting" pays 0.75, "1+1 gratis" 0.5, "2e halve prijs" 0.75; stated prices ("2 voor
3,99", "voor 4,49", Lidl's plain prices) are compared with the regular price of a pack of the offer's size. A text
without a share of its own (a price per weight, an unknown form) is shown without a saving unless the offer's own
price can be compared. Online-only deals are ignored; the store's price against its price before wins when both
are given; a result outside 0.2 to 0.97 is dropped. The
best offer per ingredient in the plan's week applies: the one with the lowest share (it saves most on
the amount needed, whatever the pack size); offers without a share come after those, one that states a
price before one with only a deal text, then the server's order (`shopping/BestOffer.kt`, one rule
for the server and the app). The planner then prefers meals whose
calories cost less than the median (`WeekPlanner.savings`, twice as strongly under the profile's Price priority), so offers pull those meals in.

Only offers at the stores the account shops at count ("Stores I shop at", an account setting:
`GET`/`PUT /api/shops`; an account that never chose shops at every store). The plan's and the
recipes' costs, the planner's preference and the offers named to Claude when writing a recipe all
leave the other stores out. The shopping list still carries every store's offers, and the app
leaves out those of stores not in use, with their pictures, in every view of the list. The stores can be
chosen on the per-store list and under Settings, Shopping (one shared setting); logging out forgets the
stores shown until the next account's answer arrives.

The shopping list gives each item's regular price for the amount needed (`ShoppingItem.regularPrice`)
and each offer's share (`Offer.paidShare`, as above), so the app can show what a store's offers save:
the sum of regular price times the share taken off, for the items whose best offer is there. An item whose offers have no share (the deal can't be valued)
keeps its badge but is listed under "Any store".
The server sends every offer that overlaps the plan week, so the app judges each against today
(the stores' weeks differ): an offer that has ended is ignored everywhere on the list; one that
starts later keeps its badge with the day (`Lidl €1.79 / from Thu`) and stays under its store,
but is left out of the saving, and an offer valid today always wins over one that starts later. Its
full deal text (a tap on the badge) names both days: `Lidl: €1.79 from Thu until Sun`.
A row with an offer also says what to pick up (`PackSize.toBuy`, in the app only): the packs to take
(`2 × 700 g`), from the offer's pack size and the weight (or, for packs in pieces, the pieces) the
recipes need. When the deal wants more packs than that (`1+1 gratis` for one pack: `1 × 500 g · deal
needs 2`) its saving is left out of the sums. Nothing is said when the pack cannot be read, or the item
has no weighed amount. Such a deal gives way to another offer valid today, at a store in use, that saves something and
fits (issue 105, the owner's "Switch it on" on #118; `PREFER_FITTING_OFFER` in `shared/.../shopping/RowSaving.kt`, a
constant shared by the app and the server): 450 g of chicken goes by AH's 300 g at 25 % off (`2 × 300 g`), not by
Jumbo's "1+1 gratis" on 500 g, for its badge, its store group, its saving and the saving its tick keeps. When none fits,
the deal that saves most stays, with `deal needs 2` and no saving. The app up to v0.1.9 still counts by the rule from
before, which kept the deep deal; a row it ticks gets the saving the server works out by this rule.
Regular prices are AH's, so for Jumbo and Lidl that saving is an estimate against AH's price, and the
app says so.

## Price history

So that a later step can tell a good deal from an ordinary one ("this 2+1 pays 67 %, and it is 1+1 about
every four weeks"), the server keeps what the stores offered and what the regular prices were, over time
(`PriceHistory.sq`, `PriceHistoryRepository`, `history/PriceChanges.kt`). It is shared by every account like
the offers and prices, written only by the server's own refreshes, and read only for the verdicts on offers (below,
"Is it a good deal?").
History starts on the first refresh after version 13 of the database; nothing from before is kept.

**Offers** (`offerHistory`). A product is its store, its title and its pack size, each normalised (lower
case, runs of spaces as one, a decimal comma as a point), so "VOOR 2.99" and "Voor 2.99" are the same; another
pack size is another product. A row is one period in which the product was offered the same way:
- A refresh that sees a product again with the same price, price before and deal text (normalised) moves
  the row's `lastSeen` to its own time (and widens `validFrom`/`validUntil`); nothing new is written. A run of
  identical nights is one row with `firstSeen` and `lastSeen`.
- A changed price, price before or deal starts a new row; the old one keeps its `lastSeen`.
- A product missing from a refresh gets nothing: its row keeps its `lastSeen`. When it comes back, even
  unchanged, it starts a new row, so the gap is the time between one row's `lastSeen` and the next one's
  `firstSeen`. A row continues only if the last recording of its store saw it.
- The last recording is the store's latest `lastSeen`. A store that fails records nothing, and so does a
  fetch that succeeds with no offers (or only ended ones): neither ends any offer, and the next good refresh
  continues the rows as if the night in between had not happened. A refresh no later than the last one
  recorded (two at once) records nothing.
- Offers that have already ended are not recorded, so a page that still shows them does not stretch a
  period. The same offer listed twice in one refresh (Jumbo lists this week's and next week's) is one
  sighting valid over both weeks; the same product offered two ways at once (Jumbo's mandarins for 1,29 this
  week and 1,89 next week) is two rows, each with its own dates.
- The catalogue ingredient the product matches is kept by name (`ingredient`), from every account's
  ingredients and aliases, as the regular prices are kept by name. It is matched when a row starts; a row
  that matched nothing is matched again on each refresh, so it gets an ingredient an account adds later.
- The store's own category (`category`, as the store gave it: AH and Lidl give one, Jumbo none), the
  product's address at the store (`url`, kept only when it is an `https` address) and a price per litre
  (`eurosPerLitre`, below) are kept with each row since version 15 of the database; rows recorded before
  have them empty until a refresh continues them. Neither the category nor the address is part of what a
  row is: a refresh that sees the same offer with another category or address continues the row, which
  keeps the ones it began with and only fills in what it lacked. So a change in only these never writes a
  row, and an unchanged offer is still one row however many nights it is seen.
- Each refresh of a store that records anything also leaves a row in `offerRecording`: the store, its
  time (`seenAt`, the `lastSeen` it gave the rows it saw) and the number of offers it saw. A refresh that
  failed, saw no running offers or came after a later one leaves none, so "not on offer for three weeks"
  can be told from "the store could not be read for three weeks". Recordings are kept as long as the rows.
- Recording happens once the store's offers are stored, in a transaction of its own: the history never
  costs a store its offers. If recording fails, the error is logged and nothing of that refresh is
  written, so the next good refresh continues the rows as if the night had not happened (as for a store
  that fails). An offer whose dates are no ISO dates (`2026-11-16`) is left out of the history and logged,
  and the store's other offers are recorded; the offer itself is stored with the others as always.

With each row the deal is kept as the store wrote it and as a kind that can be compared (`dealKind` and its numbers,
read as in "How a deal text is read" above; `Discounts.kind`, `DealTerms` in `shared/.../pricing/`).

`dealPacks` is always the packs to buy together for the deal (`Discounts.packsNeeded`). `paidShare` is the
share of the regular price paid when the deal is used in full, from the offer alone: the price paid per pack
against the store's price before where it gives one, otherwise the deal's own share (the "Share" column above). Deals stated as a price have no share without a price before; a later
step can compare them with the regular price history. `DealTerms` also gives what one pack costs (`eurosPerPack`), which
is not stored: the verdicts read each row again (below). `eurosPerKg` and `eurosPerPiece` are the price at that
share, where the pack size (or else the title) gives a weight or a count; volumes are no weight without a
density, so a pack written as a volume ("1,5 l", "6 x 330 ml", "75 cl") gets `eurosPerLitre` instead, from
the first of the pack size and the title that gives a size at all. A "2 voor 7,00" of 6 x 330 ml is 7,00 for
both packs' 3,96 litres. Deals by weight (`PRICE_PER_KG`) get no price per litre. Online-only deals keep
their share; their text says where they apply.

**Packs of several.** `PackSize` reads a pack of several as the count times the size of each: "4-pack à 1 liter"
is 4 l, "2 verpakkingen à 200 gram" 400 g, "Tray met 24 blikken à 33cl" 7,92 l (as "6 x 330 ml" always was), "3x 16
stuks" 48 pieces and "Verpakking met 8 pakjes à 5 stuks" 40. Not a multiple: a text with "alle" ("Alle flessen à
1,5 liter": any bottle of that size), a range ("2 verpakkingen à 100-175 gram", no size to multiply) and a
weight after the count ("500 gram à 2 stuks"). The history's pack key is the normalised text, so a changed reading
moves no row to another product; the verdict reads rows again from their text, and the `eurosPerKg`,
`eurosPerPiece` and `eurosPerLitre` stored with a row before 20 November 2026 are no input to anything.

A pack text longer than 200 characters, or with more than 9 digits in a row, gives no size. This is checked before
any regex runs, so no platform's regex engine can throw or stall on a store's text.

**Whose ingredient.** The `ingredient` of a row is the match at the time the row began, by every account's
names together, and it is not changed afterwards: a match that was wrong stays, and an ingredient renamed
since does not find its rows by name. An account's live offers are matched differently, by its own names
and aliases, with the store's category to keep out cold cuts, ready meals and drinks (`OfferMatcher`).
Reading the history for an account should therefore match the rows again, by `title` and `category`, with
that account's own matcher, and not trust `ingredient`; rows from before version 15 have no category to
check against.

Reading the history (the queries `selectOfferPeriods` and `selectOfferRecordings`,
`PriceHistoryRepository.offerPeriods` and `recordings`): the rows of some products (by normalised title) of a
store seen between two instants (begun by the end, last seen from the start on), and the recordings of a store
between two instants, both on an index. The verdicts below read them.

In the offers of 2026-11-15 (426: 111 AH, 270 Jumbo, 45 Lidl) all but 7 were recognised. Unknown were
"2 stapelen tot 35%", "2 stapelen voor 3.99" and "3 stapelen voor 7.99" (AH), "2 gratis blikjes bij 1
multipack", "Gratis beanie bij 2 stuks" and "voor 1,00 per hamburger" (Jumbo), and one Lidl offer with
neither a deal nor a price. Since then "N stapelen voor X" is read as "N voor X" (stack N packs for X, a
multi-price), and any "N stapelen ..." makes the deal want N packs (`packsNeeded`); "N stapelen tot 35%"
stays of unknown kind with no share, as "up to" 35 % is not a firm percentage.

**Regular prices** (`regularPriceHistory`). Per ingredient name and store, like `ingredientPrice`: a new row
only when the product (title, pack size) or its price (per pack, per kg) changed. A lookup that finds the same
again moves `lastSeen`. A name the lookup found nothing for keeps its row as it was, and when it is found again
a new row starts (the gap shows); a name whose search failed, or that a stopped lookup did not reach, keeps its
row open, as it keeps its price.

**Kept for two years.** Rows whose `lastSeen` is more than two years old are removed during the refreshes
(offers and their recordings on each store's refresh, regular prices at the end of a lookup).

A copy of a real database can be checked with
`PRICE_HISTORY_DB=/path/to/copy.db ./gradlew :server:test --tests '*PriceHistoryRealDataTest*' --rerun -i`:
it copies the file again and migrates it, comparing row counts, the history's rows and
`integrity_check`/`foreign_key_check` around each migration of the history the copy is from before (to
versions 13 and 15), then replays its stored offers through six refreshes and prints the history's rows, how
many have a category, an address and a price per litre, the recordings and timings.

### Is it a good deal?

For each of an account's offers, the server says whether it is a good deal compared with the same product's own
history at the same store (`history/DealVerdict.kt`, `history/OfferPeriods.kt`, `history/RegularPrices.kt`,
`DealVerdictRepository`). The app gets them with the offers (issue 118, step c): each judged offer of `GET /api/offers`
for today and of every answer that carries the week's shopping list has its verdict as `Offer.verdict` (`PriceVerdict`,
an optional field of the shared API, below "In the app"), so the list the app keeps for the store, where it may have no
network, keeps its verdicts too. `GET /api/offers/verdicts` gives them with everything they rest on and an English
summary, for reading; its response type is the server's own (`DealVerdicts`), not one of the shared API types, so it may
still change. Both answer for the calling account only, read and write nothing else, and need a login like every
`/api/` route. A failure while judging leaves the offers and the list without verdicts rather than failing them.

**Which offers, and whose.** The offers valid today at the stores the account shops at that match one of its own
ingredients, as `/api/offers` matches them. The history is shared, but its rows are matched again with the account's
own names and aliases, its exclusions ("Not this ingredient") and the row's store category (`OfferMatcher`), never by the `ingredient` stored with them,
which was matched against every account's names: a row the account's matcher does not give the same ingredient (a
week the store filed the product under cold cuts) is left out. Regular prices are read by the account's own
ingredient names.

**This product.** The rows of the same store and the same normalised title, over the last year (52 weeks: a seasonal
product's offers of last season count, older prices say little about today's), every pack size and every deal.

**Each pack against itself** (the owner's choice, issue 197). The verdict compares today's offer only with offers of
its own pack (the pack size as the store writes it, normalised; `''` when it gives none): a 500 g offer with earlier
500 g offers, never with the kilo pack's. Comparing per kg across packs called a 1 kg pack's ordinary deal "a real
deal" against dearer-per-kg 500 g offers, and the 500 g pack's usual deal "worse than usual", which overstates it for
"buy now or wait": the kilo comes back at that price. That another pack is cheaper per kg or per litre is said apart
from the verdict ("Cheaper per kg in another pack now (1 kg): …", or "Cheapest per kg in another pack (1 kg): …",
below).

**Two products under one title** (issue 197). Jumbo writes some offers under a brand title ("Lipton", "Spa Touch",
"Croky", "Duyvis", "Coca-Cola"), several at once, each a different product or group of products: its subtitle, kept as
the pack size, says which ("Tray 24x250ml", "Alle flesjes à 500 ml, per flesje", "Alle flessen à 1,5 liter"). Judged
pack against pack, they are never compared with one another, so none is "cheaper another way" than another. In the
offers of 16 to 20 November 2026 every title Jumbo used twice at once had two different packs. The product's address
(`url`) cannot tell them apart: Jumbo's is the promotion's page (`/aanbiedingen/lipton/3022372`), whose number is new
with each promotion, also for the same product the next week ("Mandarijnen, Net 1 kilo": 3023718 one week, 3024032
the next). Telling products apart by it would make every week of a Jumbo product a product of its own. So they are told
apart by the pack (the owner's decision, issue 197); two products under one title and one pack would still share a
history.

**Markup in store texts** (`offers/StoreText.kt`, issue 320). The parsers take the markup out of an offer's title and pack
text where they read it from the store: a `<br>` line break becomes " · ", other tags go, character references such as
`&amp;` are decoded. Every screen and the shopping list get the clean text. Rows of the price history recorded before
keep the store's text; their keys are read through the same cleaning (`ProductKey.recorded`), so their periods continue.
A recorded title is found the same way: `selectOfferPeriods` looks rows up by the cleaned title, and
`selectMarkedOfferPeriods` adds the store's rows whose recorded title holds a `&` or `<` and is the cleaned title once
cleaned, so a title recorded with a tag, a `<br>` or a character reference still gives its product's earlier periods to
the verdict. No row is changed.

**Which texts are one pack** (`history/Packs.kt`, issue 118). A pack written another way one week would start a history
of its own and stay "not enough history" for longer, so when the history is read (never when it is written: the rows
keep the store's text) the pack texts of one product are compared without:
- markup and Jumbo's footnotes, the lines of a subtitle after a line break that start with "M.u.v." (what the deal leaves
  out), "De korting is al verwerkt …" or "Maximaal N … per bestelling": "Alle flesjes à 500 ml, per flesje<br />M.u.v.
  Gekoeld", with or without empty lines after it, is "Alle flesjes à 500 ml, per flesje". Other lines stay: "Land van
  herkomst: Frankrijk" and "…: Italië" tell two wines apart;
- the weight of an approximate pack ("ca. 450 g", "Schaal circa 700 gram"): those alike but for a weight within 5 % of the
  lightest of them (`SAME_PACK_SHARE`) are one pack, known by the lightest's text. A pack a tenth heavier is another one,
  and so is a weight the store states exactly.

Measured on the offers stored on 15, 16, 18 and 20 November 2026 (420 products of a store and title seen on more than one
night): 5 had another set of pack texts from one night to the next, and none was one pack written another way. Four were
Jumbo brand titles of which one product ended (Conference peren "Los per kilo", Croky, Duyvis and Lipton's 1,5 litre),
and one was Lidl's smoothie, whose "500 ml" was gone from the second night on; a pack without a size is not taken for
one with it. AH gives no pack size: its sizes are in the title ("AH Mager rundergehakt 300 gram"), and no AH title of
those nights differed from another only in its size. Should an AH weight drift ("ca. 450 g" one week, "ca. 460 g" the
next), that title is another product, which these rules do not join. Left alone too, as they can mean another product:
", per fles" or not, and "Los, per kilo" against "Los, per stuk".

**Which rows are one offer, and which offers are earlier** (`joinOffers`, `judge`). Rows of one product (store and
title) are one offer when they have the same pack and the same deal as the customer sees it (the deal text, or for an
offer without one, as at Lidl, its price) on store dates that share a day or run on (the later one starts by the day
after the earlier one ends). Nothing else decides it. In particular, the nights the product was missing from the page
never do: the store parsers skip an item with a missing field without a word. Nights the store was not read do not
decide it either. The same deal again the next week is the offer going on, not a second one: an offer that continues
says nothing about how often the product is on offer, and a product that is always on offer has its own rule (rule 4).

"Now" is what the store shows today (`TodaysPage`, issue 197): an offer is *shown today* when the store's latest
recording saw it, or when it is among the store's stored offers (`/api/offers`), with the same pack and deal on dates
that share a day with it. A store's dates alone do not say an offer is still there: Lidl changes a price mid-week on
the same dates, and a deal dated over months can be withdrawn long before its end. An offer is *alongside* when its
store dates include today, it is not today's offer, and it is shown today: it has not ended, so it is no earlier offer,
and it is named in the summary; of today's pack, it counts against today's (rules 2 and 6). An offer is *earlier* when
it is not today's offer and it ended: its store dates ended before today, or it ended early, dated over today but no
longer shown. Only earlier offers of today's pack are compared with. Each row of this table is a test in
`history/OfferPeriodsTest.kt` or `history/VerdictPropertiesTest.kt`. The latter checks the properties behind the
rules on 600 made-up histories (fixed seeds, under two seconds; packs by weight, by pieces and without a size), which
include offers dated over months and from before the first read, other deals running alongside for weeks, looked-up
regular prices, two prices without deal text on the same dates (Lidl's change mid-week), offers the store stopped
showing before their dates ended, and another deal of today's pack shown alongside that pays what today's does:
- the verdict and every number in it stay the same when nights are dropped from the sightings (today's page stays the
  store's);
- today's value is what `DealTerms.of` gives for today's offer as shown today (but for the share against a reported
  raise);
- the earlier offers are exactly the other offers of today's pack that ended, by their dates or early, none counted
  twice; the offers alongside are exactly the other offers, of any pack, dated over today and shown today, valued as
  shown today;
- a comparison rests on at least 2 earlier offers;
- a raise is reported exactly when a price before of the same pack in the 30 days before was lower;
- a real deal is never clearly dearer, in its measure, than any other offer of its pack dated up to today, ended or
  shown today, and "less than ever" is clearly less than every one;
- no verdict but "not a deal" passes over an offer of today's pack shown now that is clearly cheaper in what is paid
  (worked out in the test from the two offers' prices, not from the measure the verdict chose);
- "always on offer" only when offers of today's pack and deal were dated over 90 % of the days read;
- the summary names every offer shown alongside and no other as on offer now, names an offer without deal text by its
  price, says "no earlier offer … ended" only when none did, and speaks of a share against a raise only when it
  compares by the share;
- another pack is said to be cheaper exactly when an offer of another pack, shown today or ended, is clearly cheaper per
  kg or per litre, never per piece (the generator has two packs of pieces); one shown today when any is.

The test also counts the shapes it reaches, and fails if one of these is never reached.

| | Pack | Deal | Price | Store dates | Nights missed between | Answer |
|---|---|---|---|---|---|---|
| 1 | same | same | same | share a day | none | one offer |
| 2 | same | same | same | share a day | any number | one offer |
| 3 | same | same text | moved with the price before ("25% korting" for 2,24, then 2,39) | share a day | any | one offer; as an earlier offer, at its lowest price |
| 4 | same | same | same | run on | none | one offer |
| 5 | same | same | same | run on | any number | one offer |
| 6 | same | same | same | apart (a day or more between) | none or any | two offers |
| 7 | same | another text | any | share a day | any | two offers |
| 8 | another | same | any | share a day | any | two offers |
| 9 | same | no text (Lidl) | another | share a day | any | two offers: the price is the deal |
| 10 | same | no text | same | run on | any | one offer |
| 11 | today's | today's | any | share a day with today's offer or run on into it | any; or today's own rows missing | today's offer, never an earlier one; today's offer is measured as the store shows it today |
| 12 | today's | today's | any | ended a day or more before today's began | any | an earlier offer |
| 13 | any | another | any | ended before today | any | an earlier offer |
| 14 | any | another | any | dated to begin after today (Jumbo lists next week's) | any, also the last night | neither earlier nor alongside |
| 15 | any | another | any | dated over today, from before or since today's began; shown today | any | alongside: not earlier; named; of today's pack, counts against today's (rules 2 and 6) |
| 16 | another | today's | any | dated over today; shown today | any | alongside, as row 15, never compared with; its days are not today's for rule 4 |
| 17 | any | another | any | dated over today, but not shown today (last seen yesterday, or fifty nights ago) | any | ended early: an earlier offer, never alongside |
| 18 | same | no text (Lidl) | another, replacing the first on the same dates | the same | any | the price no longer shown ended early (row 17); both shown: alongside |

**At which price.** An offer whose price moved while its deal stayed (row 3: AH showing another price before mid-week,
and a stated "25% korting" price with it) is measured at its lowest price. That is the row with the lowest price before,
since for one deal text the price follows the price before: a share of it, or the price the text names. It is the
price a customer could have had while the offer ran, against the lowest price before it, as a reduction is measured
(rule 1). That is for earlier offers only. Today's offer, and every offer alongside it, is measured as the store shows
it today, its own price and price before, never at a price of a row it was joined with: an older, lower price before
would make today's "1+1 gratis" look cheaper than what is paid, and an offer alongside "cheaper right now" at a price
the store no longer asks. The one exception is rule 1, which measures the share against the price before a raise, and
says so.

**Long-dated offers.** Jumbo dates some offers over many weeks (38 of the 428 stored offers of 2026-11-16 span more
than 14 days, up to 356, such as "Jumbo Oosterse maaltijden, 2 voor 5,50" dated 2026-01-07 to 2026-12-08). By rows 2
and 11, every row of such an offer with the same pack and deal is one offer, and today's. So it errs towards "not
enough history yet": the product's earlier offers of that deal are not counted as separate ones. Rule 4 counts the
days the store dated an offer of today's pack and deal over, not the days it was seen, so such an offer seen on only
two nights is "always on offer" over the days its dates cover, and the summary says "dated it on offer". When a
long-dated offer is another one than today's, it runs alongside while the store shows it (row 15): a shorter deal of the
same pack on top of it is compared with it (rule 2), and the summary names it rather than saying the product was not on
offer before. Once the store no longer shows it, it ended early (row 17) and is an earlier offer.

**What is compared.** What is paid, never the deal's wording, so a "2+1 gratis" is compared with the "1+1 gratis" the
product had before. Each offer, today's and every earlier one, is read by today's deal reader (`DealTerms.of` on the
title, pack size, deal and prices kept with the row, not the readings stored with it), so a change to how deals are read
never compares an old reading with a new one. The measure is one of: the price per kg, per litre or per piece, the price
of one pack (for products whose size can't be read, such as AH's "2 voor 5.50" of "AH Goudse plakken" or a Lidl price
without a pack size), or the share of the regular price paid; all of them between offers of today's pack only. Of those
today's offer has, the one most earlier offers have too is used; on a tie the prices come before the share, as a price
does not depend on what the store says the price was before. An offer alongside is compared for rule 2 by what is paid,
below.

**Another pack, said apart.** When an offer of another pack is clearly cheaper (by more than 3 %) per kg or per litre
(the first of these both have), the
verdict says so after its own sentence: one the store shows today first ("Cheaper per kg in another pack now (1 kg):
€1.87 per kg with "1+1 gratis", dated until 23 Nov 2026"), as it can be bought instead; else the cheapest that ended
("Cheapest per kg in another pack (1 kg): €1.87 per kg with "1+1 gratis" from 19 Oct 2026 to 25 Oct 2026"). It never
changes the verdict. Only the price of an amount of the same stuff says which pack costs less: never the price of a pack
or a share, which say nothing across packs, and never the price of a piece (the owner, issue 197): two products under
one title, such as Jumbo's "Braadpan gietijzer" of 14 and of 24 cm, are pieces of different worth.

**The rules,** in this order (the constants are named in `history/DealVerdict.kt` and `history/RegularPrices.kt`):
1. *Not a deal* (`NOT_A_DEAL`): the regular price of the same pack went up in the 30 days before the offer began, and
   with the deal a pack costs at least 97 % of what it cost before. "Before" is the lowest regular price seen in those 30
   days (`REFERENCE_DAYS`), as the EU asks of a shop announcing a reduction (Directive 98/6/EC, article 6a); 97 % is the
   shopping list's own bound for a deal that saves something (`Discounts.factor`). The price now is today's own price
   before as the store shows it. Regular prices before are the store's price before in every row of the same pack
   dated to begin before today's offer, whether or not it was joined into today's offer (last week's "1+1 gratis" at
   1,59 running on into this week's at 3,19 is one offer, and still a raise), each over the row's store dates; prices
   before within today's own dates are not "before". Then there are the looked-up prices (`regularPriceHistory`, AH) of the account's
   ingredient where the looked-up product is the offer's own: the same name, the offer's title adding at most a size,
   and the same size ("AH Snoepgroente tomaat", 500 g, is "AH Snoepgroente tomaat 500 gram"). A price raised less than
   3 % is no raise. A raise that leaves a saving is reported with the verdict, and the deal's share is then measured
   against the price before the raise ("25 % off" a price raised from 2,49 to 2,99 pays 90 %). This rule needs no
   offer history: two regular prices seen are enough to tell. It is not applied to group offers, a title beginning
   with "Alle" ("Alle AH Gerookte zalm"): AH gives such an offer the price before of whichever product of the group it
   shows, so two of them can differ without any price raised. A title without a size is no group: AH writes most single
   products so ("AH Ananas zonder kroon"), and the rule holds for them. The day the price before was "seen" is at the
   latest the day before today's offer began.
2. *Cheaper another way* (`CHEAPER_ANOTHER_WAY`): an offer of today's pack alongside (dated over today and shown today,
   row 15) is clearly cheaper, by more than 3 %, than today's in what is paid: by the first price both have (per kg,
   litre, piece or pack), else by the share of the regular price. Another pack's offer never is (each pack against
   itself; a cheaper pack is said apart, above), nor is an offer the store no longer shows (row 17). Jumbo's "2 voor
   6,00" hamburgers (€1,50 a piece) dated all year and still shown make today's "voor 3,19" (€1,60 a piece) no real
   deal, though it is less than the earlier "voor 3,49" weeks. Like rule 1, a fact that needs no history; the summary
   names the cheaper offer, by its deal text, or by its price when it has none (Lidl).
3. *Not enough history yet* (`NOT_ENOUGH_HISTORY`) when the store was read on fewer than 56 days (`MIN_DAYS_READ`, 8
   weeks of nightly refreshes). Stores run offers by the week and bring a product back every few weeks, so 8 weeks see
   a usual deal come back at least once. Days read, not calendar time: a store that could not be read was not watched.
4. *Always on offer* (`ALWAYS_ON_OFFER`): dated on offer in today's pack and deal (the deal as the customer sees it:
   the same text, or for an offer without one the same price) on at least 90 % of the days read (`ALWAYS_SHARE`, room
   for a pause between two weeks): that is its usual price, not a deal. A day counts when the store's dates of such an
   offer include it, whether or not the product was seen that night, as for which rows are one offer. Another pack's
   days do not count: the 500 g pack on "1+1 gratis" all year says nothing about a week of it on the kilo.
5. *Can't be compared* (`NOT_COMPARABLE`) when today's offer has none of the measures (a deal of unknown kind, a Lidl
   offer with neither deal nor price). *Not enough history yet* when fewer than 2 earlier offers of today's pack have
   the measure (`MIN_EARLIER_OFFERS`): one could be the odd one out.
6. Against those earlier offers, prices within 3 % being the same (`TOLERANCE`: "2 voor 3,99" and "2 voor 4,00", packs
   of "ca. 450 g"): a *real deal* (`REAL_DEAL`) at or below the lowest of its pack seen, of the earlier offers and of
   those shown now in the same measure, and clearly below at least half of the earlier offers;
   *worse than usual* (`WORSE_THAN_USUAL`) when more than half of them were clearly cheaper; else *the usual deal*
   (`USUAL_DEAL`). An offer clearly below the usual but not at the lowest is a usual deal too, and its summary says so
   ("Better than usual, but it has been cheaper"). The usual value said with it is the median.

With each verdict come the facts it rests on: the measure and today's value in it, the usual (median) and the lowest
earlier value with its deal and dates, the latest offer at least as cheap as today's, earlier or alongside (said as
"last this cheap" when it was about the same, within 3 %, and "last cheaper" when it was clearly less), the earlier
offers themselves with their store dates, deal and values (one that ended early is marked so, and said to be "no
longer shown"), the offers alongside (each named in the summary: "Also on offer now: …", by its deal text, or by its
price when it has none), the cheaper pack if any, the raise if any (the summary says the share is measured against the
price before it only when the share is the measure), and on how many of the days read the product was dated on offer,
and a summary in English, such as: "Worse than usual: 67% of the regular price, where it usually goes for 50% of the
regular price; last cheaper 50% of the regular price with "1+1 gratis" from 2 Nov 2026 to 8 Nov 2026, …". With no
earlier offer it says "no earlier offer of it ended since …", never that the product was not on offer: an offer
alongside may have run all along. When the product came in other packs too, it says "in this pack" ("on offer 3 times
before in this pack", "less than ever in this pack", "no earlier offer of this pack ended"). The response gives, with
each earlier offer and offer alongside, its pack, its price and whether it ended early, and the cheaper pack
(`cheaperPack`: the offer, the measure, today's value in it, and whether it is shown now); all of these were added
in issue 197 and are optional. Each offer named also has its pack in short (`packName`, below).

**In the app** (issue 118, step c). `Offer.verdict` (`api/Verdicts.kt`) holds the facts the app words, in English and
Dutch: the verdict, the measure, today's and the usual value, whether a usual deal is better than usual, the latest offer
at least as cheap, the days read and needed, whether the product came in other packs ("for this pack"), a raise, the
offer cheaper now, the cheaper pack, the offers alongside (without those already named) and the five latest earlier
offers of the pack, newest first. Each offer named gives its store dates, its deal text or else its price, its value in
the verdict's measure, whether it ended early, and, where it is another pack than today's, a short name of that pack.
The short name (`packName`) is made on the server so that the app need not read the stores' texts: the size alone, in
units' symbols and with the stores' decimal comma, so it reads the same in both languages ("Alle flessen à 1,5 liter" is
"1,5 l", "Tray 24x250ml" "24 × 250 ml", "Schaal circa 700 gram, per 100 gram" "ca. 700 g", "1 stuk, doorsnede van 14 cm"
"14 cm"); none for a text with several sizes ("Zak 500 gram of 1 kilo"), a count of pieces or no size, and the app then
names the offer by its deal. Every field has a default and the enums read as their default when unknown, so a later
server can add a verdict without breaking this app.

The app shows a few words under the deal, on the Offers screen and on a line of its own under the shopping row (by
section and per store; the row's whole width, aligned under the deal chip, so the words wrap between words only at any
font size), only for the verdicts that call for action (the owner's choice on #118): "Real deal" (in the theme's
primary colour and bold, the only one that stands out), "Can be cheaper", "No real discount" and "Usually cheaper".
Nothing for a usual deal, one better than usual or always on offer, while there is not enough history (every offer for
the first eight weeks of recording), or with nothing to compare by, so that the screens do not fill with it; the detail
says each of them. A tap on an offer opens the verdict at length under it: one line ("Real deal: lowest so far,
€13.30/kg", "Usually cheaper: €2.34, now €2.75 (last on 1 Nov)", "Too new to judge (2 of 56 days)"), the other pack, a raise and the
offers alongside, and the latest earlier offers ("19–25 Oct · "1+1 gratis" · €1.87/kg · Ended early"). An earlier offer
is named by the days it ran ("as low as before (19–25 Oct)"), and "last on" names the last of them. A line that holds
today's offer against an earlier price gives today's in the same terms ("now €2.75"), as the deal chip beside it shows a
pack's price, which a price per kg cannot be compared with by eye. A tap on a
shopping row's deal chip shows the same line and notes under the deal in the snackbar; when an offer of the row has
earlier offers, the snackbar has "Price history", which opens the same detail as the Offers screen in a dialog (a
snackbar has no room for a list). Early on, with no earlier offers yet, the snackbar is as it was.

Every answer that carries the shopping list, ticks included, judges the account's offers again. On a made-up year of
history (26,281 rows, 1,113 recordings, a file database, `DealVerdictRealDataTest`) that took 41 to 60 ms for an account
with 39 offers for its ingredients, on a desktop processor; nothing is kept in memory between answers.

A copy of a real database can be read with
`PRICE_HISTORY_DB=/path/to/copy.db ./gradlew :server:test --tests '*DealVerdictRealDataTest*' --rerun -i`: it copies the
file twice, replays the stored offers on one (as above) and prints the verdicts on the offers of the account with the
most ingredients, and gives the other twelve weeks of made-up history to show each verdict's wording on real offers.
With `PRICE_HISTORY_NEXT_DB=/path/to/later-copy.db` as well, a later database's stored offers are replayed on a further
copy as one more night, at the time they were fetched, so that two real nights can be read.

## Buying ahead and stocking up

Next to what the week's meals need (`ShoppingList.items`), the shopping list gives two kinds of
advice, in separate lists so that apps from before them (v0.1.2) never take them for this week's
needs. Both go by this week's offers at the stores the account shops at, and by what they save
against the regular price. Each can be switched off in the account's settings
(`GET`/`PUT /api/shopping-advice`, both on by default) or dismissed for one week
(`PUT`/`DELETE /api/plans/{weekStart}/shopping/dismissed/{buy-ahead|stock-up}`); neither changes the
plan or the rest of the list.

**What keeps.** Each shop section has a shelf life (`ShopSection.shelfLife`): short (fruit and
vegetables, bread, deli, chilled vegetarian, dairy, ready meals, Other), until next week in the
freezer (fresh meat and fish), or months (sauces and oil, pasta and rice, tins, breakfast, baking,
snacks, drinks, frozen). An ingredient keeps until next week unless its section's shelf life is
short; the account can correct that per ingredient (`PUT /api/ingredients/{id}/keeps`, `null` goes
back to the section's answer), for instance for long-life milk.

**Buy ahead for next week** (`ShoppingList.buyAhead`, keys `ahead:i<id>`), only for a week with a
plan. Next week's meals are those already planned there, or else a provisional choice
(`BuyAhead.provisional`): the dinners the planner could pick, a meal by calories at a portion within the
profile's range (README, "Portion per meal"), not disliked and
not planned this week (the planner avoids last week's recipes), one batch each. An ingredient of
them qualifies when it keeps, is on offer this week but not (as far as known) next week, is not a
staple and not on hand, and its saving is known. The meals saving at least €0.50 that way, at most
three, most saved first (`BuyAhead.choose`), give the list: what one batch of each needs, with the
meals named. A next week that holds only the meals of a batch made this week counts as not planned, and
its provisional choice is no more dinners than those meals leave free: none when they fill every dinner. No random choice is involved, so the list stays the same while it is ticked off.
Nothing of the provisional choice is stored, so `ShoppingList.buyAheadProvisional` is true while next
week has no plan: the app then says the meals are dinners next week could have (rows read "for e.g."
before them) and not what it will cook. It is false once next week is planned, and when buy ahead is
switched off or dismissed.

**Stock up** (`ShoppingList.stockUp`, keys `stock:i<id>`): ingredients that keep and are a staple, or
that a recipe uses and keep for months (so not fresh meat), on offer now, not on hand and not on the
list already. The best offer each, with its saving; the six saving most (at least €0.25), in
walking order. A deal that wants several packs together ("2 voor", "1+1 gratis", "3+1 gratis"; see
`Discounts.packsNeeded`) is advised in those packs, so the row reads "2 × 1 kg", its amount and
regular price are those of all the packs, its saving is counted and its tick stocks all of them
(`PackSize.stockUpPacks`). A deal for more than 6 packs, more than 10 kg in all, or several packs of
a size that cannot be weighed is not advised. Advice only: it never changes the plan.

Stock-up goes by the price verdict on the offer ("Is it a good deal?"), since staples keep and waiting is
a real option (issue 118, step d). An offer whose verdict is "no real discount" (`NOT_A_DEAL`,
`ALWAYS_ON_OFFER`), "usually cheaper" (`WORSE_THAN_USUAL`) or "can be cheaper" (`CHEAPER_ANOTHER_WAY`)
is not advised: the ingredient is advised by its next best offer without such a verdict, or not at all.
Of the rest, a real deal (`REAL_DEAL`) comes before the others when more than six qualify; the six are then
chosen by saving as before. A usual deal, too little history ("not enough history yet": most offers, for the
first eight weeks) and nothing to compare by leave the advice as it was. The row's offer carries its
verdict (`Offer.verdict`), so the app says "Real deal" on it as on any deal.

**Ticked items go to the pantry.** Ticking a buy-ahead item puts its ingredient on hand from next
week (`stock.fromWeek`), so this week's meals, list and recipe ideas still get their own amount and
next week's plan prefers the meals that use it (the planner's on-hand bonus); ticking a stock-up item
puts it on hand from this week. Next week's plan is not fixed in advance; only what was ticked
carries over.

**The stock is kept per tick.** Each tick that puts something at home is an entry of its own
(`stock`, by ingredient, week and `source`: `STOCK_UP`, `BUY_AHEAD`, or `USE_UP` for a bought item
used up, below), and so is each item used up. Un-ticking takes back that tick's entry: what is left
of what it put there, and no more, whatever else came in since. Another tick of the same ingredient,
an item used up, or what an un-eaten meal gave back to another entry stays (issue 116; before, one
entry per ingredient held it all, so un-ticking could take the grams of another, or find nothing of
its week to take). Ticking a ticked item again changes nothing. What the list and the pantry say is
on hand is all entries of the ingredient added up; unknown when one of them has no amount.

**Eating from it.** A ticked item is on hand in the amount the list advised buying (`stock.grams`:
three batches of chicken for three meals, one pack of rice). Each meal of that week or later that
uses it is eaten from it when it is marked eaten, from the oldest entry first (by its week, then by
when it came) and from the next once that one is used up: a dinner takes what it was cooked with, its
leftover lunch included (which then takes nothing more), and marking a meal eaten twice takes it
once. A leftover lunch eaten while its dinner is not (skipped, say) was cooked for itself and takes
its own share. The item stays on hand until less than a gram is left, or until it is removed in the
pantry, which works at once as always. A line whose weight can't be told ("olive oil to taste", "1
bunch" without a weight per piece) takes nothing: there is no amount to go by, and taking the whole
stock would empty a litre of oil on its first use; a volume without a density counts as water, as
for nutrition. An item without an amount (one the list no longer advised, or one ticked before
amounts were kept) is used up by the first meal that uses a weighed amount of it, as before.

**A tick sent late.** The amount is the one the list advises when the tick reaches the server. A tick
made offline may arrive after the store's offers were replaced and the list no longer advises the
item; the app therefore sends the grams its stored list advised with the tick (`ShoppingCheck.grams`,
never sent when unknown), and the server takes those when it has no advice row of its own. Sent
grams must be above 0 and at most 10 kg (`ShoppingCheck.MAX_GRAMS`), else the tick is refused (400)
and nothing is stored. A tick without them (apps before it, or a row the stored list does not have)
is on hand without an amount, as before.

**A tick sent late keeps what the phone showed** (issue 116). A row of what the meals need, ticked while
the phone had no connection (or without an answer), is sent later with `ShoppingCheck.shown`: the weight
and pieces the row said, the saving of its offer as the phone counted it at the account's stores, the
store of that offer, and the day it was ticked (`tickedOn`). A tick that goes out at once sends none of
it, and the server works the tick out as always. The server keeps these for the row's tick instead of
what the row says on arrival, for a row its list still has (the row's name is always the server's), and
only within bounds; a figure out of bounds is left out, not refused, so the tick is stored all the same
with the server's own figure for that part:

- the weight is above 0, at most 10 kg and at most twice what the row says now (a plan changes, a row
  does not double between two phones), pieces above 0 and at most twice the row's now, for a row that
  counts pieces;
- the saving is believed only when the tick is dated on an earlier day than the server's (a tick of
  today still has its offer on the server's list, so the server works it out) and no more than 56 days
  back. A phone's clock may run ahead: a date one day after the server's counts as the server's day (so
  the server works that tick out); a date further ahead is not believed, and neither is its store. The
  saving is from 0 to the row's regular price (what its whole need costs) in proportion to the amount
  the phone showed against what the row says now (so a need that shrank since does not cut a real
  saving down, nor a need that grew let one grow), and a row without a regular price saves nothing
  known;
- the store of that saving counts only for one of the account's stores that was recorded in the price
  history with an offer for the row's ingredient valid from a day before to a day after the tick's day;
  otherwise the saving is kept without a store (its amount is bounded by the row all the same; only the
  store header it is listed under is unknown). It counts only with a saving above 0, and another
  account's stores never count;
- a tick that is there already stays as it is, so of two phones ticking the same row the first one
  to arrive decides, and each account's ticks are its own.

An app from before it sends none of these and a server from before it refuses the field (400): the
app sends the tick again without it, which stores what the server says, as it did.

**How much is left** shows where it is known. A row of the list whose ingredient is on hand for the
week carries the grams left (`ShoppingItem.onHandGrams`), and the app says `300 g on hand, buy 200 g`
under its name, `600 g on hand: enough` when that covers the need (with no packs to take), or `300 g
on hand` when the need can't be weighed in full; the packs to take are for what is still to buy.

**What is still to buy** goes by what the meals to come need, which the server sends
(`ShoppingItem.toComeGrams`): the weight the meals still to cook need. What is still to buy is that
less the grams on hand. The row's source meals (`ShoppingItem.toComeRecipes`, next to `recipes`, the
whole week's) are only those still to cook, so a row does not name a meal already eaten; an app from
before it shows `recipes`. The pieces the meals to come need (`toComePieces`) keep the piece count in the
amount column once that shows the weight of the meals to come (`6 × · 680 g`). The stock goes down for a meal as it is eaten, so the meals eaten are left out
of the need too, else mid-week a row would ask to buy again what the eaten meals took. The meals still
to cook go by the rules of eating from the stock: an eaten meal is cooked, and so is a leftover lunch
whose dinner was eaten; a skipped meal is not, unless it is a leftover lunch whose dinner is still to
cook (the dinner is cooked with its leftovers). A leftover lunch eaten before its dinner was cooked
for itself and took its own share, so the dinner still to cook counts only its own. So, for a row not
ticked:
- a meal eaten from what was bought ahead or stocked up: the stock went down by what it took and its
  need is gone, so what is still to buy stays as it was;
- a meal eaten without the row ticked or a stock: it was made with what was at home, so only the
  meals to come are still to buy;
- a skipped meal needs nothing; un-eating or un-skipping a meal brings back its need and (above) what
  it took, so the number is as before.

A planned meal also says how much to cook when it is made (`PlannedMeal.cookPortions`, in the plan and
in `GET /api/days/{date}`, not stored): its own portions plus those of the leftover lunch cooked with
it, 0 for a skipped meal and for that lunch (cooked the evening before), unless its dinner was skipped
and it is cooked for itself. The recipe opened from the meal shows its amounts for that many servings,
so what is cooked is what the list buys. An app from before it, or a server without the field,
shows the recipe as written.

It is exactly 0 when no meal still to cook uses the row, whatever its unit: a row counted in pieces or
written without an amount ("a bit of pesto") is done with once its meals are. Null when it can't be
told: a meal to come uses it but the need can't be weighed in full, or uses it only without an amount;
never on own items or advice. `ShoppingItem.grams` stays the whole week's need, as apps before it read
it, and what a tick remembers is that, so a meal eaten never makes a ticked row "need more" or leave
the list for "no longer needed".

Once some of a row's meals are done, the app's amount column shows what the meals to come need, and
the line under the name adds up with it: `680 g` next to `176 g on hand, buy 504 g for the meals to
come`, `169 g on hand: enough for the meals to come`, or `For the meals to come` without a stock. When
none of them is still to come, the column is empty and the line says `Nothing to buy: the meals that
need it are done`; only then. A ticked row keeps the week's need in its column, which its "more" line
counts from. A row with nothing left to buy that is not ticked (no meal to come needs it, or the stock
covers them to within a gram) is no item to buy: the progress line leaves it out (`Nothing left to buy`
when that leaves none); the per-store view puts it in a group of its own at the end, `Covered ·
2 items` (not the progress line's words, which sit just above it), so the store and "any store" headers count what the progress line counts; the savings
leave it out; and shopping mode puts it in the Done group (a finished section says `✓ 3 done`, as not
all of them need a tick). It stays on the list, and can be ticked as before. The gram of leeway is only
for what a stock leaves over: a need under a gram with nothing at home (a pinch of oregano) is still
to buy.

A row's saving goes by what is still to buy of it; the deal check ("1+1 gratis" wanting two packs) goes
by the packs for that amount. Once ticked, a row keeps the saving it had at the tick
(`ShoppingItem.boughtSaving`, kept with the tick since version 16 of the database), so a store's header
says what the shop saved and does not shrink as meals are eaten or the offer ends: the server works it
out by the app's rules (`shopping/RowSaving.kt` in `shared`), at the account's stores, on the day it
stores the tick (for a tick an offline phone sends later, what the phone showed when it was ticked,
within bounds: "A tick sent late keeps what the phone showed", above). The row stays under the store whose offer it was bought with
(`ShoppingItem.boughtOfferStore`) while that store is in use, also when the offer has ended or another
store's is better now; a tick that saved nothing (the offer had ended, starts later, or is at a store
not in use) keeps 0. Un-ticking gives the saving up; ticking it again, or ticking a row that needs more
again, keeps what it saves at that moment. Ticks from before version 16, the advice and own items, and
apps before it go by what is still to buy, as before. A header's one number is what its store's deals
save on the week's list, bought and still to buy together. A ticked row has no packs
line: what was bought is not known in packs, and the whole need would count what was at home.
A ticked row says nothing of the stock: what it said when ticked already counted the stock there
was, so on a row that needs more the one number still to buy is its "more" line, never that less
the stock again (the pantry still shows the amount); that "more" is never more than what is still to
buy, so once the meals that need it are eaten, it needs nothing more. The
pantry gives the grams left of each ingredient, all its entries added up (`Pantry.onHandGrams`, next
week's stock included, as it is at home) and the app shows `300 g left` under its name, with the week
the stock was bought for (`Pantry.onHandFrom`, the week its tick counts from, for stock a tick put
there): `300 g left · for the week of 23 Nov`. An ingredient with stock of several weeks (bought ahead
and stocked up again, say) lists each of them (`Pantry.onHandWeeks`: `for the weeks of 16 Nov, 23 Nov`);
`onHandFrom` keeps the earliest, the week meals start eating it from, for apps that predate the list;
one set by hand only gives none. An entry set by hand, or ticked without an amount, has no
amount: the row says `(on hand?)` as before and the pantry no number, never zero.
Ticking the row stays the user's choice: the app does not tick a row off for them, as a tick says
what was bought.

**Un-eating.** What each meal took is kept (`stockUse`, under the meal whose portions it was for, per
entry it took from), so marking an eaten meal skipped or planned again puts exactly that back, each
share into the entry it came from, also when it had used the entry up; eaten again, it takes it again. A dinner un-eaten while its leftover lunch stays eaten gives
back only its own share, and a leftover lunch un-eaten while its dinner stays eaten gives back
nothing (the leftovers were cooked): whatever order the two are marked in, the stock ends the same.
Nothing comes back once the owner has said otherwise: after the tick of that entry was taken back
(not bought), or the ingredient was put on hand or removed by hand in the pantry. Meals eaten before
version 11 of the database have nothing kept to give back.

**A prepped batch** (issue 234, README "Prepped meals") is made once, whichever of its meals is eaten first:
then every meal's share (its portions as adjusted; a skipped meal's too, as it is in the box) is taken, each
kept under its own meal, from the stock that counts from the week the batch is made, also for a meal of the
next week: what was bought ahead for that week is not used for it. Eating more of its meals takes nothing; the
last eaten one un-eaten gives every share back. A skipped meal's share eaten in a later meal moves what it took
along. The shopping list counts the batch as one meal of all its portions on its made-on day, still to cook
until it is made; the list of a later week buys nothing for the batch's meals there and names the week whose list
did (`ShoppingList.preppedBoughtIn`, the earliest when there are several). A side taken from a batch (step 2) takes its share as its recipe has it (a side can't be
adjusted), kept under the meal it is next to but apart from that meal's own (`stockUse.role` `SIDE`), so eating
or un-eating the meal's main moves only the main's share, and the side's goes with its batch; a new main for the
meal keeps the side's share where it is. A skipped meal's share whose slot a standard meal took (issue 265, README
"Standard meals") is in the box: what the batch took for it moves under that slot as `stockUse.role` `BOX`, apart from
what the standard meal takes when it is eaten, stays there while the slot's meal changes, is given back when the batch
is un-made, and moves with the share as the meal's own when it is eaten elsewhere, or when the standard meal is removed
and the skipped meal comes back. Made after the skip, the batch takes that share as its recipe has it, as the slot's
lines are the standard meal's. A batch made with a side is one meal of the list next to that meal, also one
eaten partly as meals (issue 269), whose shortfall walk then counts what its meals had as adjusted. What was at home for a week's shortfalls (`atHome`, below) counts what each meal of the week
took, but a batch by the week it was made in, whose stock it was cooked from (issue 263): the shares of its meals
and sides in the next week count there, and not in the next week.

**An adjusted meal** (issue 226, README "What was in a meal") takes what was in it: its recipe's grams for
its portions with its own changes (an amount changed, a line left out at 0 g, an ingredient added), by the
rules above; an added ingredient that is in stock is eaten from it like any other. A leftover lunch's share,
taken when its dinner is eaten, is the lunch as adjusted, added toppings included. Adjusting a meal whose
share was taken already (eaten, the leftover lunch of an eaten dinner, or one that keeps a share) moves the
stock by the difference: for each ingredient whose amount changed, what the meal took of it goes back into
the entries it came from and the new amount is taken as eating it would take it (`StockRepository.retake`);
what it took of the others stays. What the meal holds is then kept under it as before, so un-eating it
gives back exactly that and the pantry is as it was before the meal was eaten. Adjusting a meal still to
eat changes nothing in the stock until it is eaten. The new amount of an eaten meal is taken from the stock
as it is when the adjustment is made, not as it was when the meal was eaten: nothing records what was at
home then. So an egg added to Monday's dinner on Wednesday, after eggs were bought on Tuesday, is taken
from Tuesday's eggs although they were not there on Monday; and a meal eaten before version 11, which kept
no uses, has its changed ingredients taken in full. Un-eating gives back exactly what was taken either way.
The shopping list does not change for an adjustment by itself: what was added came from the fridge or the
pantry; the one exception is below.

**A later meal left short** (issue 226). An adjusted meal that has more of an ingredient than its recipe may
eat what was at home for a meal still to cook later that week. Then the Adjust screen names that meal and the
amount it is short (`MealLines.shortfalls`), and the shopping list asks for that amount again. What was at
home for the week's meals is what the server knows of (`StockRepository.atHome`): the larger of what the stock
gave the week (the stock that counts from the week, `onHandGramsFor`, with what the week's eaten meals took of
it already, as it was there for them) and the grams the tick of its row on the week's list remembers. Not their
sum: a tick remembers what the row said then, the week's whole need, and the row asked to buy only what the
stock did not cover, so the stock is in the tick already (100 g in stock, "buy 100 g" of a 200 g need, ticked:
200 g at home, not 300 g). The larger of the two is also what the list counts on for a ticked row: its "more"
is the need beyond the tick, but no more than the meals to come need beyond the stock. So the card and the row
come from the same number. That number is what was really at home when the owner bought what the row said and
no stock for the week came after the tick. Stock put at home for the week after its row was ticked (an item used
up on the list, a second stock-up) is not counted, by the card nor by the row: both then say more is short than
is, by up to that stock. An ingredient on
hand by hand, or with stock, a use or a tick of an unknown amount, claims nothing, as nobody knows how much
there is: an ingredient set "On hand" in the pantry gives no warning and no row, by design, until it is taken
off again. So does one the week's list has no row with a known weight for (`withoutWeighedRow`): a staple
(always at home, never on the list), one that counts for nothing (to taste), and one some line of the week
can't weigh ("1 bunch" without a weight per piece), as there is no row to ask for it on. The walk (`shortfalls`
in `Shortfalls.kt`) goes through the meals cooked or still to cook in the
order they are cooked (by day and slot, a leftover lunch with the dinner it was cooked with), twice at once:
as adjusted, each meal taking what it eats while something is left, and as planned, each taking its recipe's.
A meal still to cook needs its recipe's amount, or less when it was itself adjusted to less (more than its
recipe is from the fridge too). What it is short as adjusted beyond what it would be short as planned, at
least a gram, is what adjustments left it short. So:

- 200 g of spinach ticked as bought for Thursday's curry, and 100 g of it added to Monday's bowl: the curry
  is 100 g short, and the list asks for 100 g more.
- 100 g in stock and the row ticked after buying the other 100 g, then 100 g added to the bowl: 200 g was at
  home, so the curry is 100 g short, and the list asks for 100 g more. Also when the bowl was eaten first.
- 300 g at home (overstocked, ticked or not): 60 g added is within the 100 g to spare, nothing is short and
  nothing changes; 150 g added leaves the curry 50 g short.
- Nothing known at home: the bowl's spinach came from the fridge, and the curry was short of all of it
  anyway, which the list buys already: nothing more.
- The curry itself adjusted to 100 g: nothing is short.
- The spinach added to Friday's bowl, after the curry is cooked: the curry had its share first, nothing is
  short.

The same holds when the bowl was eaten before the spinach was added: its share comes off the stock then
(above), and the walk counts it back as at home for the week's meals. The Adjust screen shows a later meal
cooked after the one being adjusted, of an ingredient that one took more of than its recipe from what was at
home, with one amount per ingredient: the meals' shortfalls added up and rounded once. When what was at home
covered the week before the adjustment, that sum is exactly what the row then asks for, with stock, a tick or
both (tested for each, and after eating), and the app rounds it the same way, so the card's `93 g` and the row's
`93 g more` agree (two meals rounded each, 37 g and 57 g, would have said 94 g). When the row asked for some
already, it asks for that and the card's amount. A ticked row says "… more" only from the usual step (5 % and at
least 25 g); a smaller amount is on the card alone.

On the list (`ShoppingListRepository.countTaken`), only for an ingredient that leaves a later meal short, the
grams each adjusted meal took of what was at home beyond its recipe count as part of that meal's need: in the
row's `grams` (the week's need), and while that meal is still to cook in `toComeGrams`, with the meal among the
row's `recipes`. The row is then read as any row whose need grew: a ticked row needs more than it remembers
was bought and the app shows it open with `100 g more (200 g bought)` (from the usual step, 5 % and at least 25
g; "What the app shows of it" below), and a row not ticked says the stock no longer covers it (`150 g on hand,
buy 50 g`). Ticking it again remembers the new amount, after which the walk finds enough at home. Every other
row stays as the recipes have it, as does a row whose weight is not known in full. An installed app reads it as
before, as only the numbers of fields it knows change.

What a meal took is kept under its day and slot and goes with the meal, so a meal the stock went down
for keeps its recipe and its place: it was eaten, it is the leftover lunch of an eaten dinner, or what
was taken for it is kept under its slot all the same (a leftover lunch whose eaten dinner was removed,
which servers before issue 165 allowed). No way of replacing or removing a meal touches it until it,
or its dinner, is un-eaten:

| Action | Meal and its partner | What happens |
|---|---|---|
| "Another recipe" (`reroll`) | eaten; a leftover lunch (planned or skipped) of an eaten dinner; a lunch that keeps a removed dinner's share | 409, nothing changes |
| "Another recipe" | a dinner whose leftover lunch is eaten, skipped, locked or keeps a share | the dinner changes, the lunch stays as it is |
| "Another recipe" | a dinner whose leftover lunch is planned; any other meal | replaced (with its lunch); they hold no stock |
| Choosing one (`PUT meals/{day}/{slot}`), writing one (`POST .../generate`) | as the first row | 409 (writing: 422, asked before Claude writes anything) |
| Choosing, writing | a dinner whose leftover lunch is eaten, skipped, locked or keeps a share | the dinner changes, the lunch stays as it is |
| Writing | eaten while the recipe is written | 422 "This meal was eaten already; the new recipe … is kept in your recipes", the meal stays |
| Choosing, writing | anything else, or an empty slot | replaced or filled; it holds no stock |
| Planning or replanning the week (`POST generate`) | locked, eaten, skipped; the planned leftover lunch of an eaten dinner; a lunch that keeps a share | kept as it is; the rest is planned around it |
| Planning or replanning the week | any other meal | planned anew; it holds no stock |
| Planning the week with dinners chosen (`POST planning/done`) | as for planning the week | kept as it is; the chosen dinners go on the other days |
| Moving a dinner to another day (`POST meals/{day}/DINNER/move`) | either dinner as the first row, or of a prepped batch, with a side or a share in the box under it; its leftover lunch eaten, skipped, locked or with a side | 409, nothing changes |
| Moving a dinner | any other dinner | it and the dinner on that day swap days, with what was in them; their leftover lunches follow; they hold no stock |
| Removing (`DELETE meals/{day}/{slot}`) | as the first row | 409, nothing changes |
| Removing | any other meal | removed; it holds no stock, and an eaten leftover lunch of a removed dinner keeps its own share |
| Deleting a recipe | planned this week or later | 409, naming the days |
| Deleting a recipe | planned only in weeks that are over | the plan keeps the meals, and what they took |
| Putting a recipe back in the bank (`PUT /api/recipes/{id}/added`, `{"added": false}`) | as for deleting; also while it is a routine | as for deleting: 409 naming the days, or the plan keeps the meals |
| Taking a recipe out of the rotation | any | the meals planned with it stay as they are |
| Locking, marking eaten, skipped or planned | any | only that flag changes; marking takes and gives back as above |
| Adjusting what was in it (`PUT meals/{day}/{slot}/lines`) | skipped | 409, nothing changes |
| Adjusting what was in it | its share taken (as the first row) | the meal keeps its recipe; the stock moves by the difference |
| Adjusting what was in it | any other meal | only its lines change; the stock waits until it is eaten |
| Adjusting what was in it | it takes what was at home for a later meal | as above; that meal is named on the Adjust screen and the list asks for the amount short ("A later meal left short") |

Removing the leftover lunch of an eaten dinner is refused rather than giving its share back: the
dinner was cooked with it, so that share is used whether the lunch is eaten or not. Skipping it says
it is not eaten, as for any meal. A replan counts a kept leftover lunch in its day's calories and
protein, as it is still to eat, but not in what the shopping list still has to buy for (its share was
taken when its dinner was eaten); the planner puts no other lunch in its slot. So it is for a leftover
lunch whose eaten dinner a server before issue 165 removed (issue 169): what is kept under its slot says
its share was taken, so it counts as cooked (it cooks nothing itself, and the list does not buy for it),
also when its recipe is chosen for that dinner again, which then cooks only its own portions.

A dinner whose leftover lunch was swapped for another recipe before it was eaten was cooked for itself
only, and eating it took only its own share. Neither a replan nor "Another recipe" for that lunch plans
leftovers of it there afterwards (issue 169): nothing was cooked or taken for them, and the shopping list
would not buy for them either. The lunch is planned like any other meal, cooked for itself and bought for.

Each change of the plan reads it and writes it in one transaction that takes the database's write lock
first, so a meal eaten meanwhile on another device is not replaced after all. So does every other change
that reads before it writes (issue 169): the stock (ticks of the shopping list, using up, on hand by hand,
eating and un-eating), the catalogue's links, editing a recipe and the cooking-site sync, and a login under
new names; and since issue 248 every other write of the server, a single statement too: the offer refresh,
the price lookup and the price history, the NEVO import, making a recipe, own items of the shopping list and
their ticks, settings, the food log and the recipe pictures. They share one helper (`Database.writing` in
`Database.kt`), whose first statement changes nothing on `appUser` (a table of no account, so a login
without an account yet takes it the same way): a write of another process in between (a second server, the
sqlite3 shell) makes it wait instead of failing with "database is locked". A login of a known account under
the same names writes nothing and does not wait for the lock. None of them holds the lock across a network
call: the cooking-site fetch, the store and price lookups and Claude's recipe writing all happen before or
outside them, and the server's long jobs write in short turns of their own (one account's import, one store's
offers, one price found, one recipe picture).

Within the server they also take turns before their transaction begins (issue 246): one at a time per
database, first come first served; one called inside another on the same thread (eating a meal changes the
stock, a replan saves each meal) joins it, and the two commit or are taken back together. On the database
file each thread has a connection of its own, and SQLite kept them apart already; the in-memory database of
the tests has one connection that every thread shares, where two at once began a second transaction on it
("cannot start a transaction within a transaction"), ran one inside the other's, or read the other's new row
as its own. A writer waits at most 30 seconds for its turn (issue 248), so that one that hangs does not hold
up every other for good, while a save behind a nightly job's turns still goes through: then it gives up, and
a request is answered 503 with "The server is busy saving something else; try again in a moment". The log
says which thread held the turn, for how long, and where it is (its stack). A writer that holds the turn for
more than 10 seconds, a third of that, is logged with the function that wrote when it lets go: three such in
a row would make the next writer give up.

Marking an item on hand in the pantry makes it one set by hand, even if ticks put it there (its stock
goes, and it keeps its place in the pantry's list): those stay until removed by hand, whatever is
ticked or eaten, and ticks add no stock to them.

Version 14 of the database split the one entry per ingredient it had into these entries. Each kept
its grams and was given the tick that could have put it there: a buy-ahead tick of the week before
it, else a stock-up tick of its week, else none (kept as an item used up, which no un-tick takes
away). What was added to an entry before stays in it, and one whose week was moved earlier matches no
tick: such an entry stays at home when its tick is taken back, until it is eaten or removed in the
pantry.

**The plan's cost** stays at the week's own prices, bought-ahead food included. The saving shows
where the money is spent, on the buy-ahead item of the week it is bought in; pricing next week's
meals at this week's offer as well would count it twice, would make the week's cost change as the
stock is eaten, and when more meals use the food than it was bought for, there is no telling which
of them were made from it.

In the app the list then has three parts: "For this week", "Buy ahead for next week (on offer now)"
and "Stock up: on offer now", each by shop section in walking order, in shopping mode (ticked items
in a Done line) and in the per-store view; at one chosen store only the advice on offer there is
shown. Each advice group says what its offers save and has a Hide button for the week, with a line
to show it again. The two switches are under Settings, Shopping; whether an ingredient keeps is
corrected in the same dialog as its shop section (Recipes → Link ingredients → Shop sections).

**What a tick remembers.** Ticking a row of what the week's meals need (`PUT
/api/plans/{weekStart}/shopping`, keys `i<id>` and `t:<text>`) keeps what the row said then with the
tick (`shoppingCheck.name`, `grams`, `pieces`; the known weight when not all of it could be weighed),
so the list can tell when the plan changed since. The row stays ticked (`checked`) and says what was
bought (`ShoppingItem.boughtGrams`, `boughtPieces`), so when a reroll makes the meals need more than
that, the app can say more is needed. Ticking a ticked row again keeps what it said first; un-ticking
forgets it. A ticked row no meal needs any more (its meal was rerolled or removed) leaves `items` and
is listed in `ShoppingList.noLongerNeeded`: its key and name, what was bought and whether it is on hand
already; un-ticking it the usual way takes it off. Advice rows remember their amount in the pantry
instead (above), and own items are ticked on the item itself. A tick of a key that is no row of the
week's list at that moment is kept as always, and remembers nothing. When the text of a ticked text
row is linked to an ingredient later (a link, an alias, a new or renamed ingredient of its name, or
the links made on a sync), the row's key becomes the ingredient's (`i<id>`) and its tick moves with
it, in every week, with what it remembers, so the row stays ticked. Where that ingredient's row is
ticked already in the week, both ticks stay as they were: the text's is listed as no longer needed,
and the ingredient's row may need more. Taking a link away again moves no tick back. Ticks from before version 12 of
the database remember nothing either, as nobody knows what was bought: they stay plain ticks, never
show as needing more, and are not listed when their row goes. What is remembered is the list as the
server has it when the tick arrives, so a tick an app sends late (offline first) remembers the plan of
that moment.

**What the app shows of it.** A ticked row whose need grew since it was bought (the weight by more
than 5 % and at least 25 g, or at least one more piece; also when an adjusted meal ate what was bought for
a later one, "A later meal left short" above) is shown open, with `600 g more (800 g
bought)`, and counts as not ticked in the progress line, the sections and shopping mode's Done
groups; tapping it ticks it again (`PUT /api/plans/{weekStart}/shopping/again` with `{"key":
"i12"}`), so the server remembers the new amount. That is one step in one transaction, so a failed
request never leaves the row un-ticked with what was bought forgotten; only a ticked row of what
the meals need can be ticked again (another key is a 400, one without a tick of the account in that
week a 404, one no meal needs any more a 409). A row without bought amounts is a plain tick.
Ticking again needs the server: it is not kept for later like a plain tick, so without a connection
(the offline line is shown, or nothing answers) the app says `Not saved: ticking this again for the new
amount needs a connection` instead of "Could not save the tick", which the offline line's "ticks are
saved and sent later" would contradict. A failure with no answer is told in a plain sentence, as
`the server could not be reached`, with the exception's own text behind "Details".
`noLongerNeeded` is the group "Bought, no longer needed" below the list, with "Use it up"
(catalogue ingredients only, not on hand already) and "Remove" (un-ticks it). "Use it up" (`POST
/api/plans/{weekStart}/shopping/use-up` with the key) puts what the tick remembers was bought on
hand as stock counted from the list's week, which meals eat from, and the pantry and the list say
how much is left; an entry of its own beside any stock there already, so un-ticking a buy-ahead or
stock-up item of that ingredient leaves it. Used up on next week's list, it counts from next week,
as what is bought ahead does: it was bought for that week, this week's list and meals have rows of
their own and never count on next week's list, and the server goes by the list's week rather than
guess from its clock when it was bought (a tick also arrives late). The pantry shows it at once, as
it is at home. Without a remembered weight (counted in pieces with no weight
per piece) it is put on hand by hand, as the pantry does, with no number. Either way the tick goes,
so the item leaves the group, all in one transaction. An item the group does not have is refused:
one the meals of the week need again (409), and one not bought in that week or another account's
(404). After a failure of either the app fetches the list, so the row shows what the server has.
Against a server from before these two routes, which answers 404 without an error, the app does
what it did before: it un-ticks and ticks, and puts the ingredient on hand by hand (`PUT
/api/pantry/on-hand/{id}`). A staple is never in the group: an ingredient made a staple after it
was bought is always at home, and when it is on offer it shows as stock-up advice, the moment to
buy more.

**Ticks in the app.** A tick shows at once and is stored on the device before it is sent
(`ShoppingCache`); it is taken off when the server has it. The app keeps the list's state for as
long as it is logged in (`ShoppingSession`), so leaving the Shopping screen for a recipe, another
tab, the pantry or the offers cancels nothing, and coming back shows the same week, scroll position
and opened groups. Requests go out one at a time, each with 10 seconds to answer. Without an answer
or with a server error (5xx) the tick stays stored and the list says it is offline. Stored ticks of
every week go out, oldest week first, before any list is fetched, also after the app was closed: a
buy-ahead tick made offline last week still reaches the server. A stored tick the server fails on (a
5xx) stays stored for the next time while the rest and the list still go out, so one tick the server
keeps failing on never stops the lists from loading; only a stored tick without any answer holds the
list back. A tick the server refuses (another 4xx than 401, 408 or 429) goes back, and the snackbar
says why. Logging out (or a login the identity
provider no longer renews) forgets the stored lists and their unsent ticks, and an answer that
arrives after that stores nothing, so they never reach another account's list.

**The list keeps itself current**, since a second phone or the web client on the same login may
tick too. Each list from the server is laid under the ticks not yet sent, as a tick's answer is, so
a refresh never flips back a row just tapped, and none is sent while another request is under way.
How often the app asks:

| State | Requests |
|---|---|
| Shopping screen shown, app in front, shopping mode | the list every 30 s (two people in one shop) |
| Shopping screen shown, app in front | the list every 2 minutes |
| Offline (or the list failed to load), screen shown, in front | a check every 30 s: the stored ticks, oldest first, then the list. Without a connection that is one attempt, as the first tick without an answer holds back the rest and the list; when the server answers with errors (5xx) or with an answer the app cannot read, every stored tick goes out and then the list, and a quiet line above the progress says how many ticks have not reached the server yet. On success the offline line goes, without Retry. A list the server refused (a 4xx) is not tried again by itself, only on Retry or when the screen or the app comes back |
| App comes to the front (start, back from another app or browser tab) | stored ticks, then the list shown, or else the current week's list, so it is on the phone in a shop without reception even if it was not opened |
| Leaving the Plan tab | the current week's list (the plan may have changed), unless the Shopping screen shows |
| Another screen, or the app behind | nothing |

A list is a few kilobytes gzipped; 30 s applies only with the screen kept on in shopping mode.
Stored lists of weeks more than four weeks back are dropped on start, unless ticks of theirs still
wait for the server.

## Own items on the shopping list

Things no recipe needs, such as coffee or toilet paper, can be put on the list by hand, so the
household needs no second list: `POST /api/plans/{weekStart}/shopping/extras` with
`{"name": "Coffee"}` (trimmed, 1 to 80 characters), and `DELETE
/api/plans/{weekStart}/shopping/extras/{id}` to take one off for good. Both answer with the week's
shopping list. They are per account (table `shoppingExtra`); another account's id is a 404.

**Which weeks.** An own item is on the list of every week from the one it was added in, until it
is ticked off. Ticked off in a week (the usual `PUT /api/plans/{weekStart}/shopping` with its key),
it belongs to that week: ticked there, and on no list before or after it. Un-ticking it there puts
it back on every list from the week it was added in. Ticking it on a list it is not on (a week
before it was added, or after it was bought) is refused (409); un-ticking it there changes nothing.
Own items show also when the week has no meals.

**On the list** an own item is a row of `ShoppingList.items` with key `x:<id>`, no amounts and no
recipes, so apps from before own items (v0.1.2) show and tick it. When its name is an ingredient of
the account's catalogue, found the way a recipe line's text is (a link or alias, the name, a plural
s), it takes that ingredient's shop section, offers, picture and on-hand mark; it stays a row of
its own right after the meals' row for the same ingredient (it sorts by the ingredient's name, also
when it was given an alias such as "Olijfolie"), so the meals' amounts stay readable, and
stock-up advice leaves that ingredient out, as it is on the list already. Any other name is in
section Other, at the end of the walk.

**Limits.** An account has at most 100 own items that are not ticked off; the 101st is refused (409)
until one is ticked off or deleted. The limit holds when adds arrive at once: each is counted after
it is stored, under the database's write lock, and taken back if it is one too many. Amounts, a
section chosen by hand and items that come back every week are left out until the plain version has
been used.

**In the app** the Shopping screen has an "Add an item of your own" field under the view switch (also
for a week without meals): Enter or the plus button adds it, the field clears and keeps the focus. An
own row has a remove button at its end (hidden in shopping mode, where the row is one tap target).
Adding and removing need a connection: while the list shown is the stored copy of a lost
connection the field is off and says so. A refusal (such as the 101st open item) shows the server's
message and keeps the typed text. Ticking an own item works offline like any row.


## Shop sections

The shopping list is sorted in the order you walk a Dutch supermarket, modelled on Albert Heijn:
fruit and vegetables, bread, cold meats and cheese, sauces, the chilled wall (meat, fish,
vegetarian, dairy and eggs, ready meals), the aisles (pasta, rice and world foods; tins and jars;
breakfast and spreads; baking; snacks and sweets; drinks), frozen, household and non-food, and
"Other" last. The list of sections is `ShopSection` in `shared`; within a section items are sorted
by name, and ticking an item off does not move it. One route serves every store: Jumbo is laid
out much the same, Lidl a little differently (bakery at the entrance, a non-food middle aisle).

In the app each section's header carries an icon (`sectionIcon` in `composeApp`, chosen so that
neighbouring sections differ); the app finds it by the section's title, and shows the "Other" one
for a title it does not know.

An ingredient's section comes from, best first:
1. **A section set by hand** in the app (Recipes → Link ingredients → Shop sections, or
   `PUT /api/ingredients/{id}/section`). Per account, like the rest of the catalogue; it stays until
   cleared, whatever later lookups find.
2. **AH's category for the product its regular price comes from** (`StoreSections`). Shared, like
   the price, because both are kept per ingredient name. Main categories map to sections one to one
   ("Kaas" to cold meats and cheese, "Diepvries" to frozen). AH files a few things where its shops
   don't put them, so these sub-categories override the main one: "Tarwebloem" and "Bindmiddel"
   (under "Bakkerij") and "Suiker, poedersuiker" (under "Koffie, thee") go to baking,
   "Tomatenblokjes" and "Kidney bonen" (canned, under "Groente, aardappelen") to tins and jars,
   "Kokosmelk" (under "Zuivel, eieren") to world foods. A category not in the list falls through.
   Prices looked up before categories were kept have none; the database migration that added them
   makes the next scheduled check look all prices up again.
3. **The NEVO food group** of the linked food (`FoodSections`). Groups are a nutrition grouping,
   so words of the food's English name refine them: "tinned", "canned" or "pickled" vegetables,
   fruit, legumes and fish go to tins and jars, "frozen" anything to frozen, "fresh" herbs and
   ginger "root" to produce, "flour" and "starch" to baking, "muesli" to breakfast, "oil" to
   sauces and oil (butter stays with dairy).
4. **Other**, at the end of the list.

Negligible ingredients (spices and the like) are never on the list, so they have no section to set.

What AH's app API offers here, checked on 2026-11-15 with the anonymous token and eleven requests:
- The product search (`/mobile-services/product/search/v2`) gives every product a `mainCategory`
  and a `subCategory` (e.g. "Koffie, thee" › "Suiker, poedersuiker"); nothing finer, and no field
  saying where in a store a product is (no aisle, shelf or route position).
- The product detail (`/mobile-services/product/detail/v4/fir/{webshopId}`) and the category list
  (`/mobile-services/v1/product-shelves/categories`) that older third-party clients used both
  answer 404 now.
- The AH app sorts "Mijn lijst" in a chosen store's walking order, but that lives in the logged-in
  shopping list; no anonymous endpoint for a store's route was found, so the route is fixed here.

## Product pictures

Offers and shopping-list items carry the URL of the store's own picture (`Offer.imageUrl`,
`ShoppingItem.imageUrl`); the server stores only the URL, never the picture, and the apps load it.
Referencing the stores' pictures in this private, non-commercial app is the same kind of use as
reading their offers; they are never copied or redistributed.

Where the URLs come from:

| Store | Field | Renditions | Used |
|---|---|---|---|
| AH | `images[]` of a product (offers and product search) and of a bonus group | each with `width`, `height`, `url`. A product's picture (also a group that is one product): five squares, 800, 400, 200 px WebP, 48 px GIF, 80 px JPEG, under `/dam/product/` (`rendition=200x200_WEBP`). A group made up for the week ("Alle …", about half the groups): its own picture of several products, 200 and 708 px WebP, under `/mam/bonus/` (`rendition=200px`) | the narrowest at least 200 px wide (a 200 px WebP, 10 to 25 KB), else the widest |
| Jumbo | `image` of a promotion in the offers page payload | one, 300 × 300 PNG (about 130 KB) | that one |
| Lidl | `image` of a tile's `data-grid-data` | `imageList_V2[].variants[]` per ratio (4:3, 3:4), each `smallUrl` 380 × 285 (about 160 KB PNG) and `mediumUrl` 772 × 579 (about 630 KB); `image` is the 4:3 `smallUrl` | `image` |

A shopping-list item shows the picture of its best offer in the plan's week (the one that saves
most, the rule the app uses for the row's badge), or else the picture
of the AH product its regular price comes from (stored with the price, shared like it), or none.
An account that set the ingredient's price by hand gets no product picture: that price is not the
product's, and a hand price often means the product found was the wrong one. Offers stored before
pictures existed get theirs at their store's next fetch (within a day), prices at the next weekly
lookup (`POST /api/prices/refresh` does it now).

The apps load these URLs, so the server keeps only https URLs on the stores' image hosts and paths
(`StoreImages`): `static.ah.nl/dam/product/` and `/mam/bonus/`, `www.jumbo.com/INTERSHOP/static/`,
`www.lidl.nl/assets/`. Anything else a page might hold (another host, plain http, `..` in the path)
is dropped, so a changed or hostile page cannot make the apps fetch an arbitrary address.

What the image hosts allow, checked on 2026-11-15 with a handful of plain GETs (no referrer, no
cookies; an `Origin` header to see CORS):

| Host | Without referrer | CORS (`Access-Control-Allow-Origin`) | Stable for a week |
|---|---|---|---|
| `static.ah.nl/dam/product/` | yes (tried with OkHttp's `okhttp/…`) | `*` | yes: an asset id and a `revLabel` in the URL, nothing signed or expiring, `max-age` about nine days |
| `static.ah.nl/mam/bonus/` | yes (tried with OkHttp's `okhttp/…`) | `*` | for the week: the week in the name (`…_WK46_ALLE_AH_GREENFIELDS_STOOFVLEES`) and a `version` hash, nothing expiring; but sent with `max-age=0`, so an app must cache it regardless of the headers to have it offline |
| `www.jumbo.com/INTERSHOP/static/` (Akamai Image Manager) | yes for a browser, Android's own (`Dalvik/…`), `ktor-client` or an app's own user agent; **the connection is reset for OkHttp's default `okhttp/…`** | none | yes: a fixed path per week and promotion (`…/Week%2046/Weekaanbiedingen-Week-46_56.png`), no query string, `max-age` about 22 days |
| `www.lidl.nl/assets/` (myracloud) | yes (tried with OkHttp's `okhttp/…`) | `*` | yes: content-addressed names (`gcp` + 32 hex), `Cache-Control: max-age` one year, `Last-Modified` two months back |

Every AH product and bonus group, Jumbo promotion and dated Lidl food tile in the responses
checked had a picture: all 111 AH offers (15 products, 96 groups), 15 AH search results, 270 Jumbo
and 45 Lidl offers (`LIVE_OFFERS=1` test, which now also counts the pictures kept).
Jumbo's is the promotion's picture, so for "Alle … of …" promotions it shows several products. AH
serves its WebP renditions with `Content-Type: image/jpeg`, so a loader must recognise the format
from the bytes (Android's decoder and Skia do). AH's anonymous token endpoint refused every request,
the server's own included, from 02:41 to about 03:00 UTC on 2026-11-15 (500, or 400 "Client not
supported"), then worked again; during such an outage offers and prices keep their previous values.

What the apps do (Coil 3 with the Ktor network fetcher, `ProductPictures.kt`, `productImageLoader`):
- **Android loads directly from the stores**, with a 50 MB disk cache so the shopping list keeps its
  pictures offline. The loader sends its own user agent (`MealPlanner (Android)`), not OkHttp's
  default, or Jumbo's pictures fail, and ignores the stores' cache headers (Coil's default strategy
  already serves whatever is on disk), so AH's `max-age=0` bonus pictures stay available offline.
  Direct loading tells the stores' CDNs the phone's IP address and which pictures it shows (not who
  is logged in); Settings → Appearance → "Show product pictures" turns it off.
- **The web client loads AH and Lidl pictures directly** (they allow CORS) **and shows the product's
  first letter for Jumbo's**, which sends no CORS headers; the app decides by host
  (`loadablePictureUrl`). A server pass-through (`/api/image?url=…`, accepting only URLs that pass
  `StoreImages`, fetching with its own user agent, caching) would fix that but also makes the server
  fetch for every viewer; it is not built. Passing every picture through it would also keep the
  stores from seeing the phone's address, if that is ever wanted on Android.
- A row shows the picture in a fixed 48 dp square (56 dp in shopping mode) with the first letter of
  the product on it until the picture has loaded, when it fails and when there is none, so rows do
  not change height. Ticked-off shopping items show it dimmed.

## Recipe pictures

A recipe from an account's cooking site carries the picture its page shows (`RecipeSummary.imageUrl`,
for example `/api/recipes/12/image?v=3f2a9c0d1e2b4a5f`); recipes made or generated in the app have
none. The cooking site is a Hugo site, and the sync takes the first of:

1. the front matter's featured picture: `featured_image`, `image`, `cover` (or `cover.image`), or the
   first of `images`;
2. the first picture in the page's text: the geekdoc theme's `{{< img name="…" >}}`, Hugo's
   `{{< figure src="…" >}}`, or Markdown's `![…](…)`;
3. the first picture file, by name, in the page's bundle (the folder of an `index.md` page).

A reference is the name of one of the front matter's `resources` (what `img` refers to), a path
relative to the page's folder, or a path from the site's root, looked up in `static/` and then
`assets/`. The owner's site uses the second form in one page bundle (`recipes/ala_mario`: two
`resources`, shown with `img`); the others have no pictures yet. Pictures on other websites are not
fetched, and nothing outside the checkout of the account's site is read, also not through `..` or a symbolic link.

The server keeps a copy at most 800 pixels on its longest side (smaller ones keep their size), turned
upright as its EXIF orientation says, as a JPEG (quality 0.82, typically 50 to 150 KB), in
`<MEALPLANNER_IMAGE_DIR>/<account>/<recipe>.jpg`. The picture is not cropped: its aspect ratio is the
photo's. It uses only the JDK's `javax.imageio`, which reads JPEG, PNG, GIF and BMP; WebP, HEIC and CMYK
JPEGs, files over 20 MB and pictures over 60 megapixels are skipped, with one warning in the log per
version of the file, and the recipe imports without a picture. A sync never fails because of a
picture.

Each sync compares the SHA-256 of every picture file with the one it last read: a changed picture is
copied again (and gets a new `v`), an unchanged one is left alone, and the copy of a recipe that is
gone, or whose page no longer shows a picture, is deleted. Setting another cooking site deletes all of
the account's copies until the new site is synced. Accounts that read the server's cooking site (issue 208)
read its one clone, but each keeps copies of its own, under its own recipe ids: a copy is about 130 KB (the
one photo on the site in November 2026: 3.2 MB as taken, 134 KB as copied, 0.12 s to scale), so a copy per
account costs little, and a picture is served by the same check as any other: the account's own recipe. `GET /api/recipes/{id}/image` serves the copy to the
account that owns the recipe (404 to any other), with
`Cache-Control: private, max-age=31536000, immutable`: the URL changes when the picture does. It needs
the login like every other call, so the apps fetch it with their access token rather than handing the
URL to an `<img>` or an image loader that does not send it.

## Nutrition: NEVO

NEVO-online 2025/9.0 (RIVM) can be downloaded free of charge from
<https://www.rivm.nl/nederlands-voedingsstoffenbestand/gebruik-nevo-online/download-bestand>
after accepting the conditions of use, so the download is a manual step.

- The data may only be used unchanged and with the reference
  "NEVO-online version 2025/9.0, RIVM, Bilthoven". Show that reference in the app.
- Do not commit the dataset to this repository; the server imports it from a local file.

The main file `NEVO2025_v9.0.csv` is UTF-8, `|`-separated, with decimal commas and one row per
food (2,328 foods). Values are per 100 g, except for foods marked `per 100ml` (mostly drinks).
An empty cell means "no value", not zero.
