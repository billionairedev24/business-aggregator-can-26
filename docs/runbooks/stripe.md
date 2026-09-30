# Stripe (Connect Express) — account setup and operations

How Northline uses Stripe, how to set up the platform account for Connect (test and live), and what to do when money
gets stuck. Code: `ca.northline.payments` (charges, transfers, payouts, refunds) and `ca.northline.merchants`
(Connect accounts, the compliance screen); decisions in `docs/DECISIONS.md` § S-11.

## 1. How the money moves

| step | Stripe object | on which account | Northline trigger |
|---|---|---|---|
| checkout | PaymentIntent `capture_method=manual`, CAD, `setup_future_usage=off_session`, `transfer_group=order:<id>` / `booking:<id>` | platform | `PaymentAuthorizations.start` (one per job / order line) |
| customer confirms | Stripe.js `confirmCardPayment(client_secret)` → `requires_capture` | platform | consumer app |
| hold | — (Northline checks the PaymentIntent is `requires_capture` for at least the amount) | | `EscrowLifecycle.hold` |
| hold renewed | new PaymentIntent, off-session, same card; old one canceled | platform | re-authorization job, 36 h before `capture_before` |
| fulfilment | `capture` (amount + GST/HST) | platform | job completed / delivered / handed off |
| release | Transfer (amount − take rate) to `acct_…`, same `transfer_group`, `source_transaction` = the charge | platform → connected | 48 h after the job, 7 days after delivery, at handoff, or on sign-off |
| payout | Payout `method=standard` / `instant` | connected (`Stripe-Account`) | 09:00 Edmonton on payout days; instant on request |
| instant fee | Transfer of the 1 % fee from the connected account to the platform (account debit) | connected → platform | right after an instant payout |
| refund before capture | PaymentIntent `cancel` | platform | refund queue |
| refund after capture | Refund (card) + Transfer reversal of the merchant-funded part if already released | platform | refund queue |

- **Separate charges and transfers.** Northline is the merchant of record (it collects and remits GST/HST as
  marketplace facilitator), so charges have no `on_behalf_of` and statements show Northline. The take rate is the
  *application fee*: amount − transfer. Northline pays Stripe's processing fees.
- **Payouts are Northline's.** Every connected account has Stripe's payout schedule set to `manual` (at creation and
  again when payments links it); Northline's run applies the merchant's schedule, the reserve, holds for open cases
  and the 24 h bank-change hold, then creates the payout. `debit_negative_balances=true` lets Stripe recover a
  negative balance (a reversal larger than what is left) from the merchant's bank.
- **Idempotency.** Every POST carries `Idempotency-Key: nl1:<operation>:<ids>` (for example
  `nl1:capture:<escrowId>:<paymentIntentRowId>`, `nl1:transfer:<escrowId>`, `nl1:refund:<refundId>`,
  `nl1:scheduled-payout:<merchantId>:<date>`). Requests a person starts (instant payout, checkout) fold the client's
  `Idempotency-Key` in as a hash. Stripe keeps keys 24 h: a job retried within that window repeats the same call.
- **Metadata** holds Northline ids only (`northline_escrow_id`, `northline_merchant_id`, `northline_ref_type`,
  `northline_ref_id`, `northline_refund_id`, `northline_case`, `northline_user_id` on Customers). No names, e-mail
  addresses or phone numbers are sent to Stripe by Northline; card details go from the browser to Stripe only.
- **API version** is pinned to **`2026-08-26.dahlia`** — the version stripe-java 33.4.2 speaks
  (`StripeClients.PINNED_API_VERSION`; the api refuses to start and `StripeIdempotencyKeysTest` fails if an SDK
  upgrade moves it). When upgrading: read Stripe's changelog for the versions in between, move the constant, create
  the webhook endpoints (S-12) with the new version, then deploy.

## 2. Platform account setup (once per mode)

Do everything in **test mode** first (dev, staging), then repeat in **live mode** (prod). Test and live are separate:
objects, connected accounts, webhooks and keys don't carry over.

1. **Account.** Stripe account for the Canadian legal entity (Northline Inc.), country Canada, default currency CAD.
   Two-step authentication for every team member; roles: Administrator (2 people), Developer (engineers), Support
   specialist (support agents), View only (finance).
2. **Business details.** Legal name, BN, address, support phone/e-mail and URL; statement descriptor `NORTHLINE`
   (shortened `NORTHLINE`), so card statements read `NORTHLINE* …`.
3. **Connect → Get started → Platform profile.** Business model *marketplace*; the platform is responsible for
   refunds and chargebacks (separate charges and transfers); sellers are *individuals and businesses in Canada*;
   industries: home and automotive services, retail goods, food. Answer honestly — Stripe reviews it before live mode.
4. **Connect → Settings → Account types.** Enable **Express** only. Countries: **Canada** only.
5. **Capabilities.** `card_payments` and `transfers` (the api requests both on every account it creates).
6. **Branding** (Connect → Settings → Branding): business name *Northline*, icon (the Northline mark), brand colour
   `#1E4D36` (spruce), accent `#D9A441` (honey). These colour the Express onboarding and dashboard.
