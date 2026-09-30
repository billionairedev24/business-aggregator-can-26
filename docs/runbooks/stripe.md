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
| refund after capture | Refund (card) of the amount **plus its share of the tax** + Transfer reversal of the merchant-funded part if already released | platform | refund queue |
| tax quote | Stripe Tax calculation (province of supply) | platform | `TaxCalculations.calculate` at checkout (S-21) |
| tax report | Stripe Tax transaction from the calculation; reversal for each refund / lost chargeback | platform | after capture / refund, § 6 |

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
    Transfers, Payouts, Accounts (Connect), Account links, Login links, Tokens, Financial Connections sessions, Tax
    calculations and transactions (S-21); read access to Balance, Charges, Events. Store them as `STRIPE_SECRET_KEY` / `STRIPE_PUBLISHABLE_KEY` in the secrets
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
| `https://<api host>/api/v1/webhooks/stripe` | **Your account** | `payment_intent.amount_capturable_updated`, `payment_intent.succeeded`, `payment_intent.payment_failed`, `payment_intent.canceled`, `charge.refunded`, `refund.created`, `refund.updated`, `refund.failed`, `charge.dispute.created`, `charge.dispute.updated`, `charge.dispute.closed`, `charge.dispute.funds_withdrawn`, `charge.dispute.funds_reinstated`, `transfer.reversed`, `transfer.updated`, and for Identity (S-22) `identity.verification_session.created`, `.processing`, `.requires_input`, `.verified`, `.canceled` | `STRIPE_WEBHOOK_SECRET` |
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

## 6. Stripe Tax (S-21)

Northline is the **seller of record and the marketplace facilitator**: it collects GST/HST (and PST/QST where it is
registered) on every job and order line and remits it; merchants never charge it themselves. Stripe Tax prices the
tax and keeps the transactions Northline files from. Code: `ca.northline.payments` (`TaxCalculations`,
`TaxSyncService`, `StripeTaxGateway`); decisions in `docs/DECISIONS.md` § S-21. **Not yet run against a real Stripe
account** — written against Stripe's documented API and tested with stripe-mock only.

| step | Stripe call | when |
|---|---|---|
| quote | `POST /v1/tax/calculations` — one tax-exclusive CAD line, product tax code by kind, customer address = country `CA`, province (+ postal code at checkout), `address_source=shipping` (the place of supply) | checkout (`TaxCalculations.calculate`); again at capture if the quote expired (90 days) or there was none |
| sale | `POST /v1/tax/transactions/create_from_calculation`, `reference=sale_<escrowId>` | after the capture commits |
| refund / lost chargeback | `POST /v1/tax/transactions/create_reversal`, `mode=partial`, `flat_amount=−(amount + tax)`, `reference=refund_<id>` / `chargeback_<disputeId>` | after the refund is paid / the dispute is lost |
| reconciliation | `GET /v1/tax/transactions/{id}/line_items` | nightly 03:17 Edmonton, or on demand |

Every POST has an `Idempotency-Key` (`nl1:tax-transaction:<reference>`, …) and metadata `northline_merchant_id`,
`northline_escrow_id`, `northline_reference` — no names or contact details. Only the province is stored
(`payments.tax_calculations`); the postal code goes to Stripe for the calculation and is dropped.

### Set-up (once per mode: test for dev/staging, live for prod)

1. Dashboard → **Tax** → Get started: origin address = Northline's Calgary head office; default tax behaviour
   **exclusive**; default product tax code *General – Services* (`txcd_20030000`).
