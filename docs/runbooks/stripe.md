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
    access to Balance, Charges, Events, Financial Connections accounts (S-24). Store them as `STRIPE_SECRET_KEY` / `STRIPE_PUBLISHABLE_KEY` in the secrets
    manager (see the environment runbooks). Never commit a key; never put a live key in dev or staging.
13. **Webhooks** — § 5.

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
- **Payout failed / canceled**: the `payout.failed` / `payout.canceled` webhook (or the reconciler 24 h after the
  arrival date) puts the money back in the merchant's balance, gives an instant fee back and publishes
  `payout.failed`; the owner fixes the bank account in Payouts.
- **Refund exceeded the transfer** (merchant already paid out): the reversal is capped at what is still transferred;
  the rest is a negative merchant balance recovered from later releases, and Stripe debits the bank if the connected
  account goes negative.
- **Reconciling**: every Stripe object carries `northline_*` metadata; search the dashboard by
  `metadata[northline_escrow_id]` or by the `transfer_group`.

## 5. Webhooks (S-12)

Two endpoints, each with its own signing secret:

| endpoint (Stripe dashboard → Developers → Webhooks → Add endpoint) | listen to | events | secret |
|---|---|---|---|
| `https://<api host>/api/v1/webhooks/stripe` | **Your account** | `payment_intent.amount_capturable_updated`, `payment_intent.succeeded`, `payment_intent.payment_failed`, `payment_intent.canceled`, `charge.refunded`, `refund.created`, `refund.updated`, `refund.failed`, `charge.dispute.created`, `charge.dispute.updated`, `charge.dispute.closed`, `charge.dispute.funds_withdrawn`, `charge.dispute.funds_reinstated`, `transfer.reversed`, `transfer.updated`, `financial_connections.account.disconnected`, `financial_connections.account.deactivated` (S-24) | `STRIPE_WEBHOOK_SECRET` |
| `https://<api host>/api/v1/webhooks/stripe/connect` | **Connected accounts** | `account.updated`, `payout.paid`, `payout.failed`, `payout.canceled` | `STRIPE_CONNECT_WEBHOOK_SECRET` |

- **API version:** create both endpoints with **`2026-08-26.dahlia`** (the pinned version, § 1); an endpoint on
  another version sends objects in a shape the handlers weren't tested with. Upgrading = new endpoints on the new
  version, deploy, delete the old ones.
- **Test vs live:** register both endpoints in test mode for dev/staging and again in live mode for prod; each has its
  own `whsec_…`. A test-mode installation (key `sk_test_…`) ignores live-mode events and vice versa.
- **Secrets:** reveal each endpoint's signing secret, store it in the secrets manager as `STRIPE_WEBHOOK_SECRET` /
  `STRIPE_CONNECT_WEBHOOK_SECRET` (staging and prod refuse to start without them). **Rolling a secret:** Stripe's
  "Roll secret" keeps the old one valid for up to 24 h and signs with both, so set the new value and restart within
  that window.
- **What Northline does:** checks `Stripe-Signature` (HMAC-SHA256, timestamp ≤ 5 min old — `STRIPE_WEBHOOK_TOLERANCE`),
  stores the event once in `payments.stripe_events` (event id = primary key; retries and replays are no-ops, payload
  with personal fields removed, kept 30 days), answers 200, and applies it asynchronously (outbox listener, plus a
  retry job every minute, up to 10 attempts). Events can arrive in any order: payouts and cases never leave a final
  state; disputes and accounts ignore an event older than the last one applied. Unknown types are stored as `ignored`.
  The endpoints need no session or token (and have no CSRF); the signature is the authentication; 600 requests/min per
  address (`STRIPE_WEBHOOK_RATE_LIMIT`), then 429.
- **Effects:** `payout.paid` → paid; `payout.failed` / `canceled` → returned to the merchant's balance (+ instant fee
  back, `payout.failed` event); `charge.dispute.*` → a card-dispute case in Refunds & disputes (escrow on hold,
  `dispute.updated` "opened" email; merchant answers with response + evidence, can't refund or offer goodwill), closed
  won → escrow resumes, lost → the merchant carries it (escrow or balance + transfer reversal), `dispute.decided`;
  `account.updated` → payouts enabled / instant eligibility / requirements on `payments.connected_accounts` (payouts
  pause while Stripe says so) and the business linked to the account; PaymentIntent, refund and transfer events keep
  the mirrors current (a hold canceled at Stripe asks the customer to pay again).
- **Fallback:** the payout reconciler still settles in-transit payouts 24 h after their arrival date by asking Stripe,
  in case a webhook never arrives.

### Local testing with the Stripe CLI

The CLI isn't part of the repo's tooling; install it from Stripe's docs (`brew install stripe/stripe-cli/stripe`, or
the Linux package / Docker image `stripe/stripe-cli`). Then, with the api running on 8080 and a **test-mode** key:

