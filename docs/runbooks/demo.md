# Client demo

A 15-minute demo of the marketplace for prospective clients, on the GCP dry run ([dryrun-gcp.md](dryrun-gcp.md)), on
OVHcloud ([ovh.md](ovh.md): `ENV=ovh-vps` or `ENV=ovh-mks` wherever this page says `ENV=dryrun`) or rehearsed on a
laptop. Every person and business in the demo data is fake. There are two ways to fill a demo environment:

- **One-click demo data** (S-194, next section) — the whole marketplace written straight into the database by one
  pipeline action, no manual step. Use this to test every flow end to end.
- **`make demo-data`** (sections 1–10) — a smaller marketplace made **through the product** (sign-ups, onboarding,
  approvals), which needs a Mailpit inbox, Stripe test keys and a first console admin. Use it to rehearse the
  onboarding story. Neither uses the dev seed: its personas have published credentials and `DevSeedGuard` refuses it
  outside a local database (DECISIONS 2026-10-18, GCP dry run).

## One-click demo data

**On OVHcloud** (after a `deploy`): **Actions › ovh › Run workflow** › `target` = `ovh-vps` (or `ovh-mks`), `action` =
**`seed-demo`** (tick `reset` to wipe the demo data and seed again) › approve the run. About two minutes. Then, on
your Mac:

```sh
make demo-creds ENV=ovh-vps     # each account: email, password, a current authenticator code and the key
```

