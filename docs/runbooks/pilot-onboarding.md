# Pilot merchant onboarding (S-120)

How Northline gets its first ten or more businesses — service providers, shops and kitchens — live in the first market:
who does what at each stage, what the businesses need to have ready, what Stripe needs in live mode, how kitchen visits
work, and how pilot data moves from rehearsal to production. The market is whatever the region model says it is
([regions.md](regions.md)); nothing here or in the code names a place.

The tooling:

- **Console › Pilot onboarding** (`/pilot`): the pipeline of a market's pilot businesses, from invite to live, with
  filters, each business's checklist and next action, who has to act, a blocker and an owner at Northline, notes,
  invites, kitchen visits, and a CSV export. Merchant success and admins act; trust & safety read it.
- **Invites**: a staff member sends a signed, expiring link (14 days, single use) by email; it opens the Studio with the
  business type and province filled in and marks the business as a pilot participant of the market.
- **Kitchen visits**: scheduled, photographed and recorded in the console; a passed visit verifies the kitchen's
  `site_visit` check, and where the region or a category requires it, the kitchen can't be approved without one.
- **`make pilot-dry-run`**: twelve fake businesses through the whole pipeline on a throwaway local stack (§ 8).

Nothing in this story has met a real business, a real ID check, a real kitchen or a live Stripe account. Those are
the real-world steps below.

## 1. The path: rehearse on staging, onboard in production

**Decision: pilot businesses onboard directly in production, in a market held at the `pilot` stage.** Staging is
where the process is rehearsed with fake businesses, never where real ones are verified.

Why not "verify on staging, then promote":

- Stripe Identity sessions, Connect accounts, bank links and their verifications exist in **one mode**. A test-mode
  Express account can't become a live one; a business verified in staging would redo identity, Connect onboarding and
  the bank link in production anyway, and its owners would have shown their ID twice.
- Staging runs Stripe **test mode** (required there, [staging.md](staging.md)): no real ID is checked, no real money
  can move. Real verification happens only with live keys, in production.
- Copying businesses between databases would carry staging's test-mode Stripe ids and fake registry results into
  production. The masked prod → staging refresh ([backups-dr.md](backups-dr.md)) goes the other way only.

What makes production safe before launch:

| | how | where it is enforced |
|---|---|---|
| customers can't reach the market | the market's stage is `pilot`: the Location screen puts visitors on the waitlist; no delivery runs; only live places take orders | region model ([regions.md](regions.md)), S-84 switchboard |
| pilot businesses are not in search | a pilot business in a market that isn't live is hidden from search (`search_hidden_cause = 'pilot'`) from the moment it accepts its invite; the business isn't told (no oversight email) | merchants (S-120), search indexer reads it as hidden (S-82) |
| launch | an admin moves the market to `live` in Console › Provinces; every pilot business of the market is shown in search at once (`pilot.market_launched` in the audit log) | console switchboard → `PilotCohort.marketLaunched` |

Pages stay reachable **by direct link** while hidden: a business's public page and product pages answer for anyone
holding the URL. That is what S-121's user-acceptance testing uses (pilot customers get the links); it is not a
secret. Don't share the links publicly before launch.

**Production prerequisite.** The region data ships the first province and its markets `live` (V131). Before the first
pilot invite in production, an admin lowers the pilot market to `pilot` in Console › Provinces (type the market's name
to confirm). Raise it back to `live` on launch day (§ 7). A business enrolled while its market is already live is never
hidden.

The rehearsal on staging: run the same steps with fake businesses (the dry run's script against staging's api works
with a staff token; or click through), Stripe in test mode, Identity in test mode (`IDENTITY_PROVIDER=stripe`), and
delete or suspend the fake businesses afterwards. Production data never goes back to staging unmasked.

## 2. Who does what

| role | console role | does |
|---|---|---|
| Merchant success (pilot lead + 1–2 people) | `merchant_success` | recruits the businesses, sends invites, owns each business on the board, writes blockers and notes, schedules kitchen visits, follows up |
| Kitchen inspector (a staff member, or a food-safety consultant Northline contracts) | `merchant_success` to record, or named on the visit | visits the kitchen, walks the checklist, takes photos, records the outcome |
| Trust & safety | `trust_safety` | decides registry and identity reviews, approves applications (verification queue), vets flagged listings |
| Admin | `admin` | sets the market's stage and the kitchen-visit rule, launches the market |
| The business owner | Studio owner | creates the account, completes the Business step and the checklist, verifies identity with Stripe, connects Stripe, adds listings, publishes the page |
| Stripe | — | verifies owners' IDs (Identity) and the Connect account's requirements; reports through webhooks |

