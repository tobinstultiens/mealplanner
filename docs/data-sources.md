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
A store that fails keeps its previous offers and reports the error under `/api/offers`. Parsers are
tested against trimmed real responses in `server/src/test/resources/offers/`; the live check is
`LIVE_OFFERS=1 ./gradlew :server:test --tests '*OfferSourcesTest.liveStores'`.
On 2026-11-13 it returned 111 AH, 137 Jumbo and 39 Lidl offers.

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
   `currentPrice`, `priceBeforeBonus`, `bonusStartDate`, `bonusEndDate`, `mainCategory`, `subCategory`.

The product search with `filters=bonus%3Dtrue` also works but is capped at 10,000 results.
"Volume voordeel" entries (3–5% off multi-packs) are flagged as bonus and should be filtered out.

### Jumbo
The old `mobileapi.jumbo.com` endpoints return 404. The offers page embeds its data in
`<script id="__NUXT_DATA__">`: a flat JSON array in which objects refer to other entries by
index. Promotion objects have `title`, `subtitle`, `tags[].text`, `start.iso`, `end.iso`, `url`.
A GraphQL endpoint (`www.jumbo.com/api/graphql`) answers too, but its queries are not explored yet.

### Lidl
Each product tile on the offers page carries a `data-grid-data` attribute holding HTML-escaped
JSON: `fullTitle`, `price.price`, `price.oldPrice`, `price.packaging.text`, `storeStartDate`,
`storeEndDate` (Unix seconds), `category`, `keyfacts.wonCategoryPrimary`. The page holds only
the week's tiles (47 on 2026-11-13, 46 on 2026-11-15) and makes no paging request. Only the
tile categories `Food` and `F+V` are used. Tiles without `storeStartDate` are regular-priced items
with a Lidl Plus app discount, and are skipped. The offers page address
(`/c/aanbiedingen/a10008785`) may change; the fetch reports an error when it finds no tiles.

The page can also silently stop showing new offers while its tiles still parse. After each
fetch the server logs a warning (`LidlGuard`) when every Lidl offer has already ended, or when
it is Friday or later and no Lidl offer runs into next week. The store keeps its offers. The
warning is only logged for now: apps released before it fail on fields they do not know, so it
will be shown in the app's offer source line once apps that tolerate new fields are installed.

Looking for offers beyond this page (2026-11-13 and 2026-11-15, plain page fetches only) found
none, so the page is the whole list as far as can be found:
- `/q/api/search` and `/q/api/category` return no items for the offers category.
- The page already holds both of the week's batches: on 2026-11-15, 45 food tiles (39 `Food`,
  6 `F+V`), all dated, 32 from Monday 16 and 13 from Thursday 19, all until Sunday 22 November.
  Offers starting later in the week are therefore not missed.
- `/c/store/aanbiedingen/a10008785` is the same page (46 tiles, same products).
- Next week's offers are not published yet: the folder `hah-wk48-2026` does not exist, and the
  current folder `hah-wk47-2026` (`/l/folders/…/view/flyer/page/1`) is a script-rendered viewer
  without tiles.
- Other pages linked from the offers page: `/c/pre-black-week/s10080606` has 48 tiles, none with a
  food category or store dates (webshop non-food); the supermarket assortment pages
  (`assortiment-supermarkt-in-groente-en-fruit`, `-in-vlees`) hold no offer tiles (0 and 2
  non-food). The navigation links only to non-food campaign pages.
Next week's offers should appear on the same page once they start, so the fetch picks them up
without changes.

## Regular prices (AH)

Offers only say how much is taken off; to know what a meal costs, and so which offers make it
cheap, the server keeps a regular price per kg for each ingredient (`PriceRepository`, `prices/`).

- Once a week it searches AH's product search (`/mobile-services/product/search/v2?query=…`, same
  anonymous token as the offers) for up to four of the ingredient's names, Dutch ones first.
  `POST /api/prices/refresh` starts a lookup now; it takes a few minutes and `GET /api/prices`
  shows the result with the product each price came from.
- A product counts for an ingredient when the offer matcher would give it that ingredient, with
  two extra rules: words after "met", "with" or "in" are a side ingredient, and prepared products
  (titles with a word ending in "saus", "mix", "sap", "smaak", "oven" and so on) don't count unless
  the ingredient is itself one ("sojasaus").
- `priceBeforeBonus` is the regular price, also while the product is on offer. Per kg comes from
  the pack size (pieces need the ingredient's weight per piece), or else from the unit price text.
- The product must sit in an AH aisle where the ingredient's kind of food is sold: its NEVO food
  group maps to AH main categories (`AhAisles`; vegetables to "Groente, aardappelen", canned goods
  to "Pasta, rijst, wereldkeuken", sugar to "Koffie, thee"). This keeps out flavoured products
  from other aisles such as "croissants cinnamon" or cumin cheese.
- Of the matching products, only those in the category holding most of them count (stray matches
  such as "croissants cinnamon" sit elsewhere); of those, the middle of the three cheapest per kg is used.
- A price set by hand on the ingredient (`pricePerKg`) wins. Ingredients without a price are
  costed at the median price per kg and named in the recipe's cost.

With each price the lookup keeps the product's `mainCategory` and `subCategory`; they also give the
ingredient's shop section (below).

An offer becomes a share of the regular price (`Discounts`): "25% korting" pays 0.75, "1+1 gratis"
0.5, "2e halve prijs" 0.75; stated prices ("2 voor 3,99", "voor 4,49", Lidl's plain prices) are
compared with the regular price of a pack of the offer's size. Online-only deals are ignored. The
best offer per ingredient in the plan's week applies. The planner then prefers meals whose
calories cost less than the median (`WeekPlanner.savings`), so offers pull those meals in.

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