2. **Registrations** (Tax → Registrations): Canada — **GST/HST** (Northline's business number, `RT0001`). Add
   **British Columbia PST** before the BC pilot sells taxable goods, **Québec QST**, **Saskatchewan PST** and
   **Manitoba RST** only once Northline registers there. Without a registration Stripe Tax returns no tax for that
   tax (`taxability_reason: not_collecting`) and the Studio shows what was actually collected.
3. **Product tax codes** (`TAX_CODE_SERVICE`, `TAX_CODE_GOODS`, `TAX_CODE_FOOD`): defaults are Stripe's general
   services, general tangible goods and prepared food codes. Confirm them with the accountant for each category
   (e.g. basic groceries are zero-rated) before prod; changing them is a variable change.
4. Set `TAX_PROVIDER=stripe` (staging and prod refuse `local`). The restricted key needs write access to *Tax
   calculations and transactions* (§ 2 step 12).
5. Check: book a job in staging, complete it, and within a minute the Stripe dashboard (Tax → Transactions) shows
   `sale_<escrowId>`; Studio › Stripe & compliance shows the quarter's total for the province.

### Local

`TAX_PROVIDER=local` (default) uses fixed 2026 rates (AB/NT/NU/YT GST 5 %, BC GST + PST 7 %, MB GST + RST 7 %, SK GST +
PST 6 %, QC GST + QST 9.975 %, ON HST 13 %, NS HST 14 %, NB/NL/PE HST 15 %) and reports nothing; its ids
(`taxcalc_local_…`, `tax_local_…`) carry the amounts. `TAX_PROVIDER=stripe` with the stripe-mock settings of § 3
exercises the real adapter (stripe-mock answers with fixtures, so its amounts are meaningless).

### What the Studio shows

`payments.tax_jurisdiction_totals` — one row per merchant, quarter (`2026-Q3`, Edmonton) and jurisdiction
(`ab_gst`, `bc_gst_pst`, `on_hst`, `qc_gst_qst`, …, the province of supply): collected = sales − reversals reported
to Stripe Tax (never below 0; `payments.tax_totals_sync` keeps both parts). Stripe & compliance › Tax reads it.
Rows written before the sync (the dev seed's `platform_fee_gst` and `not_selling` rows) are left alone, and an older
row the sync reaches keeps its amount as a base. The GST summary CSV now has a "GST/HST refunded" column.

### Operations

- **Retries:** each transaction is stored `pending` with the capture / refund, reported by the outbox listener right
  after commit, and retried by the payments job every minute (up to 10 attempts, `payments.tax_transactions.error`
  holds the last failure). A reversal waits for its sale.
- **Reconciliation** (nightly, current and previous quarter; or now):
  `curl -X POST -H "Authorization: Bearer <staff token with acr=mfa>" -H 'Content-Type: application/json'
  -d '{"period":"2026-Q3"}' https://<api host>/api/v1/console/payments/tax-reconciliations`. It reports whatever is
  still pending (whatever its attempts), compares every recorded transaction with Stripe Tax's line items
  (`stripe_tax_cents`; a difference is logged as `Tax reconciliation: … Northline x ¢, Stripe Tax y ¢`), and
  rebuilds the quarter's rows. The answer: `{period, reported, checked, mismatched, rows, stillPending}`.
- **A difference** usually means the checkout tax wasn't a Stripe Tax quote (the caller computed it) or a
  registration was added mid-quarter; the customer paid what Northline recorded (`tax_cents`, which the ledger's
  `tax_payable` follows). Correct the filing in Stripe Tax's export, not in the table.
- **Refunds** give the customer the refunded share of the tax (`refunds.tax_cents`, half-up; a full refund returns
  all of it) and debit `tax_payable`; a lost chargeback moves its tax part from `tax_payable` too. Goodwill credits
  and holds canceled before capture report nothing.

## 7. Identity (S-22)

Every owner the business structure requires (the principals at or above the structure's KYC threshold: 25 % for
partnerships and corporations, everyone for sole proprietors, co-ops and non-profits — `docs/spec/legal-details.schema.json`)
verifies with a **Stripe Identity** VerificationSession: a government ID (driving licence, passport or ID card, live
capture) plus a matching selfie. Onboarding › Verification › "Identity (Stripe KYC)" lists them; the signed-in owner
opens Stripe's hosted flow ("This is me · verify now"), the others get the link by email (the `identity-verification`
template, [email.md](email.md)). Stripe sends the person back to `STUDIO_ORIGIN/onboarding/verification?…&identity=returned`
(signed-in owner) or to the public `STUDIO_ORIGIN/identity/done` (emailed owners).

| `IDENTITY_PROVIDER` | what happens | needs |
|---|---|---|
| `local` (default) | fake sessions; the "hosted flow" is `API_PUBLIC_URL/api/v1/dev/identity-sessions/{id}` (profile `local` only) where you pick the outcome: verified, name / date-of-birth mismatch, processing, `document_expired`, `document_unverified_other`, `selfie_face_mismatch`, `consent_declined`, canceled. It is applied exactly like the webhook. Refused under `staging`/`prod`; under `dev` a warning (owners can't finish) | — |
| `stripe` | stripe-java against Stripe Identity (API version pinned, § 1); `STRIPE_API_BASE` + stripe-mock works for requests (stripe-mock's fixture has no hosted-flow URL, so starting a session answers 409 there) | `STRIPE_SECRET_KEY` (the platform key; or a restricted key with Identity *write* and *read* — reading `verified_outputs` needs it), the platform webhook endpoint (§ 5) subscribed to the `identity.verification_session.*` events |

**Account setup (once per mode):** Stripe dashboard → Settings → Identity: activate Identity, fill the branding
(Northline name, logo, support email — shown in the hosted flow), check that Canada is supported for document
verification (it is at the time of writing; Identity pricing is per verification). Add the five
`identity.verification_session.*` events to the **platform** webhook endpoint (§ 5). Staging uses test mode (Stripe's
test documents; nothing is really checked), prod live mode.

**What Northline keeps** (`merchants.owner_identity_checks`, V032): the session id, the status
(`pending | processing | verified | retry | review | canceled`), Stripe's `last_error.code`, and two results —
`name_match` (the verified first + last name against the principal's legal name, accents/case/punctuation ignored,
extra middle names allowed) and `dob_match` (the verified date of birth against the business's Stripe Connect person
with the same name, when the Connect account exists and has one). The api reads `verified_outputs` once, compares in
memory and drops it; it never stores or logs ID images, ID numbers, names read or the date of birth. Webhook payloads
are stored redacted (`verified_outputs`, `provided_details`, names removed). The owner's email is kept for "Send a new
link".

**Flow and states:** verified by Stripe + names match → `verified`; verified but a name or date-of-birth mismatch →
`review` (a Northline agent decides in the console — not built yet; until then trust & safety looks at the row and the
Stripe dashboard); `requires_input` with an error → `retry` (the owner starts again; the old session is canceled at
Stripe, new idempotency key `nl1:identity-session:<check>:<attempt>`). The checklist's `kyc` row follows the owners:
all verified → verified; all verified / processing / in review → submitted (counts as complete for submitting);
otherwise to do — and while it is to do it is a `ComplianceStatus` due item ("Identity verification" on the dashboard).
Webhooks go through the S-12 platform endpoint (signature, 5-minute tolerance, dedupe on the event id), then an
in-process `IdentitySessionUpdated` to the merchants module, applied in Stripe's `created` order; events for replaced
sessions are ignored.

**Operations:**
- *An owner didn't get the email* — Mailpit locally; in the cloud check the email provider's logs; the owner can send
  a new link from the dialog (each new link cancels the previous session).
- *An owner is stuck in `review`* — compare the legal name typed in the Business step with the ID (Stripe dashboard →
  Identity → the session, searchable by metadata `northline_merchant_id`). If the application has a typo, the owner
  fixes the Business step (a principal whose name changes is a new principal and verifies again); otherwise an agent
  approves in the console (S-console).
- *Redaction* — a person's request to delete their verification data is done in the Stripe dashboard (Identity →
  session → Redact); Northline holds no copy.