Grant the role (S-96 Team & audit, or SQL until then):

```sql
INSERT INTO identity.platform_roles (user_id, role, granted_at, granted_by)
VALUES ('<staff user id>', 'staff', now(), '<admin id>'), ('<staff user id>', 'merchant_success', now(), '<admin id>');
```

## 3. Stages

The board derives every stage from the business's own records; staff never set a stage by hand. A business's stage is
the furthest step with every step before it done (or not applicable); its **next action** is the first step that
isn't, with who has to act.

| # | stage | done when | next action and who | documents and facts the business needs |
|---|---|---|---|---|
| 1 | Invited | the pilot row exists and its invite is pending | business accepts the invite (Northline resends an expired or withdrawn one) | a working email address |
| 2 | Account created | the invite was accepted: the business exists and is linked to the pilot | business completes the Business step | a Northline account (email or mobile), a passkey or authenticator app (businesses need a second factor) |
| 3 | Details complete | the Business step is saved (legal name, structure, owners, categories) | business | legal name, structure, business number (CRA BN) or provincial registration, trade name registration if any, registered address, owners and their share, categories |
| 4 | Identity verified | every owner who needs it (≥ 25 %, or everyone for sole proprietors, co-ops, non-profits) passed Stripe Identity | owners verify (Stripe); Stripe processing; an agent decides a name or date-of-birth mismatch (Northline) | a government photo ID (driving licence, passport or ID card) and a phone for the selfie, per owner |
| 5 | Stripe Connect ready | Stripe reported the Express account with charges and payouts enabled (`account.updated`) | business finishes Stripe's onboarding (Settings › Stripe & compliance › Update) or its open requirements; waiting = Stripe hasn't reported yet | the representative's date of birth and address, the business's website (its Northline page), a Canadian bank account (transit, institution, account number — typed into Stripe, never Northline), statement descriptor |
| 6 | Kitchen visit passed | kitchens only, where required: a visit passed (else "not needed": the booked slot is confirmed at approval) | Northline schedules; the inspector visits; a failed visit sends the business back to book again | see § 5 |
| 7 | Catalogue ready | at least one listing handed to vetting (services, products) or one published dish | business | services: price, duration, what's included · products: title, price, stock, a main image on white ≥ 1000 px, country of origin, bilingual labelling confirmed · dishes: price, allergens, a photo ≥ 1000 px |
| 8 | Approved | the application was approved | business submits the checklist; Northline decides open reviews, then approves in the verification queue | the checklist's evidence (below) |
| 9 | Live | approved, page published, at least one listing customers can see, in a market, and shown in search | business publishes its page; listings wait for vetting; the market opens (Northline) | — |

The checklist's evidence (onboarding, per business type):

- **Every business**: owners' identity (Stripe), the business registry lookup, a bank account for payouts (Stripe), a
  second factor.
- **Providers**: a licence number for each regulated service category (the regulator comes from the category data),
  liability insurance certificate (≥ $2M).
- **Sellers**: GST/HST number, product category permits (or "none required"), the product safety attestation, the
  returns policy.
- **Kitchens**: the provincial food premises permit number, food handler certificates (upload), the latest health
  inspection report (upload), liability insurance, the allergen attestation, a liquor licence or "not applicable",
  GST/HST number, the kitchen visit slot.

Blockers: staff write one down when something outside the system holds a business up ("insurance renewal in May"),
with who has to act (business, Northline, Stripe, inspector). The board also flags blockers it finds itself: an
expired invite, checks an agent sent back, Stripe requirements past due, a failed visit, a suspended business, a
business without a market. Clear a written blocker when it's resolved; every change is in the audit log.

## 4. Stripe Connect in live mode

Northline's platform account (one-time, before the first invite in production — [stripe.md](stripe.md)):

1. Activate the Stripe account in **live mode** for the Canadian legal entity: business details, a representative,
   the bank account Northline's fees land in.
2. **Connect**: platform profile completed (Express accounts, Canada, "marketplace"), the Connect branding (name, icon,
   colour, the support email and phone), the platform's terms link, and Express onboarding enabled. Northline is the
   merchant of record with separate charges and transfers (S-11): no `on_behalf_of`.
3. **Stripe Identity** enabled in live mode (Stripe reviews the use case first; allow a few business days) — required
   by `IDENTITY_PROVIDER=stripe` in production.