7. **Onboarding options** (Connect → Settings → Onboarding): collect *eventually due* requirements up front (the api
   asks for it too), require ID verification of representatives and owners ≥ 25 %; the Terms of Service acceptance is
   Stripe's hosted one. Return and refresh URLs come from the api (`STUDIO_ORIGIN` + `/settings/compliance`), so
   nothing needs registering; make sure `STUDIO_ORIGIN` is the Studio's public https origin.
8. **Express dashboard** (Connect → Settings → Express dashboard): allow merchants to update bank accounts **off**
   (bank changes go through the Studio with step-up and the 24 h hold), view payouts **on**, instant payouts **on**.
9. **Payouts** (Connect → Settings → Payouts): enable **Instant Payouts** for connected accounts (Canada: eligible
   Visa/Mastercard debit cards and some bank accounts); leave the default schedule alone — the api sets `manual` on each
   account. Instant payout pricing must match what the Studio shows (1 %, min $0.50, `Fees.instantFee`); if Stripe's
   price changes, change `Fees` and design copy together.
10. **Radar**: default rules; add *Block if :risk_level: = 'highest'*. 3-D Secure: Stripe's default (request when
    required).
11. **Payment methods** (Settings → Payment methods): cards only (manual capture). Apple Pay / Google Pay can be added
    later (they are cards to Stripe).
12. **API keys.** Developers → API keys: the *secret key* (`sk_test_…` / `sk_live_…`) and *publishable key*
    (`pk_…`). Prefer a **restricted key** for the api with write access to: PaymentIntents, Customers, Refunds,
    Transfers, Payouts, Accounts (Connect), Account links, Login links, Tokens, Financial Connections sessions; read
    access to Balance, Charges, Events. Store them as `STRIPE_SECRET_KEY` / `STRIPE_PUBLISHABLE_KEY` in the secrets
    manager (see the environment runbooks). Never commit a key; never put a live key in dev or staging.
13. **Webhooks** — S-12 (a section of this runbook once it lands).

### Test mode data

- Cards: `4242 4242 4242 4242` (succeeds), `4000 0025 0000 3155` (needs 3-D Secure — exercises the re-authorization
  failure path when used off-session), `4000 0000 0000 9995` (declined, insufficient funds). Any future expiry, any CVC.
- Canadian bank for connected accounts: institution `000`, transit `11000`, account `000123456789`.
- Instant payouts in test mode: add one of Stripe's test *debit* cards for instant payouts (Stripe docs → Connect →
  Instant Payouts → Testing) as the connected account's external account.
- Connected account onboarding in test mode: use the "Use test data" buttons; `000-000` as SMS code.

## 3. Local development

| mode | set | what runs |
|---|---|---|
| fake (default) | nothing | `FakeStripeGateway` + `FakeConnectAccountGateway`: everything succeeds, nothing leaves the process |
| stripe-mock | `docker compose --profile payments up -d`, then `STRIPE_SECRET_KEY=sk_test_123` `STRIPE_API_BASE=http://localhost:12111` | the real adapters against Stripe's mock (stateless fixtures, validated requests) |
| Stripe test mode | `STRIPE_SECRET_KEY=sk_test_…` `STRIPE_PUBLISHABLE_KEY=pk_test_…` (no `STRIPE_API_BASE`) | the real adapters against your test-mode account |

The tests run stripe-mock in Testcontainers (`stripe/stripe-mock:v0.205.0`, `support/StripeMock`) and check that
every mutating call sends an `Idempotency-Key` and every call the pinned `Stripe-Version`.

## 4. Operations

- **"Payment reauthorization required"** (`payment.reauthorization_required`): Northline could not renew a card hold
  off-session (declined or 3-D Secure). The customer is asked to confirm again; the old hold stays valid until
  `lapsesAt`. If it lapses, Stripe cancels the PaymentIntent and fulfilment can no longer capture it — support
  contacts the customer for a new payment before the job.
- **Capture failed** (log `Stripe capture failed` from the fulfilment listener): usually a lapsed hold. The listener
  retries; fix by a new checkout for the job, then the escrow can be captured.
- **Transfer failed** (release job log): the release job retries each minute with the same key. `balance_insufficient`
  shouldn't happen (transfers draw on their charge via `source_transaction`); check the charge was captured.
- **Payout failed / canceled**: S-12 webhooks move it back to the merchant's balance; until then the payout
  reconciler logs `Payout … is failed at Stripe`.
- **Refund exceeded the transfer** (merchant already paid out): the reversal is capped at what is still transferred;
  the rest is a negative merchant balance recovered from later releases, and Stripe debits the bank if the connected
  account goes negative.
- **Reconciling**: every Stripe object carries `northline_*` metadata; search the dashboard by
  `metadata[northline_escrow_id]` or by the `transfer_group`.
