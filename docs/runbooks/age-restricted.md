# Age-restricted purchases

Owner decision 2026-10-04 ([DECISIONS.md § 2026-10-04 — Age-restricted purchases](../DECISIONS.md)). Age verification
is driven by what is in the cart: a customer buying alcohol (or, once allowed, tobacco, vape or cannabis accessories)
proves their age once with photo ID, the courier or the counter checks photo ID again at handoff, and an order nobody
of age can take goes back to the business. Sellers list these items only with an approved licence. Everything that
depends on a place — minimum ages, hours, whether delivery or pickup is allowed — is region data; code names no
province.

**Counsel has confirmed none of the ages or hours below** ([counsel-questions.md H4](../compliance/legal/counsel-questions.md)).
They were taken from public summaries because the official statute sites could not be reached from the build machine.

## Contents

1. [At a glance](#at-a-glance)
2. [Restricted categories](#restricted-categories)
3. [The age table (region data)](#the-age-table-region-data)
4. [Sellers' licences](#sellers-licences)
5. [The customer's ID check](#the-customers-id-check)
6. [The ID check at handoff](#the-id-check-at-handoff)
7. [Refusals, returns and refunds](#refusals-returns-and-refunds)
8. [Trust and safety](#trust-and-safety)
9. [Retention and privacy](#retention-and-privacy)
10. [App stores](#app-stores)
11. [Configuration](#configuration)
12. [Operations](#operations)
13. [Never run for real](#never-run-for-real)

## At a glance

| step | where | what happens |
|---|---|---|
| category | console › Taxonomy | a category (or its group) carries an age class: `alcohol`, `tobacco` (tobacco and vaping products), `cannabis` (cannabis accessories) — `catalogue.category_age_classes`, V341 |
| licence | Studio › Compliance › Restricted licences; console › Listing vetting › Licences | the business sends its licence number, the document and the expiry per class; staff approve or reject; restricted listings stay hidden until a licence is approved and are hidden again when it expires |
| listing | Studio editors, console vetting | a product in a restricted category (or a dish marked alcohol) needs the licence (409 `licence_required`) and is always reviewed by a person (`AGE_RESTRICTED` vetting flag; `AGE_CLASS_MISMATCH` when the words say alcohol or tobacco in another category) |
| checkout | consumer web (cart, food checkout), consumer app (Checkout) | the delivery province's minimum age for the classes in the cart; the hours; whether delivery/pickup is allowed; the customer's ID check must be done — 409 `age_verification_required`, `age_under_minimum`, `restricted_hours`, 422 `age_rules` |
| handoff | courier app (door), Studio kitchen display (counter) | three confirmations — government photo ID checked, the name matches the account holder, of age — or a refusal reason; recorded in `restricted.handoff_checks` |
| refusal | api | goods: a return stop to the shop, every line refunded, the delivery fee kept; food: the alcohol dishes refunded, the rest charged |

## Restricted categories

- Seeded classes (V341): `shop.restricted.alcohol` → alcohol, `shop.restricted.tobacco-and-vape` → tobacco,
  `shop.restricted.cannabis-accessories` → cannabis, `food.service.alcohol-with-food` → alcohol. A leaf inherits its
  group's class; a seller can't lower it (the class is never stored on a listing).
- **Tobacco/vape and cannabis accessories stay banned** (`northline.catalogue.banned-categories`, unchanged): the App
  Store and Google Play don't allow apps that facilitate their sale, and counsel has not answered H2. Their age rows
  exist so the rules are known if that changes — the apps must hide those categories first
  ([mobile-release.md § Distribution](mobile-release.md#distribution-and-age-restricted-goods)).
- Staff change a category's class in the console (Taxonomy › a category › Age restriction; audited as
  `catalogue.category_age_class`): `PUT /api/v1/console/taxonomy/categories/{id}/age-class` `{ageClass}` (null clears it).
- Kitchens mark a dish "Contains alcohol" in the menu editor (`food.menu_items.age_class`); it needs the kitchen's
  alcohol licence. Booking services are not age-restricted.

## The age table (region data)

`region.age_rules` (V340): one row per province and class — `minimum_age`, `delivery_allowed`, `pickup_allowed`, an
optional delivery window and pickup window (local time in the market's zone; an end before the start runs past
midnight), `source`, `confirmed` (counsel; all false). No row = that class can't be sold to that province at all.
The console shows the table (Listing vetting › Age checks, `GET /api/v1/console/vetting/age-rules`).

| province | alcohol | tobacco / vape | cannabis (accessories) | hours seeded |
|---|---|---|---|---|
| AB | 18 | 18 | 18 | alcohol 10:00–02:00 (AGLC Retail Liquor Store Handbook) |
| BC | 19 | 19 | 19 | — |
| SK | 19 | 19 (since February 2024) | 19 | — |
| MB | 18 | 18 | 19 | — |
| ON | 19 | 19 | 19 | alcohol delivery 09:00–23:00, pickup 07:00–23:00 (AGCO) |
| QC | 18 | 18 | 21 | — |
| NB | 19 | 19 | 19 | — |
| NS | 19 | 19 | 19 | — |
| PE | 19 | 21 | 19 | — |
| NL | 19 | 19 | 19 | — |
| YT | 19 | 19 | 19 | — |
| NT | 19 | 19 | 19 | — |
| NU | 19 | 19 | 19 | — |

Sources (each row's `source` names its act): alcohol — the provinces' liquor acts as summarised publicly (18 in
Alberta, Manitoba and Québec, 19 elsewhere); tobacco — the Tobacco and Vaping Products Act sets a federal floor of 18
and the provinces' tobacco acts (Ontario: Smoke-Free Ontario Act, 2017; British Columbia: Tobacco and Vapour Products
Control Act; Saskatchewan raised it to 19 in 2024; Prince Edward Island to 21 in 2019); cannabis — the provincial
cannabis acts (18 Alberta, 21 Québec, 19 elsewhere), whose application to *accessories* is a counsel question.

Change a row (any environment; picked up at once, the table is read per request):

```sql
UPDATE region.age_rules SET minimum_age = 19, confirmed = true, source = 'Act …, s. 12 (confirmed by counsel 2026-11-02)',
       updated_at = now() WHERE province = 'XX' AND age_class = 'alcohol';
-- a province where third parties may not deliver alcohol: pickup only
UPDATE region.age_rules SET delivery_allowed = false, updated_at = now() WHERE province = 'XX' AND age_class = 'alcohol';
-- a delivery window (local time; '10:00'–'02:00' runs past midnight)
UPDATE region.age_rules SET delivery_from = '10:00', delivery_until = '23:00' WHERE province = 'XX' AND age_class = 'alcohol';
```

## Sellers' licences

- `merchants.restricted_licences` (V342): class, the business's province, licence number, the document (stored through
  the storage port as a `verification` document, never public), expiry, status `pending` → `approved` / `rejected`
  (reason) → `expired` / `replaced`.
- Studio › Compliance › Restricted licences (owners and technicians send, the team sees):
  `GET/POST /api/v1/merchants/{merchantId}/restricted-licences` (multipart: `ageClass`, `licenceNumber`, `expiresOn`,
  `file`).
- Console › Listing vetting › Licences (staff with Vetting): `GET /api/v1/console/vetting/licences`,
  `GET …/{licenceId}/document`, `POST …/{licenceId}/decision` `{approve, reason, note}`. Every submission and decision
  is in the audit log (`merchants.restricted_licence_submitted|approved|rejected|expired`) and the business gets an
  email (`restricted-licence`, en/fr).
- Hidden until approved: restricted listings can't be submitted or published without an approved, unexpired licence
  for the class (409 `licence_required`); a listing that loses its licence is hidden by the platform
  (`catalogue.offers.licence_hold`, `food.menu_items.licence_hold`) and comes back exactly when a licence is approved
  again — never one the seller hid.
- The nightly job (`RESTRICTED_LICENCE_CRON`) expires licences past their date (listings hidden, email) and sends a
  reminder 30 days before expiry, once.

## The customer's ID check

- Port `AgeIdentityProvider`, `AGE_VERIFICATION_PROVIDER`: `stripe` = Stripe Identity (document + matching selfie,
  `metadata.northline_purpose=age`) on the platform account; `local` = a fake whose page picks the outcome
  (`/api/v1/dev/age-sessions/{id}`, local profile only). Staging and prod refuse `local`.
- `GET /api/v1/me/age-verification` (the status) and `POST` `{returnTo: web | web_food | app}` → `{url, status}`. The
  web returns to `CONSUMER_ORIGIN` + `/cart?age=done` (or `/food/checkout?age=done`), the app to
  `AGE_VERIFICATION_APP_RETURN_URL`. The result arrives through the same Stripe webhook as owners' identity
  (`identity.verification_session.*`), or by reading the session on return. Five attempts, then support.
- **Kept:** `restricted.age_verifications` — "verified over N, on date, by method" (`stripe_identity_document_selfie`
  or `fake`). N is the age in whole years on the day, **capped at the strictest minimum in the table** (21), so an
  adult's real age is not kept; N plus the whole years since the check is their age floor. The session is redacted at
  Stripe once the result is known; the document, selfie, date of birth and ID number are never stored by Northline.
- Checkout: the quote and the setup carry `age` (`required`, `minimumAge`, `classes`, `state`); Pay/Continue waits for
  `verified`; the api re-checks at start and place.

## The ID check at handoff

- An order with restricted items carries `id_check_age` (the province's minimum for the strictest class) on the order
  and the delivery.
- **Door (courier app):** the stop shows "ID check: N+"; the drop-off asks for three confirmations and the account
  holder's name, then the usual proof. `POST /api/v1/courier/stops/{id}/dropoff` `{proof, pin?, idCheck: {idChecked,
  recipientMatches, ofAge}}`; anything but three yeses → 422. **No gifting:** the order is handed only to the account
  holder (there is no gift or group-order flow, and "the name matches" is part of the check).
- **Counter (Studio kitchen display):** a food pickup with alcohol opens the counter check before "Picked up"
  (`POST /api/v1/merchants/{m}/kitchen/live/{orderId}/handoff` `{idCheck}`), or Refuse (`…/refuse` `{reason}`).
  Northline has no merchant self-delivery, so the counter is the only merchant handoff.
- Reasons: `no_id`, `underage`, `mismatch`, `nobody_of_age`, `intoxicated`, `other`. Every check — passed or refused —
  is in `restricted.handoff_checks` with who, where (door/counter), the age and the answers. Never an ID image.

## Refusals, returns and refunds

| | goods (shop delivery) | food (delivery or pickup) |
|---|---|---|
| order | `returned`; the courier gets a **return stop** to the shop (`POST /courier/stops/{id}/refuse`, then `…/returned`) | `returned`; the kitchen ticket is `refused` |
| refunded | every line in full (an uncaptured hold is released) | the alcohol dishes only |
| charged | the delivery fee (the trip was made; the customer was told at checkout) | the rest of the meal, the fees and the tip |

Refunds go through the refund queue as auto-approved cases, so the card, the ledger, tax and the customer's "Refunds
& cases" behave like any refund.

## Trust and safety

- Console › Listing vetting: **Licences** (the queue) and **Age checks** (`GET /api/v1/console/vetting/age-checks?from&to`:
  checks, refusals by reason and province, per courier/business; the age table).
- Every listing in a restricted category goes to a person; the words check flags alcohol/tobacco words outside those
  categories (`AGE_CLASS_MISMATCH`).
- A business with repeated refusals or an expired licence is visible in the report; acting on it (warning, suspension)
  uses the existing trust & safety actions.

## Retention and privacy

| data | kept | how |
|---|---|---|
| `restricted.age_verifications` | while the account is open; deleted with the account (privacy erasure) | `RestrictedPersonalData`; retention schedule `enforcement: none` (no stand-alone period) |
| `restricted.handoff_checks` | 2 years (`AGE_CHECK_RECORDS`), longer while a dispute is open; pseudonymous after erasure | retention job, `P2Y`, hold `open_dispute` |
| licence documents | as merchants' verification documents | merchants' retention |

The masked staging copy (S-114) masks `restricted.age_verifications` to `pending` (`db/dr/mask/restricted.sql`).

## App stores

Consumer app: alcohol only, 18+ rating, the age gate and the province rules declared in `store/policy.json`; Stripe
Identity declared as a service provider and the result as *Other data types* in the privacy answers. Courier app:
unlisted. See [mobile-release.md § Distribution and age-restricted goods](mobile-release.md#distribution-and-age-restricted-goods).

## Configuration

| variable | app | default | notes |
|---|---|---|---|
| `AGE_VERIFICATION_PROVIDER` | api | `local` | `stripe` required in staging and prod (`local` refused); uses `STRIPE_SECRET_KEY` and the platform webhook |
| `CONSUMER_ORIGIN` | api | `http://localhost:3000` | where the web returns after the check; required in staging and prod (the chart sets it) |
| `AGE_VERIFICATION_APP_RETURN_URL` | api | `ca.northline.app:/age-verified` | the consumer app's return link |
| `RESTRICTED_LICENCE_CRON` | api | `0 17 0 * * *` | nightly licence expiry and reminders |

Stripe: Identity must be activated on the platform account (already needed for owners, [stripe.md § Identity](stripe.md#8-identity-s-22));
the webhook endpoint subscribed to `identity.verification_session.*` covers both uses.

## Operations

- **A customer can't finish the ID check** (five failures): support checks `restricted.age_verifications.last_error`;
  after confirming identity another way, `UPDATE restricted.age_verifications SET attempts = 0 WHERE user_id = '…'`.
  Never mark someone verified by hand.
- **A licence document is unreadable:** reject with `unreadable`; the business sends a new one.
- **Hours or ages change:** update `region.age_rules` (above) and record the source.
- **Local:** `AGE_VERIFICATION_PROVIDER=local`; add an alcohol product (Studio, a restricted category), approve the
  licence in the console, buy it as a customer and pick "Over 30" on the fake page.

## Never run for real

- Stripe Identity for customers (`StripeAgeIdentity`) has run only against WireMock stand-ins; no live or test-mode
  Stripe account exists.
- The ages, hours and delivery permissions are unconfirmed (H4); no province's liquor board has been asked whether a
  marketplace with business-held licences and a platform courier may deliver alcohol there (H2).
- The courier and counter checks have been exercised in tests and fixtures only, not by a courier.
