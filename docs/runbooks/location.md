# Location runbook — the consumer's "Deliver to", passive detection and the location chooser (S-190, S-192)

A location is **never required** (S-190): every page shows every live market, popular first, and only *ranks* by
nearness once a place is known. S-192 made the chooser Amazon's and added passive detection from the visitor's IP.

## Where the header's place comes from

In order (consumer web `features/location/sources.ts`, consumer app `src/location/DeliveryLocation.tsx`):

1. **Saved** — what the person chose in the location dialog: a saved address, a postal or ZIP code, a country, or
   "Use my current location". Web: `localStorage` `nl.location`, mirrored into the `nl.loc` cookie (no street in it)
   so the server's render already shows it. App: the key-value store.
2. **Default address** — signed in: the account's default address.
3. **Detected** — the place the api's visitor detector guessed from the request (`GET /api/v1/geo/visitor`, below).
   The consumer's server render reads it (the root route's loader), so the first HTML already says "Deliver to
   {city}" and the browser's first render agrees (no hydration mismatch). A guess never sets a province (the
   language and the privacy law follow only a chosen place).
4. **Nothing** — "Deliver to / Choose location"; every market, popular first.

The header line is Amazon's: "Deliver to {first name}" over "{city} {postal code}" when known, "Deliver to" over the
detected city (or only the country), "Deliver to" over "Choose location" otherwise. The browser's position is read only
when the person presses "Use my current location" inside the dialog.

## The location dialog

"Choose your location" with the line *Delivery options and delivery speeds may vary for different locations*:

- signed in, the saved addresses as cards (the current one outlined, "Default address"), "Manage addresses" →
  `/account?tab=addresses`; signed out, "Sign in to see your addresses";
- **or enter a postal or ZIP code** + Apply: any country (`GET /api/v1/geo/postal?code=`, validation-rules.md
  § Location). The places provider looks it up (`PLACES_PROVIDER=google`: the Geocoding API by components; `local`:
  the fixture codes, a few abroad); inside a live or pilot market the place gets that market's city and zone,
  elsewhere only its point (the reads rank the nearest markets first); a code nobody knows is kept as typed;
- **or deliver to another country**: the country alone (no city) — common countries first
  (`NL_COMMON_COUNTRIES`, comma-separated codes; unset = a general list), then every country by name;
- "Use my current location"; **Done** (spruce: the commerce gold is never a button).

Currency, tax, postal formats per country and the address book outside Canada are **S-192a**.

## Passive detection (the api's `VisitorLocator`)

`VISITOR_LOCATION_DETECTOR` (api, `northline.visitor-location.detector`):

| value | reads | needs |
|---|---|---|
| `none` (default) | nothing | — |
| `edge` | Cloudflare's visitor location headers `cf-ipcountry`, `cf-ipcity`, `cf-region-code`/`cf-region`, `cf-postal-code`, `cf-iplatitude`, `cf-iplongitude`; else the generic `X-Geo-Country`, `X-Geo-Region-Code`/`X-Geo-Region`, `X-Geo-City`, `X-Geo-Postal-Code`, `X-Geo-Latitude`, `X-Geo-Longitude` any other edge can add | a proxied Cloudflare record with "Add visitor location headers" on (or an edge setting `X-Geo-*`) |
| `geoip` | the client's address (the right-most public hop of `X-Forwarded-For`) in a MaxMind GeoLite2/GeoIP2 **City** database | `VISITOR_LOCATION_DATABASE` = the `.mmdb` file's path |

Every value is cleaned before use (a two-letter country, not Cloudflare's `XX`/`T1`; a place name ≤ 60 characters;
the permissive postal rule; a point in range) — a header a client forges only moves its own guess, which never filters,
prices or charges anything. The consumer's server render forwards the edge headers and the address chain to the api;
the browser's own call goes through the consumer-bff, which relays them.

The demo (`values-dryrun.yaml`) runs `edge`: it stays silent until Cloudflare sends the headers.

### Turning detection on for the demo — Cloudflare (recommended)

1. Cloudflare dashboard → the zone (`northline.ca`) → **DNS › Records**: set `demo` and `*.demo` (the A records of
   ovh.md § DNS) to **Proxied** (orange cloud).
2. **SSL/TLS › Overview**: encryption mode **Full (strict)** — the origin keeps its Let's Encrypt certificates, which
   Cloudflare then verifies. Never *Flexible* (it would talk HTTP to the Gateway, which redirects to HTTPS: a loop).
3. **Rules › Settings › Managed Transforms** (older dashboards: Rules › Transform Rules › Managed Transforms): turn on
   **Add visitor location headers** (all plans).
4. Check: `curl -s https://demo.northline.ca/api/v1/geo/visitor` (through the consumer-bff) answers your city with
   `"source":"edge"`; the header says "Deliver to {city}" on a fresh browser.

**Certificates with the record proxied.** cert-manager renews over HTTP-01: Let's Encrypt fetches
`http://<host>/.well-known/acme-challenge/<token>` and Cloudflare forwards it to the origin — keep it reachable:
no "Always Use HTTPS" redirect or WAF/Bot Fight rule in front of `/.well-known/acme-challenge/*` (or add a
Configuration Rule skipping them for that path), and leave **Full (strict)** on. A proxied wildcard (`*.demo`) is
fine on every plan for HTTP traffic. If renewals fail, set the records back to DNS-only for the renewal, or move the
issuer to DNS-01 with a Cloudflare API token.

### The alternative: keep DNS-only and use GeoLite2

If the records must stay **DNS only** (grey cloud):

1. Create a free MaxMind account, accept the GeoLite2 licence, and download **GeoLite2 City** (`.mmdb`). Never commit
   it or bake it into an image (licence; it changes weekly) — MaxMind's `geoipupdate` refreshes it.
2. Put it on a volume the api pods mount (for example a PVC with `geoipupdate` as a CronJob) and set
   `VISITOR_LOCATION_DETECTOR=geoip`, `VISITOR_LOCATION_DATABASE=/var/lib/northline/geoip/GeoLite2-City.mmdb` in the
   api's `env`. The api refuses to start in `geoip` without a readable file (the message names this runbook).
3. The client address is the right-most public hop of `X-Forwarded-For` (`ClientAddress`): right with the Gateway in
   front and no proxying CDN; behind Cloudflare it would be Cloudflare's edge — use `edge` there.

**Recommendation for the demo: Cloudflare** — no file to license, mount or refresh, the country even without the
managed transform, and Cloudflare's protection in front; the only cost is the SSL mode and the ACME path above.

## Locally

`VISITOR_LOCATION_DETECTOR=edge` in `server/.env`, then
`curl -H 'cf-ipcountry: CA' -H 'cf-ipcity: Testville' localhost:8080/api/v1/geo/visitor`. The api's tests run `edge`
(`GeoApiTest`); `make ui-shots`' mock api answers `{source: none}` (POST `/__mock/state?visitor={json}` sets a guess).
