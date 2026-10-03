# Regions: provinces, markets and the region model (S-134)

Northline starts in Alberta but is built for every province (DECISIONS "Region-neutral by design"). Nothing in code
names a province, city or time zone: every place fact comes from the **region model** — rows in `region.regions`
(provinces and the city markets inside them) and `availability.service_zones`, with configuration on top. Opening a
province is data and, at most, configuration; never a code change. The console's Provinces screen (S-84) edits stages, markets and delivery
zones; the rest is SQL (a migration for every environment, or by hand for one).

## 1. What the model holds

| fact | where | used for |
|---|---|---|
| launch status (`off` · `waitlist` · `pilot` · `live`) | `region.regions.stage` (province and market rows) | served provinces, onboarding's province list, the Location screen, delivery markets (live markets) |
| time zones | `region.regions.time_zones` (province: first = default; market: its own, else the province's) | a business's hours, slots, cut-offs, holidays, reports, payouts, quiet hours; a market's delivery runs |
| statutory holidays | `region.regions.holidays` (codes; dates computed by `region.domain.HolidayRule`) | Availability › Time off & holidays, closed days in slots |
| tax regime | `region.tax_profiles` via `region.regions.tax_profile_id` | quote tax (`TaxRates`), the Location screen; must agree with S-21's Stripe Tax rates (`RegionsApiTest`) |
| privacy law | `region.regions.privacy_law` (`pipeda` · `ab_pipa` · `bc_pipa` · `qc_law25`) | consumer sign-in copy, Studio compliance copy |
| business registries | `region.regions.registries` (adapter keys = `merchants.registry_checks.source` codes) on the province (corporate registry) and the market (municipal licences) | which registry adapter checks a business; none = a Northline agent |
| names | `region.regions.name_i18n` (`en`, `fr`, and `fr_in` / `fr_of`: "au Québec", "du Québec") | every message that names a place (`{province}`, `{provinceIn}`, `{provinceOf}`) |
| markets | `region.regions` rows with `kind = 'market'`: city, centre, radius, status | delivery markets, the shop's fallback market, a business's zone |
| service zones | `availability.service_zones` with `market_id`, `sort`, `default_on` | the zones a provider of the market can pick (Booking rules), the ones it starts with |

V130 fills every province and territory (zones, holidays, privacy law, tax, French forms); V131 makes **Alberta**
live with its Calgary, Edmonton and Airdrie markets, the Calgary licence registry and Calgary's service zones — the
first *configured* region. `GET /api/v1/geo/regions?lang=` serves the model to the web apps.

### From the console (S-84)

Admins open **Provinces** in the platform console (`/provinces`): a province's and each market's stage (with the
province's code or the market's name typed to confirm), new markets (centre and radius), delivery zones with a GeoJSON
boundary, and the courier model. Going live needs the checklist on screen (tax profile, holidays, a registry key on
the province — `manual` when an agent checks records by hand —, and a market with a zone). Every change is in the
platform audit log (`region.*`) and is served at once by the instance that made it, by the others within
`REGION_CACHE_TTL`. Service zones (`availability.service_zones`), time zones and registry keys are still SQL.

## 2. Configuration (`northline.region.*`)

| variable | default | meaning |
|---|---|---|
| `REGION_PROVINCES` | (none) | extra served provinces on top of the live rows, `CODE[=Zone/Id],…`; a zone here replaces the row's. S-44's `SEARCH_MARKETS` is read when unset |
| `REGION_DEFAULT_PROVINCE` | `AB` | the province used when nothing is known (a business without a province, the shop's fallback market, the default on forms). `SEARCH_DEFAULT_MARKET` is read when unset |
| `REGION_PLATFORM_ZONE` | `America/Edmonton` | work that belongs to no market: nightly jobs (`@Scheduled` zones), support hours, account "member since", tax reporting quarters. api, worker and auth |
| `REGION_CACHE_TTL` | `60s` | how long each api instance keeps the rows before reading them again |
| `EMAIL_TIME_ZONE` | `REGION_PLATFORM_ZONE` | dates and times in emails |
| `REGION_FRENCH_FIRST` | (none) | S-116: province codes or market ids that are French-first with French listing text required, on top of the rows' `french_first` / `french_listings` (V315) — [i18n.md](i18n.md) |

Kitchen visits (S-120, [pilot-onboarding.md](pilot-onboarding.md#5-kitchen-visits)): `region.regions.kitchen_visit` (`required` | `optional`; a market's `NULL` = its province's, a province's `NULL` = optional) decides whether a kitchen there is approved only after a passed visit. A pilot market is held at stage `pilot` until launch; its pilot businesses are hidden from search until an admin sets it `live`.

Language rules (S-116, [i18n.md](i18n.md)): `french_first` and `french_listings` on each province row (a market row's
`NULL` = its province's) decide where interfaces default to French, the Terms come in French first, receipts are
French and listings need French text. V315 seeded them from each province's first official language.

Web: the Studio build takes `VITE_NL_PLATFORM_TIME_ZONE` until the model answers; the consumer reads the platform zone
from the model and `NL_LEGAL_ENTITY` for its footer.

## 3. Opening a province (example: Saskatchewan, Saskatoon)

This is exactly what `SecondProvinceTest` does — no code involved.

```sql
-- 1. the province's status (its zones, holidays, tax, privacy law and names are already there since V130)
UPDATE region.regions SET stage = 'live' WHERE kind = 'province' AND province = 'SK';   -- or 'pilot' / 'waitlist'
-- 2. its markets (a city, a centre and radius; time_zones only when it differs from the province's first zone)
INSERT INTO region.regions (id, kind, parent_id, province, city, name_i18n, stage, center, radius_km, languages, sort)
VALUES ('mkt-saskatoon', 'market', 'prov-sk', 'SK', 'Saskatoon', '{"en":"Saskatoon","fr":"Saskatoon"}', 'live',
        'SRID=4326;POINT(-106.6700 52.1332)', 20, '{en,fr}', 1);
-- 3. the market's service zones (names are unique across markets: availability.service_zones keys on the name)
INSERT INTO availability.service_zones (name, city, centre, area, radius_m, market_id, sort, default_on) VALUES (…);
-- 4. optional: delivery zones (region.zones) for the Location screen, registry adapters the province or city has
UPDATE region.regions SET registries = '{<adapter key>}' WHERE id = 'mkt-saskatoon';
```

Rows are picked up within `REGION_CACHE_TTL` on every instance. Then check:

- `GET /api/v1/geo/regions` lists the province `live` and the market;
- a business there (`merchants.merchants.province/city`) shows its province's holidays and its market's zone in the
  Studio (`GET /api/v1/merchants/{id}` → `region`);
- search serves the province (`?market=SK`), delivery runs exist for the market (`GET /api/v1/public/shop?market=`).

Registries: a province or city without an adapter key has its records checked by an agent (console queue). Adding an
adapter for a new registry is code (an adapter, like `OpenCorporatesAlbertaRegistry`); pointing a province at it is
data.

## 4. Lint

`./gradlew build` fails on a province, city or Canadian time zone in a Java string literal or text block (Checkstyle
`RegionLiteral`; tests, seed tools, `HolidayRule` and the province-specific registry adapters are allowed in
`server/config/checkstyle/suppressions.xml`). `pnpm -r test` fails on the same in web `src/` code
(`packages/ui/src/regionLiterals.test.ts`, with its own allowlist). Seeds, fixtures and test data may keep Calgary.
