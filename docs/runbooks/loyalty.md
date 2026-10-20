# Loyalty — points, provider-funded rewards, Invite a neighbour and Northline Plus (S-152)

The loyalty module (`ca.northline.loyalty`, schema `loyalty`) decides who earns what. Points themselves live in the
trust module's ledger (`trust.points_ledger`), money in payments (the double-entry ledger, Stripe). This page is how to
run it in each environment; [stripe.md § 11–12](stripe.md#11-northline-plus--stripe-billing-s-152) is the Stripe set-up.

## 1. How it works

| what | when | where |
|---|---|---|
| **Earning** | a sale's escrow is **released** (goods, food, a booking), not when it is paid: the region's rule gives *n* points per whole dollar the business was paid (after discounts and points), × the Plus multiplier for members. One `loyalty.earnings` row per escrow (idempotent). | `region.loyalty_rules` (the market's row, else its province's, else the default) |
| **Reversal** | a refund charged to the business takes back its share (`earn_reversal`); a refund approved before the release was already left out. A cancellation before release earns nothing. | `refund.issued` |
| **Expiry** | `expiry_months` after each earning, first earned first spent; the job writes one `expiry` line per lot (`LOYALTY_JOBS_CRON`, hourly by default). `null` = points never expire. | the same rule |
| **Spending** | at checkout (shop, food, bookings): 100 points = $1 off, **before tax** — the discount line lowers the taxable amount the TaxGateway sees. The ledger books the points as a platform-funded discount when the sale is released. | payments ledger account `points` |
| **Provider-funded rewards** (S-75) | a business's running reward (2×/3× points on some or all of its listings, with a budget of $10–$10,000) adds bonus points to a covered sale; each credit draws its cost from the budget, and the reward stops when the budget is spent. The cost is charged to the business by transfer reversal (a payout deduction). | `trust.merchant_rewards`, `trust.reward_credits`, `payments.reward_charges` |
| **Invite a neighbour** | everyone has a code (8 letters/digits, no look-alikes); a **new** account uses one; both sides get the region's referral points after the invitee's **first completed order** of at least the minimum. | `loyalty.referral_codes`, `loyalty.referrals` |
| **Plus** | a household membership: $0 service fee on food, member prices where shops opt in (a % per offer), a points multiplier. Billing behind `MembershipBilling` (`local` fake or Stripe Billing). | `loyalty.memberships`, `payments.plus_invoices` |

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
| `PLUS_BILLING_PROVIDER` | `local` | `local` (fake; refused under `staging`/`prod`) · `stripe` (Stripe Billing) |
| `STRIPE_PLUS_MONTHLY_PRICE`, `STRIPE_PLUS_ANNUAL_PRICE` | — | the Stripe Price ids (`price_…`), required with `stripe` |
| `PLUS_AUTOMATIC_TAX` | `false` | Stripe Tax on Plus invoices ([stripe.md § 11](stripe.md#11-northline-plus--stripe-billing-s-152)) |
| `PLUS_MONTHLY_PRICE_CENTS`, `PLUS_ANNUAL_PRICE_CENTS` | `799`, `6900` | what the account shows (match the Stripe prices) |
| `PLUS_TRIAL_DAYS` | `30` | the free trial, once per household |
| `PLUS_SERVICE_FEE_WAIVED`, `PLUS_MEMBER_PRICES` | `true`, `true` | the benefits |
| `PLUS_POINTS_MULTIPLIER_PCT` | `200` | members earn 2× |
| `PLUS_GRACE_DAYS` | `7` | the local fake: how long an unrenewed membership keeps its benefits |
| `LOYALTY_JOBS_CRON` | `0 7 * * * *` | expiry, referral windows, the fake's renewals |
| `LOYALTY_REFERRAL_WINDOW`, `LOYALTY_NEW_ACCOUNT_WINDOW` | `P90D`, `P30D` | see the abuse limits |
| `LOYALTY_CLAIM_ATTEMPTS_PER_HOUR` | `5` | wrong codes an hour |

No new secrets: Stripe Billing uses `STRIPE_SECRET_KEY` and the platform webhook secret.

## 3. Per environment

- **local / test:** nothing to set. Plus uses the fake (join, switch, cancel and resume work; no card, no tax). The dev
  seed gives Prairie Wrench a 2× reward with a $50 budget, a 10 % member price on one product, and Amara the invite
  code `AMARAK26`. Release an escrow (complete a booking, confirm a delivery) to see points arrive.
- **dev:** `local` is allowed (a warning); set `PLUS_BILLING_PROVIDER=stripe` and the two price ids from the test-mode
  account to try Stripe Billing.
- **staging / prod:** `PLUS_BILLING_PROVIDER=stripe` (the chart sets it) and `STRIPE_PLUS_MONTHLY_PRICE` /
  `STRIPE_PLUS_ANNUAL_PRICE` in the operator's `configEnv` (start-up refuses without them); the webhook events of
  [stripe.md § 11](stripe.md#11-northline-plus--stripe-billing-s-152) on the platform endpoint.

## 4. Operations

- **Finance screen:** Plus revenue (the week's paid invoices), points outstanding and what they are worth (the
  liability), what businesses' rewards hold, points taken off sales this week, Plus households, referrals waiting.
- **A reward that ran out:** the Studio shows "budget spent"; the business raises the budget to restart it.
- **A customer says points are missing:** points arrive when the business's money is released (after delivery or the
  job's sign-off and the hold), not at payment. Check `loyalty.earnings` for the escrow, then `trust.points_ledger`.
- **Changing a rule** applies to sales released afterwards; earned points keep their expiry.
- **Not done:** a referral already rewarded is not taken back when the invitee's order is refunded later; there is no
  order-cancelled event, so points are only ever earned on release (nothing to reverse for a cancellation).
