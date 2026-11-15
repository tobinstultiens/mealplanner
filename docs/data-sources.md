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

Only offers at the stores the account shops at count ("Stores I shop at", an account setting:
`GET`/`PUT /api/shops`; an account that never chose shops at every store). The plan's and the
recipes' costs, the planner's preference and the offers named to Claude when writing a recipe all
leave the other stores out. The shopping list still carries every store's offers, and the app
leaves out those of stores not in use, with their pictures, in every view of the list. The stores can be
chosen on the per-store list and under Settings, Shopping (one shared setting); logging out forgets the
stores shown until the next account's answer arrives.

The shopping list gives each item's regular price for the amount needed (`ShoppingItem.regularPrice`)
and each offer's share (`Offer.paidShare`, as above), so the app can show what a store's offers save:
the sum of regular price times the share taken off, for the items whose cheapest offer is there.
Regular prices are AH's, so for Jumbo and Lidl that saving is an estimate against AH's price, and the
app says so.

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
(`BuyAhead.provisional`): the dinners the planner could pick, a meal by calories, not disliked and
not planned this week (the planner avoids last week's recipes), one batch each. An ingredient of
them qualifies when it keeps, is on offer this week but not (as far as known) next week, is not a
staple and not on hand, and its saving is known. The meals saving at least €0.50 that way, at most
three, most saved first (`BuyAhead.choose`), give the list: what one batch of each needs, with the
meals named. No random choice is involved, so the list stays the same while it is ticked off.

**Stock up** (`ShoppingList.stockUp`, keys `stock:i<id>`): ingredients that keep and are a staple, or
that a recipe uses and keep for months (so not fresh meat), on offer now, not on hand and not on the
list already. One pack of the best offer each, with its saving; the six saving most (at least
€0.25 a pack), in walking order. Advice only: it never changes the plan.

**Ticked items go to the pantry.** Ticking a buy-ahead item puts its ingredient on hand from next
week (`onHand.fromWeek`), so this week's meals, list and recipe ideas still get their own amount and
next week's plan prefers the meals that use it (the planner's on-hand bonus); ticking a stock-up item
puts it on hand from this week. Un-ticking takes back only what that tick put there. Next week's
plan is not fixed in advance; only what was ticked carries over.

**Eating from it.** A ticked item is on hand in the amount the list advised buying (`onHand.grams`:
three batches of chicken for three meals, one pack of rice). Each meal of that week or later that
uses it is eaten from it when it is marked eaten: a dinner takes what it was cooked with, its
leftover lunch included (which then takes nothing more), and marking a meal eaten twice takes it
once. The item stays on hand until less than a gram is left, or until it is removed in the pantry,
which works at once as always. Without an amount (an item the list no longer advised, or one ticked
before amounts were kept) or when a meal's use of it can't be weighed ("to taste"), that meal uses
it up, as before. Marking an item on hand in the pantry makes it one set by hand, even if a tick put
it there: those stay until removed by hand, whatever is ticked or eaten.

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

A shopping-list item shows the picture of its best offer in the plan's week (the cheapest that
states a price, else the first, the rule the app uses for the row's badge), or else the picture
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
fetched, and nothing outside the account's checkout is read, also not through `..` or a symbolic link.

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
the account's copies until the new site is synced. `GET /api/recipes/{id}/image` serves the copy to the
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