```sh
stripe login
# platform events → the platform endpoint; prints "Your webhook signing secret is whsec_…"
stripe listen --forward-to localhost:8080/api/v1/webhooks/stripe
# connected accounts' events → the Connect endpoint (second terminal; its own whsec_…)
stripe listen --forward-connect-to localhost:8080/api/v1/webhooks/stripe/connect
```

Put the two printed secrets in `server/.env` as `STRIPE_WEBHOOK_SECRET` / `STRIPE_CONNECT_WEBHOOK_SECRET` and restart
the api. Trigger events with `stripe trigger payment_intent.amount_capturable_updated`, `stripe trigger
charge.dispute.created`, `stripe trigger payout.failed --stripe-account acct_…`, or replay one with
`stripe events resend evt_…` (a replay is deduplicated). Events for objects Northline doesn't know (most `trigger`
fixtures) are stored and logged as ignored — use objects created through the Studio to see effects. stripe-mock
doesn't send webhooks; the automated tests sign fixtures themselves (`StripeWebhookApiTest`).

## 7. Bank linking — Stripe Financial Connections (S-24)

How a merchant links the bank account payouts go to (Studio › Payouts › Bank account › Change). Code:
`payments.application.BankLinking` (port), `StripeBankLinking` / `FakeBankLinking` (adapters), `BankAccountService`;
decisions in `docs/DECISIONS.md` § S-24. **Not yet run against a real Stripe account** — written against Stripe's
documented API and tested with stripe-mock only.

| step | Stripe call / Studio | notes |
|---|---|---|
| session | `POST /v1/financial_connections/sessions` — `account_holder[type]=account`, `account_holder[account]=acct_…` (the merchant's connected account), `permissions[]=payment_method` | a new session per click; the api returns `{mode: "stripe", clientSecret, publishableKey}` |
| collect | Stripe.js `stripe.collectBankAccountToken({clientSecret})` in the Studio | Stripe.js is loaded from `js.stripe.com` only now, only in `stripe` mode; the owner signs in to their bank in Stripe's modal |
| check | `GET /v1/financial_connections/accounts/{fca}` | must be held by the same connected account and `active`, else 422 "We couldn't use that bank link. Connect your bank again." |
| attach | `POST /v1/accounts/{acct}/external_accounts` with the bank-account token | not the default yet: the 24 h hold |
| confirm | Studio step-up (passkey / authenticator) → `POST …/bank-accounts/{id}/confirm` with `X-Step-Up` | payouts pause 24 h; owners are emailed and texted (existing) |
| take over | `POST /v1/accounts/{acct}/external_accounts/{ba}` `default_for_currency=true` | the payouts job, after the hold |

- **Kept:** the institution's name and the last 4 digits (plus Stripe's `ba_…` and `fca_…`). Nothing else — no
  institution / transit numbers for a linked account, never the account number. Typed details ("Enter details
  manually", the design's fallback) still work and are tokenized at Stripe (`POST /v1/tokens`).
- **Audit log** (Settings › Security › Audit log, `developer.audit_log`): `payout_account.linked` (who, institution,
  last 4), `payout_account.change_confirmed` (step-up, before/after), `payout_account.change_effective` (system),
  `payout_account.bank_connection_ended` (stripe).
- **Disconnected:** `financial_connections.account.disconnected` / `…deactivated` (platform endpoint, § 5) mark the
  account; the bank account stays the connected account's external account, so payouts keep going there, and the
  Studio shows "Bank connection ended … reconnect to keep it verified" with a Reconnect button. Unknown `fca_…` →
  stored as ignored.
- **Onboarding** "Bank account for payouts" (outside `local`/`test`): verified with the linked account's label once one
  exists (linked in Payouts), otherwise it waits and is verified when the owner confirms a bank account.
- **Set-up:** Dashboard → Financial Connections → enable it for the platform (Canada: confirm with Stripe that
  Financial Connections covers the Canadian institutions the merchants use — TD, RBC, Scotiabank, BMO, CIBC, ATB,
  Desjardins, credit unions; manual entry covers the rest). Settings → Connect → Express dashboard keeps "merchants may
  update bank accounts" **off** (§ 2 step 8). The restricted key needs Financial Connections sessions (write) and
  accounts (read). No new variables: `STRIPE_SECRET_KEY` and `STRIPE_PUBLISHABLE_KEY`.
- **Local:** without a key the session's mode is `fake`: the Studio shows a "Test bank connection" picker (RBC ··8820,
  TD ··3391, …) instead of Stripe.js and sends a local token (`btok_local_<institution>_<last4>`); nothing leaves the
  machine. With stripe-mock (§ 3) the real adapter runs, but the Studio can't complete Stripe.js against it.