`demo-creds` reads the Kubernetes Secret `northline-demo-accounts` over SSH (`ssh -i .ovh/ovh-vps-deploy-key
ubuntu@<IPv4>`; the IPv4 from `OVH_HOST`, else Terraform's output `vps`); for `ovh-mks` through `kubectl`'s current
context. Add each key to an authenticator app ("enter a setup key", time-based) or type the printed code. **Locally**:
`make demo-seed ENV=local` (against the stack of `make up`; `RESET=true` to wipe first), then `make demo-creds
ENV=local` (the file `.demo/local-accounts.env`, mode 600, git-ignored).

**What it makes** (`server/api/src/tools/resources/demo-seed/plan.json`; the run's summary lists the counts):

| | |
|---|---|
| businesses | 79, all **verified and live** (every check passed, approved, page published), in the configured default market: 45 providers, 23 shops, 11 kitchens |
| coverage | **every one of the 162 categories** the consumer site links to (all Services groups, Shop departments and Food formats and services) has at least one business with a priced listing; several in the main ones (3 bakeries, 3 grocers, 2 butchers, 3 mechanics, 3 house cleaners, 3 barbers, 3 tutors, 3 plumbers, 11 kitchens) |
| listings | 156 services (prices, durations, instant booking), 90 products (stock on today's pooled run), 56 dishes on live menus; a photo from the photo library on each ([photos.md](photos.md)): the demo slot when the library has one, else the category's — none drawn |
| hours | providers bookable every day (Mon–Sat 8–19, Sun 10–16, the market's time); kitchens open every day, two around the clock, so some are always **open now** |
| reviews | 3–10 per business (529), ratings mostly 4–5 |
| customer history | the demo customer: two delivered orders, a food order, two signed-off and reviewed bookings, an upcoming visit |
| Studio | the owner's businesses (a mechanic, a cleaner and a tutor — providers —, a bakery and an auto-parts shop — sellers —, a kitchen): today's jobs, orders to pack on tonight's run, an open quote request, an open dispute, low stock, an insurance renewal due, 12 weeks of earnings |
| console | a submitted application in the verification queue, a dispute escalated to an agent, a flagged listing in listing vetting |
| market | the configured default market (`DEMO_MARKET`, else the first market of `REGION_DEFAULT_PROVINCE` / `SEARCH_DEFAULT_MARKET`) set **live**, with delivery and service zones drawn around its centre when it has none. Nothing names a place: businesses are spread around the market's centre, so pages work without a location and "near {city}" |

**Accounts** (passwords and keys made at seed time, never committed or printed in a log):

| key | email | app | for |
|---|---|---|---|
| `admin` | admin@demo.northline.test | console | every console role: approve applications, disputes, vetting |
| `reviewer` | reviewer@demo.northline.test | console | trust & safety |
| `owner` | owner@demo.northline.test | Studio | the owner of six businesses of every type (switch business in the account menu) |
| `applicant` | applicant@demo.northline.test | Studio | no business yet: the live onboarding |
| `customer` | shopper@demo.northline.test | consumer | orders, bookings, reviews; an address in the market |
| `customer2` | avery@demo.northline.test | consumer | a second customer with an address |

**Payments and identity without Stripe.** With no `STRIPE_SECRET_KEY` the api uses its fake payment gateway and the
deploy puts identity and age checks on the local fakes ([ovh.md § 6](ovh.md#6-email-needed-stripe-and-twilio-optional)):
checkout, booking and the escrow complete without Stripe, and the seeded businesses are already verified, so nothing
waits on Stripe's hosted pages. What still needs Stripe: payouts to a real (test) bank account and Stripe's own
dashboards. What needs a person: the live onboarding of a *new* business (the applicant), as in the demo script.

**Re-running.** Idempotent: the ids come from the plan's keys, so a re-run refreshes the same rows (prices, stock,
live states) and moves today's jobs and tonight's run to the new day; reviews are immutable (V073) and stay. `reset`
deletes every demo row and everything made with them through the product (orders a demo customer placed, bookings
with a demo provider), except what the database keeps by design: the payments ledger and the audit log are
append-only (their triggers are never bypassed). The accounts' credentials survive a reset (`ROTATE=1
scripts/demo/seed-demo.sh …` makes new ones). Search results catch up within a minute or two (the worker's reconcile
sweep, [search.md § 7](search.md)).

**Safety.** The tool (`ca.northline.tools.demo.DemoSeed`, run from the api image as a one-off Job) refuses to run
without `NORTHLINE_DEMO_SEED=true` and under any `staging` or `prod` profile or environment; the chart never sets the
flag. It writes only through the tables' constraints and V016's triggers.

## Contents

1. [What `make demo-data` builds](#1-what-make-demo-data-builds)
2. [Before the first run on the dry run](#2-before-the-first-run-on-the-dry-run)
3. [Run it](#3-run-it)
4. [Accounts](#4-accounts)
5. [The 15-minute script](#5-the-15-minute-script)
6. [Reset between demos](#6-reset-between-demos)
7. [What to avoid](#7-what-to-avoid)
8. [Pre-demo checklist](#8-pre-demo-checklist)
9. [Rehearse on a laptop](#9-rehearse-on-a-laptop)
10. [Troubleshooting](#10-troubleshooting)

## 1. What `make demo-data` builds

`scripts/demo-data.sh` (driver `scripts/demo/demo-data.mjs`, plan `scripts/demo/data/marketplace.json`) signs people
up at northline-auth and then works only through the three apps' BFFs, with real sessions, as the Studio, the consumer
site and the console do:

| what | how |
|---|---|
| 11 businesses | a bakery, a grocer, a coffee roaster, a **wine shop (age-restricted)**, home cleaning, a barber, a caterer, tutoring, music lessons, a handyman, a photographer/DJ — each applied for in the Studio (business details, categories, every verification check), submitted, and **approved by staff in the console's verification queue** — by two people when the api asks for a four-eyes review (the reviewer recommends, a different approver decides; an api without the review step gets the approver's single decision) |
| their public pages | logo (initials on the business's colour), tagline, brand colour, published |
| 42 listings | 3–6 each: products with prices, stock, attributes and a picture; services with prices, durations and what's included; the wine shop's alcohol licence approved in the console's licence queue; whatever the automated vetting flags approved by trust & safety |
| opening hours | each provider's week (weekdays 9–5, Thursday to 7, Saturday 10–3), bookable |
| 3 customers | with an address in the market; 6 orders (3 packed, 3 still "to pack"), 6 bookings (4 completed, signed off and reviewed 4–5★, 2 upcoming) |

- **Pictures** are real photos from the photo library ([photos.md](photos.md) § 10): each business and listing names a
  `photo` slot, and a slot that has a photo is uploaded through the product (the product image, the service's photo
  of the work, the business's logo). While a slot has none, the picture is drawn at run time
  (`scripts/demo/lib/images.mjs`: a 1000 × 1000 PNG on white with the item's name; services get none) — nothing is
  downloaded or hotlinked. Six more for the listing you add live are written next to the state file (`-photos/`).
- **Places** come from the region model: the market is the configured default (`GET /api/v1/geo/markets` → `fallback`)
  or `DEMO_MARKET`; addresses come from the product's own address search in that market. The plan names no place.
- **Idempotent:** a re-run finds what exists (the state file's ids, the listings' `DEMO-…` SKUs, the business names),
  brings it back to the plan (prices, stock, hidden listings) and makes the orders, bookings and reviews only once.
- **Credentials:** the product has no passwords — demo accounts sign in with an **authenticator app**. northline-auth
  generates each key at sign-up; the script writes it (and backup codes) only to the **state file**
  (`.demo/<env>.json`, mode 600, git-ignored) with each key's QR code beside it (`.demo/<env>-qr/<account>.png`, to
  scan into the presenter's phone). Nothing secret is printed; with `CI=true` not even the identifiers. Keep the file
  with the operator; losing it means new accounts (new emails and phones).
- **History:** everything is dated today — the product can't backdate an order — so the dashboards' 12-week charts show
  this week's bar only. Run it a few days before the demo for more.

## 2. Before the first run on the dry run

Once per environment, after [dryrun-gcp.md](dryrun-gcp.md) steps 1–14 (OVHcloud: [ovh.md](ovh.md) up to its smoke
test; Stripe goes into `.ovh/<target>.env` instead of Secret Manager, [ovh.md § Secrets](ovh.md#secrets)):

1. **An inbox the script can read** (S-159). Every account is an email + a random password + an authenticator app
   (S-154's accounts), and the code northline-auth emails at sign-up is read from **Mailpit's API**: locally the
   compose Mailpit (`http://localhost:8025`, found on its own); on OVHcloud the demo inbox — `OVH_DEMO_MAILPIT=true`
   in `.ovh/<target>.env` ([ovh.md § Email](ovh.md#email)), then `https://mail.<OVH_DOMAIN>` with `MAILPIT_PASSWORD`
   is used without further settings. Any other inbox: `DEMO_MAILPIT_URL` (+ `DEMO_MAILPIT_PASSWORD`, user `demo`), or
   `DEMO_EMAIL_CODE_CMD` (a command printing the code emailed to `$1`), or type each code when the terminal asks. The
   plan's addresses are `@demo.northline.test`, which only a catch-all inbox like Mailpit receives — with a real mail
   relay set `DEMO_EMAIL_<KEY>` (e.g. `DEMO_EMAIL_OWNER`) to addresses you can read. **No Twilio is needed any more.**
2. **Stripe test mode** ([stripe.md](stripe.md)): Identity activated in test mode (Settings › Identity), the
   `identity.verification_session.*` events on the platform webhook endpoint (step 12). `values-dryrun.yaml` sets
   `IDENTITY_PROVIDER=stripe` and `AGE_VERIFICATION_PROVIDER=stripe`: the local fakes' outcome pages exist only under
   the `local` profile, so without Stripe no owner could finish onboarding and no customer could buy wine.
3. **First console admin** (step 13.1) — register on the consumer site with **an authenticator app** as the second
   factor and keep its key (the "can't scan?" text) and its password: the script signs in as this admin — the
   **approver** — to decide the applications (`DEMO_STAFF_IDENTIFIER`, `DEMO_STAFF_PASSWORD`,
   `DEMO_STAFF_TOTP_SECRET`; an older phone-code admin without a password leaves `DEMO_STAFF_PASSWORD` empty). The second console account, the **reviewer**,
   is made by the script: registered like the other demo accounts, then invited by the admin with the trust & safety
   role (Console › Team; an invite needs an existing account).
4. **The market live.** Production migrations start the first market at `pilot` (V345), and a pilot market takes no
   orders. Console › **Provinces** (admin) → the market → stage `live` (the screen's checklist must be green;
   [regions.md](regions.md#from-the-console-s-84)). The script warns when the market isn't live.
5. **Phones are optional.** `DEMO_PHONE_OWNER`, `_APPLICANT`, `_REVIEWER`, `_CUSTOMER`, `_CUSTOMER2`, `_CUSTOMER3`
   put a mobile on an account (unverified: no SMS is sent). Until S-159 the script needed six phones that received
   SMS.

## 3. Run it

```sh
# the dry run (≈ 10 minutes the first time, plus the Stripe pages below)
DEMO_STAFF_IDENTIFIER=admin@… DEMO_STAFF_PASSWORD=… DEMO_STAFF_TOTP_SECRET=… \
DEMO_MAILPIT_URL=https://mail.… DEMO_MAILPIT_PASSWORD=… \
DEMO_STRIPE_PUBLISHABLE_KEY=pk_test_… \
  make demo-data ENV=dryrun

make demo-data ENV=ovh-vps                # OVHcloud: the demo inbox from .ovh/ovh-vps.env (OVH_DEMO_MAILPIT=true)
make demo-data ENV=local                  # a local stack (§ 9): the local Mailpit, the dev seed's reviewer as admin
make demo-data ENV=dryrun ONLY=wine,barber  # only these businesses (keys in marketplace.json)
```

Each new account's emailed code is read from the inbox (§ 2.1) — newest message to that address with a 6-digit code,
polled for up to 20 seconds; without an inbox the terminal asks for it (or `DEMO_EMAIL_CODE_CMD` prints it). Under the
`local` profile the script can also read auth's dev outbox (`/api/auth/dev/outbox`) when Mailpit isn't running. The URLs come from `values-dryrun.yaml` — for `ENV=ovh-vps` / `ovh-mks` from `values-ovh-*.yaml` if it
sets `urls`, else the same file (`DEMO_*_URL` override them). Each target has its own state file (`.demo/<env>.json`;
`ENV=gcp-dryrun` is `dryrun`). Any other `ENV` than `local`, `dryrun`, `gcp-dryrun`, `ovh-vps` and `ovh-mks` is
refused.

**Steps only a person can do** are listed at the end and the exit code is 3 until none are left: on the dry run,
Stripe's hosted test pages — each business's **owner identity** (pick the verified outcome) and, if the Studio asks,
its **Connect onboarding** (Stripe's test data, [dryrun-gcp.md § 13](dryrun-gcp.md#13-demo-data)). Open each link, then
run the same command again; it carries on where it stopped. Locally nothing is left for a person (the fakes' outcome
pages are answered by the script).

The end of the output checks every business: `✓ Fade Room Barbers  4/4 listings live · page /providers/fade-room-barbers`.

## 4. Accounts

From the state file (`.demo/dryrun.json`, `accounts`): each account's email, **password** (S-159, made by the script),
authenticator key and backup codes. The authenticator app holds each key (scan `.demo/dryrun-qr/`). State files from
before S-159 (accounts without a password) keep signing in by code as before; delete the file to start over on email
accounts.

| account | app | sign in with | for |
|---|---|---|---|
| `owner` (Morgan Demo-Owner) | Studio | email + password + authenticator code | owns the 11 businesses: their orders, calendar, reviews, dashboards (the business switcher in the account menu) |
| `applicant` (Riley Demo-Applicant) | Studio | email + password + authenticator code | an account with no business of its own: the live seller onboarding |
| `customer` (Jordan Demo-Shopper) | consumer site or app | email + password + authenticator code (also at payment, "Confirm it's you") | the shopper: past orders, bookings, reviews |
| `customer2`, `customer3` | consumer site | as above | background customers |
| `staff` (the console admin) | console | email + password + authenticator code | the **approver**: decides applications; vetting, licences |
| `reviewer` (Casey Demo-Reviewer) | console | email + password + authenticator code | the **reviewer** (trust & safety): the first pair of eyes on an application when the four-eyes review is on — a different person from the approver |

## 5. The 15-minute script

Three browser windows (separate profiles, so the sessions don't mix), each signed in before the demo: the consumer
site as **customer**, the Studio as **applicant**, the console as **staff**; a fourth tab of the Studio as **owner**.
Stripe test card: `4242 4242 4242 4242`, any future expiry, any CVC, any postal code.

| min | who | what to do | what to say |
|---|---|---|---|
| 0–1 | shopper | the home page: hero, the four tiles, the rails of real listings near the market | one marketplace for services and shopping; every business verified before it appears |
| 1–5 | shopper | search **sourdough** → *Country sourdough loaf* (Hearth & Crumb Bakery) → **Add to cart** → cart → **Proceed to checkout** → step 1: the saved address, tonight's pooled window, "If something's out of stock" → **Continue** → step 2: the card (test card) → **Continue to payment** → "Confirm it's you": **Use authenticator code instead** → the code → step 3: review → **Place your order** → *Thank you — your order is placed* → **Track your order** | the 3-step checkout; payments behind a second factor; the money is **held** until the customer confirms delivery |
| 5–8 | shopper | **Services** → Barber & hair → **Fade Room Barbers**: its reviews, prices, durations → **Book a visit** → *Classic haircut* → **Choose a slot** → a day, a time → **Continue to payment** → tick both policies → **Hold $… in escrow** (test card) → *Booked* | live calendar from the provider's hours; escrow released only after the job and the customer's sign-off; reviews only from verified bookings |
| 8–10 | shopper | open **Cellar Door Wine & Spirits** (search **riesling**) → **Add to cart** → checkout: step 1 shows **Photo ID needed** → **Verify my age** → Stripe's test page (verified) → back at checkout, verified | the ID check happens once, we keep only "over the age"; the courier checks photo ID again at the door; the shop could list wine only after its licence was approved |
| 10–13 | applicant → staff | Studio: account menu → **+ Add another business** (the first demo opens onboarding directly) → **Sell products** → store name, legal name, address, description → **Continue to categories** → a department (e.g. *Gifts & crafts*) → **Continue to verification** → identity (**Start with Stripe** → Stripe's test page), **Look up**, **Enter BN** `123456789 RT0001`, **Declare categories**, **Read & sign**, returns **Northline standard**, bank, second factor → **Submit for review** → the gate *We're checking your application* (**Why is my Studio locked?**) → console › **Verification**: search the name → **Approve** (with the four-eyes review: the **reviewer** recommends, then the **approver** decides — two console windows) → back in the Studio, reload: the dashboard with **Get set up** | nobody sells before identity, registry, tax and bank checks pass and a person approves; the Studio unlocks on approval |
| 13–14 | applicant | **Add your first product** → title, brand, category, description → **Images**: a picture from `.demo/dryrun-photos/` → price and stock → compliance → **Submit for vetting** → *Approved · live*, or *In review · flagged* → console › **Listing vetting** → **Approve** | automated listing checks (images, words, categories); a person reviews what they flag |
| 14–15 | owner | Studio (owner) → **Hearth & Crumb Bakery** → **Orders**: the shopper's order from minute 4 under *To pack* → **Mark packed**; then **Fade Room Barbers** → **Dashboard** (net this month, rating, the charts) | the seller sees the order at once; packed orders go out on the shop's pooled run |

Optional, time permitting: French (footer or account menu › Français); the console's queues; the shopper's
**Orders & bookings** (account menu) with the delivered and booked history.

## 6. Reset between demos

```sh
make demo-data ENV=dryrun RESET=1     # after each demo (seconds)
```

It hides what the live demo added — listings outside the plan in the demo businesses and every listing of the
applicant's businesses — and puts the plan's listings back (prices, stock, hidden ones republished). What it does not
undo, by design of the product:

- **Orders and bookings placed live stay** in the history (there is no delete); the shopper's list grows.
- **The business onboarded live stays approved** (applications can't be deleted); its listings are hidden. Next time,
  **+ Add another business** with a new name.
- Upcoming bookings keep their slots; book a different time.

`RESET=all` hides **every** demo listing (the marketplace looks empty — e.g. before handing the environment to someone
else); a plain `make demo-data ENV=dryrun` brings them back. Accounts are never deleted.

## 7. What to avoid

- **Hidden features — keep the flags off** in the demo builds (the dry run's images are built without them):
  `VITE_NL_FULL_NAV=1` (consumer) shows the account's Wallet & points, Dietary, Plus and Language;
  `VITE_NL_TESTING_TOOLS=1` shows the "Send feedback" control and the console's Pilot / UAT / Go-live / On-call / API
  screens. Their routes still answer a typed URL — don't type `/account?tab=wallet`. Food is part of the consumer
  site since E-15 (the locked mockups show it in the department strip, the search scope and the home).
- **"Demo the approval" / "Simulate approval →"** on the Studio's gate: a development shortcut that only the `local`
  profile answers. Approve in the console.
- **Food, kitchens, couriers:** out of the demo. A delivery needs a courier on shift with the courier app; orders stay
  at "packed".
- **Alcohol hours:** the region's alcohol hours apply at checkout (`restricted_hours`; [age-restricted.md](age-restricted.md)) —
  demo the wine shop inside them.
- **Rate limits** ([README § Rate limits](README.md#rate-limits-s-9)): 5 codes an hour per account, 30 sign-in
  look-ups per 10 minutes per address. Sign everyone in **before** the demo and don't retry a failed sign-in in a loop;
  `make demo-data` signs in only the accounts it needs.
- **Known rough edges** (reported for fixing in the pull request that added this page): booking times on the wizard's last
  step and the confirmation are shown in the **browser's** time zone — set the demo laptop to the market's; the
  booking calendar's week title can name the previous week; the checkout's summary column slides under the header when
  scrolled; the consumer sign-in page still says "Bilingual support 7 am – 11 pm MT".

## 8. Pre-demo checklist

The day before:

- [ ] `make dryrun-up` (and `spot_nodes = false` + `terraform apply` for on-demand nodes, [dryrun-gcp.md](dryrun-gcp.md#running-it-day-to-day))
- [ ] Smoke test ([dryrun-gcp.md § 14](dryrun-gcp.md#14-smoke-test)): issuer, the three sites, api readiness, the Jobs
- [ ] Stripe in **test mode**: `pk_test_`/`sk_test_` keys, both webhook endpoints delivering (Dashboard › Webhooks, no failures)
- [ ] The market is `live` (console › Provinces)
- [ ] `make demo-data ENV=dryrun` exits 0 (every business ✓)

One hour before:

- [ ] `make demo-data ENV=dryrun RESET=1`
- [ ] Four browser profiles signed in (§ 5); the authenticator app on the presenter's phone has the demo keys
- [ ] The laptop's time zone is the market's; notifications off; the photos folder open
- [ ] One dry pass of minutes 1–5 with a different item (then `RESET=1` again)

## 9. Rehearse on a laptop

The same script against the local stack: `make up-all` (or `ci/e2e.sh up` — under `flock` when the machine is
shared, [e2e.md](e2e.md)), then `make demo-data ENV=local`. Locally the staff account is the dev seed's console
reviewer (from `web/e2e/data/local.json`; signing in with a backup code, the script stores a fresh set in the state
file), phone codes come from the local outbox, and the fake Stripe Identity and age pages are answered by the script.
Search needs an index: with `make up-all` the worker fills it; with the e2e stack (no Kafka worker) run the reindex
once afterwards ([search.md § 9](search.md#9-reindex-s-71)).

## 10. Troubleshooting

| symptom | cause, fix |
|---|---|
| exit 3, "For a person to finish" | a hosted Stripe page per business (dry run): open each link, run again |
| `429: rate limited … wait N s` | sign-in or code limits (§ 7); wait, then run again |
| `… is already registered but not in .demo/<env>.json` | the state file was lost or another file was used: restore it, or give that account other details (`DEMO_EMAIL_<KEY>`, `DEMO_PHONE_<KEY>`) |
| `the code emailed to … is needed` | no code reached the inbox in 20 s (not a terminal, so it can't ask): the environment sends elsewhere or the address isn't delivered (§ 2.1) — check Mailpit's web UI, set `DEMO_MAILPIT_URL` / `DEMO_EMAIL_<KEY>` / `DEMO_EMAIL_CODE_CMD` |
| `… already has an account that isn't in …` | as the row above (lost state file): the password and authenticator key are gone with it |
| a booking says "That time was just taken" | the owner is the one bookable person of every demo provider, so their jobs overlap across businesses; pick another time |
| `! … market … is a pilot market` | § 2 step 4 |
| a business ✗ in the check | the line under it names the listing and its vetting state; Studio › Listings shows why |
