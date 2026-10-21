# Loyalty — points, provider-funded rewards, Invite a neighbour and Northline Plus (S-152, S-162)

The loyalty module (`ca.northline.loyalty`, schema `loyalty`) decides who earns what. Points themselves live in the
trust module's ledger (`trust.points_ledger`), money in payments (the double-entry ledger, Stripe). This page is how to
run it in each environment; [stripe.md § 11](stripe.md#11-northline-plus--billed-by-northline-s-152-s-162) and § 12 say what Stripe sees (for Plus: PaymentIntents only, no Stripe Billing).

## 1. How it works

| what | when | where |
|---|---|---|
| **Earning** | a sale's escrow is **released** (goods, food, a booking), not when it is paid: the region's rule gives *n* points per whole dollar the business was paid (after discounts and points), × the Plus multiplier for members. One `loyalty.earnings` row per escrow (idempotent). | `region.loyalty_rules` (the market's row, else its province's, else the default) |
| **Reversal** | a refund charged to the business takes back its share (`earn_reversal`); a refund approved before the release was already left out. A cancellation before release earns nothing. | `refund.issued` |
| **Expiry** | `expiry_months` after each earning, first earned first spent; the job writes one `expiry` line per lot (`LOYALTY_JOBS_CRON`, hourly by default). `null` = points never expire. | the same rule |
| **Spending** | at checkout (shop, food, bookings): 100 points = $1 off, **before tax** — the discount line lowers the taxable amount the TaxGateway sees. The ledger books the points as a platform-funded discount when the sale is released. | payments ledger account `points` |
| **Provider-funded rewards** (S-75) | a business's running reward (2×/3× points on some or all of its listings, with a budget of $10–$10,000) adds bonus points to a covered sale; each credit draws its cost from the budget, and the reward stops when the budget is spent. The cost is charged to the business by transfer reversal (a payout deduction). | `trust.merchant_rewards`, `trust.reward_credits`, `payments.reward_charges` |
| **Invite a neighbour** | everyone has a code (8 letters/digits, no look-alikes); a **new** account uses one; both sides get the region's referral points after the invitee's **first completed order** of at least the minimum. | `loyalty.referral_codes`, `loyalty.referrals` |
| **Plus** | a household membership: $0 service fee on food, member prices where shops opt in (a % per offer), a points multiplier. **Billed by Northline itself** (S-162, § 5): the plan's RFC 5545 rule, one PaymentIntent per period on the saved card. | `loyalty.memberships`, `payments.plus_schedules`, `plus_periods`, `plus_attempts` |

Customers see their points on the consumer site (Account › Wallet & points, Invite a neighbour, Plus) and in the app
(Wallet, Points activity, Invite a neighbour); a push and an in-app notice say when points arrive
(`loyalty.points_credited` on topic `loyalty.points`, no personal data in the payload). "Rewards near you" is on both
homes (`GET /api/v1/public/rewards?city=&lat=&lng=`, 25 km or the city).

### Abuse limits on referrals

- not your own code, not someone in your household (`identity.households`);
- only accounts younger than `LOYALTY_NEW_ACCOUNT_WINDOW` (30 days) can use a code, once;
- at most `LOYALTY_CLAIM_ATTEMPTS_PER_HOUR` (5) wrong codes an hour;
- the first completed order must come within `LOYALTY_REFERRAL_WINDOW` (90 days) and reach the rule's minimum;
- a referrer gets at most the rule's `referral_monthly_cap` rewarded referrals in a rolling 30 days (the rest are
  refused with `referrer_cap`; the console sees the reason).

## 2. Configuration

Earning rules are **region data**, edited in the console (Finance › Points earning rules; viewing needs the finance
screen, changes the `promotions` action; audit `loyalty.rule_changed` / `loyalty.rule_removed`). The migration seeds a
default row: 2 points per dollar on every kind of sale, 18-month expiry, 1,000 referral points each, 5 per referrer per
30 days, first order at least $20. Add a province's or a market's row to differ; remove it to fall back.

