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
the first batch of tiles (47 when checked); paging through the rest is not explored yet. Only the
tile categories `Food` and `F+V` are used. Tiles without `storeStartDate` are regular-priced items
with a Lidl Plus app discount, and are skipped. The offers page address
(`/c/aanbiedingen/a10008785`) may change; the fetch reports an error when it finds no tiles.

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