4. **Financial Connections** enabled in live mode if bank linking by login is used (S-24).
5. **Webhooks** in live mode: the platform endpoint `/api/v1/webhooks/stripe` on the production api host (payments,
   Identity, Financial Connections) and the Connect endpoint `/api/v1/webhooks/stripe/connect` (`account.updated`,
   `payout.*`), pinned to the API version in [stripe.md](stripe.md). Their signing secrets go into
   `STRIPE_WEBHOOK_SECRET` / `STRIPE_CONNECT_WEBHOOK_SECRET` (External Secrets, [secrets.md](secrets.md)).
6. Live keys `STRIPE_SECRET_KEY` / `STRIPE_PUBLISHABLE_KEY` in production only; test keys stay in staging.
7. Stripe Tax registrations for the pilot province (S-21) before the first sale.

Each pilot business (Stripe's hosted Express onboarding, from Settings › Stripe & compliance):

- legal entity type and name, business number, address, the business website (its Northline page), what it sells
  (MCC), a statement descriptor;
- the representative's legal name, date of birth, address, and — where Stripe asks for it — their SIN, collected by
  Stripe, never by Northline;
- directors/owners as Stripe requires (`directors_provided`, `executives_provided`);
- a Canadian bank account for payouts (Northline keeps Stripe's payout schedule manual and runs its own payouts);
- acceptance of Stripe's Connected Account Agreement.

The board's "Stripe Connect ready" waits for Stripe's own `account.updated` with charges and payouts enabled; a
business with requirements past due is blocked until it resolves them in Stripe's hosted flow. None of this has run
against live Stripe (S-11, S-12, S-22: stripe-mock and fakes only).

## 5. Kitchen visits

**The rule is data.** A kitchen is approved only after a passed visit when its market (else its province) says so, or
when one of its categories does:

```sql
-- per place: a market's NULL = its province's; a province's NULL = optional
UPDATE region.regions SET kitchen_visit = 'required' WHERE id = '<market id>';            -- or a province row
-- per category: wherever the kitchen is
UPDATE catalogue.categories SET site_visit_required = true WHERE id = '<category id>';
```

Where no rule applies, the slot the owner booked during onboarding is confirmed with the approval (the behaviour before
S-120). Turn the rule on for the pilot market before the first kitchen submits.

**Scheduling.** The owner books a slot in the onboarding checklist; merchant success confirms it by scheduling the
visit in the console (Pilot onboarding › the kitchen › Kitchen visits): a date and time within 60 days, and a staff
member or an inspector's name. Scheduling also marks the owner's checklist row as booked.

**On the day** — the inspector checks, marks each item pass, fail or n/a, and adds photos (JPEG or PNG ≤ 10 MB,
stored as verification documents in the business's object storage; at most 12 per visit):

| item | what to check |
|---|---|
| Hand washing | a dedicated hand-wash sink with soap, paper towel and hot water |
| Temperatures | fridges at 4 °C or colder, freezers at −18 °C or colder, with temperature logs |
| Separation | raw and ready-to-eat food stored and prepared apart |
| Sanitation | clean surfaces; sanitizer at the right strength (test strips) |
| Pests | no sign of pests; screens, sealed openings |
| Allergens | allergen ingredients stored and labelled as the menu declares them |
| Permit displayed | the food premises permit is current and displayed |
| Food handler | a certified food handler on every shift |
| Storage | food covered, dated, off the floor |
| Waste | waste and grease handled and collected |
| Packaging | packaging fit for delivery: sealed, tamper-evident, insulated where needed |

Photos to take: the hand-wash sink, fridge and freezer thermometers, the storage area, the permit on the wall, the
packaging. No people, no customers' orders, nothing with personal information.

**Outcome.** Passed (no failed item) → the `site_visit` check is verified and the kitchen can be approved. Failed
(needs a note of what to fix) → the check goes back to the owner, who fixes things and books again; schedule a new
visit. A visit is recorded once; cancel one that didn't happen. Every step is in the business's audit log
(`kitchen_visit.scheduled | photo_added | passed | failed | cancelled`).

The visit is Northline's own quality check. It does not replace the provincial health authority's permit and
inspection, which the kitchen uploads in its checklist.

## 6. Invites and the pilot cohort

**Inviting** (Pilot onboarding › Invite a business): market, business type, a working name, the business's email, the
email's language. The invite email (`pilot-invitation`, en/fr) carries the link; the console shows the link too, to
share another way. The link expires after 14 days, works once, and a new link withdraws the previous one ("Send a new
link"). Only the token's SHA-256 is stored.

**CASL.** The invite is sent as a transactional message because it is the follow-up a business asked for: send one
only after the owner agreed, in a conversation with the staff member (note it on the board), to be onboarded. Don't
send invites to lists or to businesses that haven't agreed — that would be a commercial message needing consent
([casl.md](casl.md)).

**Accepting.** The person signs in (or registers) and lands on the Account step with the type and province fixed; when
they continue, the business is created and linked to the pilot. An invite for another type or another province is
refused.

**Enrolling an existing business** (one that registered on its own): `POST /api/v1/console/pilot/enrolments` with `businessId` and `marketId` (console screen: not built, use the API with a staff session).

## 7. Launch day

1. The board's CSV (Export CSV) for the record; every pilot business at "Approved" with "Goes live when the market
   opens" as its next action.
2. The market goes live from **Console › Go-live** (S-118, [go-live.md](go-live.md)): the checklist, one admin requests,
   a second admin approves (typing the market's name). The Provinces screen no longer raises a market to Live. The
   province must be live and the market must have a delivery zone with a boundary.
3. The board shows the businesses **Live** within a minute (search re-indexes on the visibility event).
4. Merchant success tells each business with the launch email ([go-live/email-merchant-launch.md](go-live/email-merchant-launch.md)).

## 8. Dry run

```sh
make pilot-dry-run                     # STACK_LOCK=/path/to/lock wraps it in flock on a shared machine
KEEP=1 make pilot-dry-run              # keeps the database container (port 55120) for a look in psql
```

It starts a throwaway PostGIS container (or uses `DB_URL` + `PSQL_URL`, which must be disposable), migrates with the dev
seed, starts the api under `local` (dev auth, the fake Stripe Identity, the fake Connect gateway, fixture registries),
requires kitchen visits in the first live market of the region model (or `PILOT_MARKET`), and runs
`scripts/pilot/dry-run.mjs`: 4 providers, 4 sellers and 4 kitchens — all fake — each invited by a staff member,
accepted in the Account step, Business step, every checklist item, Stripe Identity through the fake, Stripe Connect
through the fake gateway plus a signed `account.updated` webhook, submission, a listing (a service, a product with an
image, a dish with a photo), a kitchen visit with a photo and a passed checklist (approval before it is refused),
registry and identity reviews decided, approval, the menu and page published. It then prints the board, writes the CSV
to `pilot-dry-run/pilot-board.csv`, and checks what customers see: every business has a market (its city) and its
listing is on the public pages (the shop's product page in its market, the market's kitchens and the kitchen's menu,
the provider's page). It fails unless every business is live, in a market and visible. One seller's address names no
market city on purpose (S-117's finding: such a business used to be approved without a market).

Result of the run recorded for S-120 (2026-10-03): 12 of 12 live, all in the market and publicly visible, in 32 s
(details in DECISIONS.md, S-120).

## 9. The pilot cohort (template)

Fill this in as businesses are recruited; keep real names and contacts on the board, not in git.

| # | working name | type | category | owner at Northline | agreed on | invite sent | kitchen visit | live |
|---|---|---|---|---|---|---|---|---|
| 1 | Provider A | provider | (service category) | | | | n/a | |
| 2 | Provider B | provider | | | | | n/a | |
| 3 | Provider C | provider | | | | | n/a | |
| 4 | Provider D | provider | | | | | n/a | |
| 5 | Seller A | seller | (shop department) | | | | n/a | |
| 6 | Seller B | seller | | | | | n/a | |
| 7 | Seller C | seller | | | | | n/a | |
| 8 | Seller D | seller | | | | | n/a | |
| 9 | Kitchen A | kitchen | (food category) | | | | | |
| 10 | Kitchen B | kitchen | | | | | | |
| 11 | Kitchen C | kitchen | | | | | | |
| 12 | Kitchen D | kitchen | | | | | | |

Twelve gives room for two to drop out and still meet "at least 10 live". A regulated provider category (one with a
licence regulator) and a kitchen with alcohol test the longer checklists; include at least one of each.

## 10. Troubleshooting

| symptom on the board | what to do |
|---|---|
| Invited, invite expired | Send a new link from the business's panel |
| Stripe Connect "Waiting for Stripe to report" for more than an hour | check the Connect webhook endpoint and secret ([stripe-incidents.md](stripe-incidents.md)); Stripe's dashboard › Connect › the account |
| Approved but "Business has no market" | its addresses named no market city and it has no pilot market; set `merchants.merchants.city` to the market's city (S-117 fix: approval now assigns the pilot market, else the province's first live market) |
| Live step "Listings waiting for vetting" | Console › Listing vetting |
| Kitchen approved but not live: "Kitchen sets its fulfilment and hours" | the owner saves Kitchen › Hours (fulfilment, opening hours); until then the market's kitchen list leaves it out |
| A kitchen can't be approved: "Record a passed kitchen visit before approving this kitchen." | schedule and record the visit (§ 5) |
