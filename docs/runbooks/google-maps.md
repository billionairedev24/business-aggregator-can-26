# Google Maps Platform — addresses (S-47)

The consumer site's Location screen (design 06 `location`) suggests Canadian addresses as the person types, and the
header's location pill names the device's position ("Beltline, Calgary"). Both go through the api's
`PlacesAutocomplete` port; **the key never reaches a browser** — the browser calls `/api/v1/geo/*` through the
consumer-bff and the api calls Google.

| what | Google API | api endpoint |
|---|---|---|
| suggestions while typing (restricted to Canada, address types, 50 km bias around the visitor) | Places API (New) `POST /v1/places:autocomplete` | `GET /api/v1/geo/autocomplete?q=&session=&lat=&lng=` |
| the chosen address (street, city, province, postal code, coordinates) | Places API (New) `GET /v1/places/{id}` (field mask `id,formattedAddress,addressComponents,location`) | `GET /api/v1/geo/places/{placeId}?session=` |
| name the device's position | Geocoding API `GET /maps/api/geocode/json?latlng=` | `GET /api/v1/geo/reverse?lat=&lng=` |

Markets, zones and the waitlist (`GET /api/v1/geo/markets`, `/resolve`, `POST /waitlist`) are Northline's own data
(`region.regions`, `region.zones`, `region.waitlist`, PostGIS) and need no Google call.

**Status:** the Google adapter (`region.adapters.GooglePlaces`) was written from Google's documentation and tested
against WireMock only. **It has never run against Google** — no Google Cloud project exists yet.

## 1. The Google Cloud project (once per environment)

1. Create (or reuse) a Google Cloud project per environment: `northline-maps-dev`, `-staging`, `-prod`. Attach a
   billing account (Maps Platform needs one even inside the free tier). Maps Platform is a global service; no personal
   data is sent to it except the typed address text and, for reverse geocoding, the device's coordinates.
2. **APIs & Services › Library:** enable **Places API (New)** (`places.googleapis.com`) and **Geocoding API**. The
   legacy Places API is not used.
3. **Quotas** (APIs & Services › Places API (New) › Quotas): cap *Autocomplete requests per day* and *Place Details
   requests per day* at what the budget allows (start with 20 000/day in prod, 1 000/day in dev), same for the
   Geocoding API. A capped day answers 429, which the api turns into "We couldn't look up addresses right now."
4. **Budget alert:** Billing › Budgets & alerts on the project (e.g. 50 %, 90 %, 100 % of the monthly budget).

## 2. The server key

1. **APIs & Services › Credentials › Create credentials › API key.** Name it `northline-api-<env>`.
2. **API restrictions:** *Restrict key* → Places API (New) and Geocoding API only.
3. **Application restrictions:** *IP addresses* → the cluster's egress addresses (NAT gateway / Cloud NAT / Azure NAT
   public IPs from Terraform's network outputs). Leave it unrestricted only in dev while the egress IP isn't fixed.
4. Store it in the secrets manager as **`google-maps-api-key`** (AWS `northline/<env>/google-maps-api-key`, Google
   Cloud `northline-<env>-google-maps-api-key`, Azure Key Vault `google-maps-api-key`) — Terraform creates the empty
   secret (`secret_env` in `infra/terraform/stacks/<cloud>/main.tf`); the chart maps it to `GOOGLE_MAPS_API_KEY`
   (`externalSecrets.secretNames`, `apps.api.secretEnv`; required in staging and prod).
5. Set `PLACES_PROVIDER=google` (values-staging/prod set it; dev keeps `local` unless you add it to `configEnv` and
   `GOOGLE_MAPS_API_KEY` to `externalSecrets.optionalKeys`).

## Variables (api)

| variable | required | value |
|---|---|---|
| `PLACES_PROVIDER` | staging, prod (`local` is refused there at start-up) | `local` (fixture addresses) · `google` |
| `GOOGLE_MAPS_API_KEY` | with `google` (the api doesn't start without it) | the server key (secret) |
| `PLACES_RATE_LIMIT` | no (`60`) | address lookups per browsing session (the consumer-bff's guest id), else per caller address, per minute and api instance → 429 `rate_limited` |

## How the api calls Google

- **Session tokens:** the Location screen makes a random token per address search and sends it with every
  suggestion request and with the details request of the chosen address; the api passes it to Google, which bills the
  keystrokes and the details call as one session. A new search starts a new token.
- **Fewer calls:** no suggestion request below 3 characters; the screen waits 250 ms after the last keystroke.
- **Time-outs:** 3 s connect / read, no retries (a late suggestion is useless); failures answer 503
  `places_unavailable` and the screen says "We couldn't look up addresses right now. Try again in a moment."
- **The key:** Places calls send it in `X-Goog-Api-Key`. The Geocoding API only accepts it as `key=` in the query, so
  its errors are logged without the request URI.
- **Attribution:** the suggestion list shows "powered by Google" (Google's terms for autocomplete without a map).
- **What's stored:** nothing from Google. The chosen address is kept in the person's browser (`localStorage`
  `nl.location`); a waitlist entry keeps only the market, the user id or a guest's own email, and the language.

## Local and tests

`PLACES_PROVIDER=local` (the default) uses `FakePlaces`: design 06's four "1204 17 …" Calgary addresses, one address
per market (Edmonton, Airdrie, Red Deer pilot, Lethbridge waitlist), and Toronto / Montréal / Vancouver for the
waitlist. Every typed word must start a word of the address ("1204 17", "queen st"). Reverse geocoding answers the
nearest fixture within 3 km, else 404 (the pill then falls back to the nearest market). Tests: `GooglePlacesWireMockTest`
(request shape, parsing, errors), `GeoApiTest` (the endpoints with the fake, markets, zones, waitlist, rate limit).

## Markets and zones

`V117__geo_markets_zones.sql` seeds Alberta (live: Calgary, Edmonton, Airdrie; pilot: Red Deer; waitlist: Lethbridge,
Medicine Hat), British Columbia (pilot: Vancouver), Ontario and Québec (waitlist), and approximate delivery zones in
Calgary, Edmonton and Airdrie. A market covers addresses within `radius_km` of its centre (the nearest covering centre
wins); zones are polygons inside it. Until the console's Regions screen exists, change them with SQL, e.g.
`update region.regions set stage = 'live' where id = 'mkt-red-deer';`.

## Operations

- **Key leaked:** create a new key with the same restrictions, put it in `google-maps-api-key`, restart the api
  (External Secrets refreshes the Secret within its interval), then delete the old key.
- **429 / quota:** raise the daily quota or wait for midnight Pacific; the pill falls back to the nearest market and
  people can still type an address later.
- **"REQUEST_DENIED" in the api log:** the key isn't allowed for that API (restrictions) or billing is off.

## Checking an environment

- [ ] Places API (New) and Geocoding API enabled, billing attached, quotas and a budget alert set
- [ ] `google-maps-api-key` holds a key restricted to those two APIs (and to the egress IPs outside dev)
- [ ] `PLACES_PROVIDER=google`; the api logs `Places: Google Places API (New) + Geocoding API` at start-up
- [ ] On the consumer site, `/location`: typing "1204 17 Ave" lists Calgary addresses with "powered by Google";
      choosing one shows its postal code, market and zone
