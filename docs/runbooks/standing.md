# Standing — trust consequences, appeals and coaching (S-156)

The `standing` module (`ca.northline.standing`, schema `standing`) keeps one registry of what Northline did to a
business or a customer, with the reason, and the appeals against it. The switches themselves stay with the modules that
own them (merchants: suspension and search hiding; catalogue: instant book; payments: the photo delay; identity: a
customer's no-show restriction). The rules and their numbers are S-93's trust rules (console › Trust & safety › Rules,
`trust.rules`); this page is how they behave and how staff work them.

Card-dispute evidence to Stripe, also S-156, is in [stripe.md § 14](stripe.md#14-card-disputes--evidence-to-stripe-s-156).

## 1. The consequences

| kind | what turns it on | what it does | ends | appealable |
|---|---|---|---|---|
| `suspended` | staff (console › Sellers) or the off-platform-payment rule (S-82) | the business is offline | staff reinstate, or a granted appeal | yes, once |
| `search_hidden` | staff, or the nightly rating-floor rule (S-82: below the floor over its window) | hidden from search; its page and checkout stay open | the rule's recovery, staff, or a granted appeal | yes, once |
| `instant_book_off` | the nightly `provider_no_shows` rule (default **3 confirmed no-shows in 30 days**) | customers ask for a quote; the Studio switch (Availability › How bookings come in) can't turn instant on while paused | by itself once the window holds fewer than the count; staff; a granted appeal | yes, once |
| `photo_delay` | a job completed **without a completion photo** (`missing_photo_delay`, default **48 h**) | that job's escrow is released 48 h later; the customer's sign-off doesn't release it earlier | when the time runs out (the nightly job closes it; payments' release job pays), or staff lift it | no (staff can lift it) |
| `no_show_warning` (customer) | the nightly `customer_no_shows` rule (default **2 confirmed in 12 months**) | providers see the count on the request and the job; the customer can't book instantly (asks for a quote) | by itself once fewer remain in 12 months, or staff | no |

Every consequence is shown to whom it affects, with the reason and what to do: Studio › **Standing** (and the alert on
Availability for a paused instant book; a suspension email links to Standing), the consumer site's Account › Profile
for a customer's warning (`GET /api/v1/me/standing`).

**No-shows** are trust flags (`trust.flags`, target `booking`) that count only once staff **confirm** them in
Trust & safety:

- a provider no-show: the customer reports "Something's wrong › The provider didn't come" on the booking (rule
  `no_show`);
- a customer no-show: the business reports it from Studio › Appointments, from **15 minutes after the start**, for a
  job that isn't completed or cancelled (rule `customer_no_show`).

**Staff lifts hold.** A consequence lifted by staff (by hand or by granting an appeal) is not put back by the nightly
rules within that rule's window (`StaffLifts`; S-82's rating-floor and off-platform jobs read it too).

## 2. The nightly job

`EnforceStanding` runs at `STANDING_ENFORCEMENT_CRON` (default `0 33 5 * * *`, after S-82's job, in the platform
zone `REGION_PLATFORM_ZONE`; not scheduled under the `test` profile). It pauses and resumes instant book, restricts and
clears customers, and closes photo delays whose time ran out. It is idempotent: running it twice changes nothing. When it
changed something it logs `Standing rules enforced: instant book paused n / resumed n, customers warned n / cleared n`.

There is no console button: to run it sooner (dev, staging), set `STANDING_ENFORCEMENT_CRON` for one run and restart
the api.

## 3. Lifting a consequence (staff)

Console › Sellers › a business › **Standing** card: each active consequence with its reason and **Lift** (needs the
sellers screen and the `suspend` action; a reason is required; audit `standing.consequence_lifted`). Lifting a
suspension reinstates the business, a search hiding shows it again, an instant-book pause resumes it (the business's
own switch is kept), a photo delay releases on the normal clock. The business is emailed.

A customer's restriction is lifted with the same endpoint (`POST /api/v1/console/standing/consequences/{id}/lift`).

## 4. Appeals

- **Who files:** an owner or manager (`manage`) in Studio › Standing, **once per decision**: each suspension, search
  hiding and instant-book pause, and each dispute an agent decided against the business (not card chargebacks, which
  the bank decides — those are answered with evidence, [stripe.md § 14](stripe.md#14-card-disputes--evidence-to-stripe-s-156)).
  Within **14 days**, a statement (up to 2,000 characters) and up to 5 files (JPG, PNG, HEIC or PDF, 10 MB each), stored
  in object storage under the `standing/` prefix.
- **Queue:** console › Trust & safety › **Appeals** (`/api/v1/console/appeals`, the trust screen). An agent with
  `decide` **takes** it (`in_review`, audit `standing.appeal_assigned`), reads the statement, the files and the
  original decision, and **grants** or **denies** it with a reason the business sees (audit `standing.appeal_granted` /
  `standing.appeal_denied`). Whoever took the original decision can't decide its appeal.
- **Granted:** a consequence is lifted as in § 3. A dispute decision isn't reversed — the customer keeps the refund —
  Northline pays the seller what the decision cost them (their share of the refunded amount after Northline's fee):
  ledger `appeal_compensation` → the business, and a transfer to its connected account (`payments.appeal_compensations`).
- **Outcome:** emailed to the owners (`standing-update`, en/fr-CA) and shown in Studio › Standing.

## 5. Coaching plans

Console › Sellers › Standing card › **Start a coaching plan** (`decide`): a title, a note, up to 10 tasks, a due date;
one active plan per business. The owners are emailed; the business ticks tasks off in Studio › Standing (`edit`). Staff
close it as completed or cancelled. Audit `standing.coaching_started` / `_completed` / `_cancelled`. A plan has no
automatic consequence: it is guidance the agent follows up.

## 6. Configuration

| variable | default | what |
|---|---|---|
| `STANDING_ENFORCEMENT_CRON` | `0 33 5 * * *` | the nightly job (§ 2) |
| the trust rules | `provider_no_shows` 3/30 days, `customer_no_shows` 2, `missing_photo_delay` 48 h | console › Trust & safety › Rules (S-93) |

Nothing else; no new secrets, no new Kafka topic (instant-book changes go out on `catalogue.listing` as
`catalogue.instant_book_changed`, no personal data).

## 7. Troubleshooting

- **A business says instant book is off but it chose instant:** Studio › Standing shows the pause and its reason; the
  console Standing card shows the same. Lift it if the no-shows were wrong (and un-confirm the flags).
- **A consequence keeps coming back:** it was lifted outside the registry (e.g. SQL). Lift it from the console so the
  lift holds.
- **An appeal can't be filed:** already appealed, past 14 days, or the consequence was lifted — the Studio says which.