| variable | default | what |
|---|---|---|
| `PLUS_MONTHLY_PRICE_CENTS`, `PLUS_ANNUAL_PRICE_CENTS` | `799`, `6900` | the plans' prices before tax (a member keeps the price their plan started with) |
| `PLUS_MONTHLY_RRULE`, `PLUS_ANNUAL_RRULE` | `FREQ=MONTHLY;INTERVAL=1`, `FREQ=YEARLY;INTERVAL=1` | when the plans renew: RFC 5545 `FREQ=MONTHLY` or `FREQ=YEARLY` with an optional `INTERVAL` (start-up refuses anything else) |
| `PLUS_RETRY_DAYS` | `1,3,5` | a refused renewal is tried again this many days after it was due, then the membership lapses |
| `PLUS_UNCONFIRMED_AFTER` | `PT24H` | a first payment never confirmed with the bank is dropped (the membership doesn't start) |
| `PLUS_TRIAL_DAYS` | `30` | the free trial, once per household |
| `PLUS_SERVICE_FEE_WAIVED`, `PLUS_MEMBER_PRICES` | `true`, `true` | the benefits |
| `PLUS_POINTS_MULTIPLIER_PCT` | `200` | members earn 2× |
| `PLUS_GRACE_DAYS` | `7` | how long past its period a membership being retried keeps its benefits (at least a day past the last retry, enforced) |
| `LOYALTY_JOBS_CRON` | `0 7 * * * *` | expiry, referral windows, S-152 memberships moving to Northline's billing |
| `LOYALTY_REFERRAL_WINDOW`, `LOYALTY_NEW_ACCOUNT_WINDOW` | `P90D`, `P30D` | see the abuse limits |
| `LOYALTY_CLAIM_ATTEMPTS_PER_HOUR` | `5` | wrong codes an hour |

No new secrets: Plus's PaymentIntents use `STRIPE_SECRET_KEY` (the fake gateway without one) and the platform webhook secret. Renewals are the payments job's (every minute, `northline.payments.jobs-interval`).

## 3. Per environment

- **local / test:** nothing to set. Save a card (Account › Payment methods: the fake test Visa), then join, switch,
  cancel and resume; the fake gateway's PaymentIntents pay, tax is the local fixed rates. The dev seed gives Prairie
  Wrench a 2× reward with a $50 budget, a 10 % member price on one product, and Amara the invite code `AMARAK26`.
  Release an escrow (complete a booking, confirm a delivery) to see points arrive.
- **dev / staging / prod:** nothing Plus-specific: the Stripe keys every environment already has (test mode in dev and
  staging, live in prod). No Stripe Product, Price or webhook event to add. Once, after deploying S-162 where S-152 ran:
  cancel any Stripe Billing test Subscriptions left in the dashboard ([stripe.md § 11](stripe.md#11-northline-plus--billed-by-northline-s-152-s-162) § Moving off Stripe Billing).

## 4. Operations

- **Finance screen:** Plus revenue (the week's paid periods, net of refunds), points outstanding and what they are worth (the
  liability), what businesses' rewards hold, points taken off sales this week, Plus households, referrals waiting.
- **A reward that ran out:** the Studio shows "budget spent"; the business raises the budget to restart it.
- **A customer says points are missing:** points arrive when the business's money is released (after delivery or the
  job's sign-off and the hold), not at payment. Check `loyalty.earnings` for the escrow, then `trust.points_ledger`.
- **Changing a rule** applies to sales released afterwards; earned points keep their expiry.
- **A member's payment:** `GET /api/v1/console/plus/households/{householdId}/periods` lists every period (state,
  amount, tax, paid at); `payments.plus_attempts` has each try and its decline code. A declined renewal is retried on
  `PLUS_RETRY_DAYS`; the member can pick another card (it's tried on the next run) or confirm a 3-D Secure payment
  from the email / push link.
- **Refund a period** (finance, `refund` action, audited): `POST /api/v1/console/plus/periods/{id}/refund` gives back
  amount + tax to the card; the current period's refund ends the membership at once. No console screen yet.
- **Not done:** a referral already rewarded is not taken back when the invitee's order is refunded later; there is no
  order-cancelled event, so points are only ever earned on release (nothing to reverse for a cancellation).

## 5. Plus billing (S-162)

Northline bills Plus itself — no Stripe Billing (owner, 2026-10-20). Each membership is a schedule: the plan's RFC
5545 rule anchored to the first paid instant (UTC; month-end and February 29 starts renew on the month's last day), its
price, the card. Periods are charged once each (idempotency key per membership and period, the attempt's number claimed
in `payments.plus_attempts`, the period row locked `skip locked`):

| moment | what happens | the member hears |
|---|---|---|
| join (first membership of the household) | the free trial; the first charge at its end | — |
| join (again) | the first period charged now, on-session; 3-D Secure through the returned client secret; declined → 409 `card_declined` | the receipt |
| renewal due | charged off-session by the payments job | the receipt (email) |
| declined | `past_due`, benefits continue, tried again on day 1, 3, 5 | email + push, with the next try |
| `authentication_required` | waits for the member (and the retries go on) | email + push linking to Plus (web `/account?tab=plus`, app wallet) |
| last retry declined | the membership lapses (benefits end) | email + push |
| cancelled | no further renewal; benefits to the period's end | — |
| plan change | from the next renewal, on the new plan's rule | the receipt then |
| refund (finance) | amount + tax back; the current period ends the membership | email + push |
