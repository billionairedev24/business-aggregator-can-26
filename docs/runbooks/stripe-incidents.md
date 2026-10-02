# Stripe incidents (S-115)

What to do when money stops moving or looks wrong. Each section: symptoms, impact, decision tree, exact commands,
verification, rollback, comms. How Stripe is set up and how the money moves: [stripe.md](stripe.md); key rotation
(planned): [key-rotation.md § Stripe](key-rotation.md#4-stripe-api-keys-and-webhook-secrets).

**Never run against a real Stripe account.** No test- or live-mode account exists yet; every step below that talks to
Stripe was written from Stripe's documentation and exercised against stripe-mock, a stand-in or a fake (see each
section's *Exercised*). The Northline-side steps (SQL, jobs, the console, the api's answers) ran for real in tests.

| incident | page / ticket | section |
|---|---|---|
| Stripe is down or slow | `NorthlineCheckoutAvailabilityBurn`, `NorthlineCheckoutLatencyBurn`, `NorthlinePayoutRunStalled` | [1](#1-stripe-is-down-degrade-checkout-what-queues) |
| webhooks not arriving, piling up, or failing | `NorthlinePayoutTimelinessBurn`, payouts stuck "in transit", support tickets | [2](#2-webhook-backlog-or-replay) |
| Stripe and the ledger disagree | the nightly reconciliation says `mismatch` | [3](#3-stripe-and-the-ledger-disagree) |
| many disputes at once | `charge.dispute.created` spike, finance | [4](#4-dispute-spike) |
| a Stripe key or webhook secret leaked | anyone who sees one outside the secrets manager | [5](#5-leaked-keys) |

First five minutes, whatever it is: open <https://status.stripe.com>, the **checkout and payouts** dashboard
(`northline-checkout-payouts`), and the api's logs for `Stripe` (`StripeCallFailed`, `Provider unavailable
(payments_unavailable)`, `Payments job '…' failed`). Open an incident note with the time.

## 1. Stripe is down (degrade checkout, what queues)

**Symptoms.** Checkout answers **503 `payments_unavailable`** with `Retry-After: 60` (since S-115: Stripe unreachable,
429 or 5xx — before, a 500); log `Provider unavailable (payments_unavailable): …StripeCallFailed…`; the checkout
availability / latency burn alerts ([alerts/checkout-availability.md](alerts/checkout-availability.md)); payments job
steps failing every minute (`northline_jobs_runs_total{job=~"payments.*",outcome="failed"}`); Stripe's status page.

**Impact.** New orders and bookings can't be paid. **Nothing already paid is at risk:** holds stay valid at Stripe
(7 days), the ledger is Northline's, and every Stripe call is retried with the same idempotency key, so nothing is
charged, transferred or paid out twice when Stripe comes back.

What degrades and what queues:

| flow | during the outage | when Stripe is back |
|---|---|---|
| checkout (`POST /me/checkouts/{id}/place`, booking checkout, quote deposits) | 503 `payments_unavailable`, "Payments are unavailable right now. Nothing was charged — try again in a few minutes." (en/fr); the cart, the checkout and the slot hold stay; the request's `Idempotency-Key` is released, so the same click works later | the customer retries; nothing to replay |
| saved cards, bank linking | 503 / an error in the Studio or account page | retry |
| capture on fulfilment | the listener's capture fails and is retried | captured with the same key |
| escrow release (transfers) | `payments.release_escrow` fails each minute | transfers go out on the next run |
| refunds | the refund queue retries each minute | paid |
| scheduled payouts | `payments.payouts` fails; `NorthlinePayoutRunStalled` after 30 min | the run catches up business by business (idempotent per business and day, `nl1:scheduled-payout:<merchant>:<date>`) |
| card hold renewals | retried each minute until 36 h before `capture_before` runs out | renewed; a hold that lapsed asks the customer to pay again (`payment.reauthorization_required`) |
| Stripe Tax transactions | stored `pending`, retried each minute, **10 attempts** | the nightly tax reconciliation reports what is still pending (`stillPending`); run it by hand (below) |
| webhooks | Stripe queues them and retries for up to 3 days | they arrive late, out of order; § 2 |
| instant payouts (Studio) | 503 | the merchant retries |

**Decide:**

```
status.stripe.com shows an incident?
├─ yes → it's Stripe: degrade (nothing to switch), communicate, wait; check the queues drain afterwards
└─ no  → is it only us?  curl from an api pod:  kubectl -n northline-<env> exec deploy/northline-api -- \
         curl -s -o /dev/null -w '%{http_code} %{time_total}\n' https://api.stripe.com/v1/charges   (401 = reachable)
         ├─ timeout / DNS error → our egress (NetworkPolicy, NAT gateway, firewall, proxy): fix it ([edge.md](edge.md))
         ├─ 401 fast → Stripe is fine from here: a restricted key lost a permission, or a rolled key (§ 5) — the logs say which
         └─ 429 → we are throttled: a runaway job? check `Payments job` logs and the request rate per endpoint
```

**Commands.** Nothing to toggle: the api degrades by itself. Watch it and the queues:

```sql
-- what is waiting on Stripe
select 'stripe events'  as what, count(*) from payments.stripe_events where state in ('received', 'failed')
union all select 'tax transactions', count(*) from payments.tax_transactions where state in ('pending', 'failed')
union all select 'payouts in flight', count(*) from payments.payouts where status in ('pending', 'in_transit');
```

After Stripe recovers, re-report tax transactions that used up their attempts (the reconciliation reports everything
still pending, whatever its attempts — [stripe.md § 6](stripe.md#6-stripe-tax-s-21)):

```sh
curl -X POST -H "Authorization: Bearer <staff token with acr=mfa>" -H 'Content-Type: application/json' \
  -d '{"period":"2026-Q4"}' https://<api host>/api/v1/console/payments/tax-reconciliations
```

**Verify.** Checkout answers 201 again (the burn alerts resolve); `northline_jobs_runs_total{outcome="succeeded"}` for
every payments step; the query above drains to its usual level within minutes; payouts due during the outage show
in the console's payouts view (the timeliness SLO will count them late — expected).

**Rollback.** Nothing was switched, nothing to roll back.

**Comms.** Status page / in-app banner: "Payments are temporarily unavailable because of an outage at our payment
provider. Nothing was charged; please try again shortly." (fr: « Les paiements sont temporairement indisponibles en
raison d'une panne chez notre fournisseur de paiement. Rien n'a été débité ; réessayez sous peu. »). Support macro
for businesses: payouts and releases due during the outage go out automatically once it ends. Finance: payouts paid
late (the timeliness SLO). No banner mechanism exists yet — post on the status page and social accounts.

**Exercised** (2026-10-02): `StripeOutageTest` — the real Stripe adapter against an address where nothing listens and
against a stand-in answering 500 → `payments_unavailable` with `Retry-After` 60, a 400 stays an ordinary failure;
`ProviderUnavailableTest` — the 503 ProblemDetail, its `Retry-After` header, English and French. **Not exercised:** a
real Stripe outage; the jobs' catch-up after one (their retry-with-the-same-key behaviour is S-11's, tested against
stripe-mock in `StripeMockFlowTest`); the `curl` from a pod (no cluster).

## 2. Webhook backlog or replay

**Symptoms.** Payouts stay "in transit" past their arrival date; disputes opened at Stripe don't show in Refunds &
disputes; `account.updated` changes (payouts enabled) don't reach the Studio; Stripe dashboard › Developers ›
Webhooks › the endpoint shows failed deliveries (4xx/5xx/timeouts) or "Disabled"; rows piling up in
`payments.stripe_events` as `received` / `failed`.

**Impact.** Northline's view lags Stripe's: payout states, disputes, connected account status. Money still moves
(our calls don't depend on webhooks); the payout reconciler settles in-transit payouts 24 h after their arrival
date without a webhook ([stripe.md § 5](stripe.md#5-webhooks-s-12)).

**Decide:**

```
Stripe › Webhooks › endpoint › failed attempts?
├─ 400 invalid_signature  → wrong STRIPE_WEBHOOK_SECRET / STRIPE_CONNECT_WEBHOOK_SECRET (after a roll? the other endpoint's?):
│                           fix the secret (key-rotation.md § 4), then resend from Stripe
├─ 503 webhooks_unconfigured → the secret is missing in that environment: set it, restart the api
├─ 429                    → over 600/min per address (STRIPE_WEBHOOK_RATE_LIMIT): a resend storm; raise it for the catch-up
├─ 5xx / timeouts         → the api is down or slow: it's an api incident (high-error-rate); Stripe keeps retrying 3 days
└─ all 200, but rows stay 'received'/'failed' in payments.stripe_events → our processing fails: read `error` (below),
                            fix, then requeue the rows (below)
Endpoint disabled by Stripe (failing for days) → re-enable it in the dashboard, then resend the missed events
```

**Commands.**

```sql
-- the backlog on our side, by state and type
select state, type, count(*), min(received_at), max(attempts) from payments.stripe_events
 where state in ('received', 'failed') group by 1, 2 order by 3 desc;
-- why they fail
select id, type, attempts, left(error, 200) from payments.stripe_events where state = 'failed' order by received_at desc limit 20;
-- after the fix: rows that used up their 10 attempts get them back; the payments job applies them within a minute,
-- each under SELECT … FOR UPDATE, idempotently (out-of-order rules of stripe.md § 5 apply)
update payments.stripe_events set attempts = 0 where state = 'failed' and attempts >= 10 and received_at > now() - interval '30 days';
```

Events Stripe never delivered (endpoint down longer than Stripe retries, or disabled) — Stripe CLI, logged in to the
right account and mode:

```sh
# list what happened during the gap (types the endpoints listen to), then resend each to the endpoint
stripe events list --created.gte=$(date -d '2026-10-02T06:00:00Z' +%s) --limit 100 --live    # --live only for prod
stripe webhook_endpoints list --live                                                       # → we_… of the endpoint
stripe events resend evt_1Q… --webhook-endpoint=we_1P… --live
# connected-account events (payout.*, account.updated) → the Connect endpoint, with the account:
stripe events resend evt_1Q… --webhook-endpoint=we_1P… --stripe-account=acct_1K… --live
```

A resent event is deduplicated on its id (`payments.stripe_events` primary key): resending one Northline already has
is harmless — the answer says `"duplicate": true`.

**Verify.** The backlog query returns nothing older than a few minutes; the endpoint's recent deliveries in Stripe are
200; the payouts that were stuck show paid / failed; disputes appear in the console.

**Rollback.** None needed: applying an event twice is impossible (one row per event id, state machine forward only).
If the requeue `update` matched too much, the extra rows simply get applied or fail again.

**Comms.** Usually none. If payouts showed "in transit" to businesses for long: support macro "your payout's status was
delayed; the money moved on time". If disputes arrived late: the dispute's reply-by date is Stripe's evidence deadline
− 2 days — tell the affected businesses directly.

**Exercised** (2026-10-02): `StripeWebhookApiTest` (S-12) re-run — signed fixtures through the real verification:
wrong/other-endpoint/stale signatures 400, a duplicate delivery applied once, out-of-order payouts and disputes,
unknown events ignored. **Not exercised:** the requeue `update` (written against the S-12 schema and the job's query
`state in ('received','failed') and attempts < 10`); the Stripe CLI `events list` / `resend` steps (no account).

## 3. Stripe and the ledger disagree

**Symptoms.** The nightly reconciliation (04:41 platform zone, `PAYMENTS_RECONCILE_CRON`) marks a day **mismatch**:
console › Finance › Reconciliation · daily, or `select day, state from payments.reconciliation_days where state =
'mismatch' order by day desc`. Finance asks why the bank deposit differs from the ledger.

**Impact.** Usually none (timing). A real difference means money moved at Stripe that the ledger doesn't know about
(a refund or dispute outside Northline, a lost webhook) or the reverse (a posting without its Stripe call) — the
books are wrong until corrected.

**Decide** (per difference, console › Reconciliation › the day, or `payments.reconciliation_items`):

```
missing_in_ledger
├─ a refund (re_…) made in the Stripe dashboard by hand → book it through Northline's refund flow next time; post a
│    correcting entry with finance (accountant), mark the day resolved with the note
├─ a dispute withdrawal (dp_…) whose webhook was lost → § 2 (resend charge.dispute.created / funds_withdrawn); re-run the day
└─ a charge captured near midnight → timing: re-run tomorrow
missing_at_stripe
├─ a capture/refund posted but its Stripe call failed and is still retrying → wait for the job; re-run the day
└─ a payout whose payout.failed / canceled webhook never came → § 2; the reconciler settles it 24 h after arrival
amount_differs → a partial refund or partial capture: compare the Stripe object with payments.refunds / escrows;
                 a code bug if it repeats — open an issue with the ids, resolve the day with the explanation
```

**Commands.**

```sh
# re-run one ended day (platform-zone date); needs a staff session with the 'payouts' permission and acr=mfa
curl -X POST -H "Authorization: Bearer <staff token>" https://<api host>/api/v1/console/payments/reconciliation/days/2026-10-01/run
# resolve it with finance's note once explained
curl -X POST -H "Authorization: Bearer <staff token>" -H 'Content-Type: application/json' \
  -d '{"note":"Refund re_… made by hand in Stripe; correcting entry JE-2026-10-03"}' \
  https://<api host>/api/v1/console/payments/reconciliation/days/2026-10-01/resolve
# the differences and the ledger for the accountant (CSV, ≤ 92 days)
curl -H "Authorization: Bearer <staff token>" "https://<api host>/api/v1/console/payments/reconciliation/export?from=2026-09-01&to=2026-09-30" -o recon.csv
```

(The same three are buttons in the console; every run, resolution and export is in the audit log.)

**Verify.** The day re-run is `matched`, or `resolved` with a note finance agrees with; the next nightly run doesn't
reopen it.

**Rollback.** A resolution is a note, not a correction: nothing to undo. Ledger corrections are new entries, never
edits.

**Comms.** Finance owns the conversation with the accountant; no customer or business comms unless a business's money
was affected (then support, with finance's numbers).

**Exercised** (2026-10-02): `StripeReconciliationApiTest` (S-85) re-run — a matched day, a mismatch with each kind of
difference against the fake balance transactions, resolve with a note, exports, roles. **Not exercised:** the real
`GET /v1/balance_transactions` (stripe-mock only); the console buttons by hand.

## 4. Dispute spike

**Symptoms.** Many `charge.dispute.created` in a short time; Refunds & disputes fills with card-dispute cases;
`dispute.updated` "opened" emails to businesses; Stripe emails about the dispute rate. Check:

```sql
select date_trunc('hour', created_at) as hour, count(*) from payments.stripe_events
 where type = 'charge.dispute.created' and created_at > now() - interval '7 days' group by 1 order by 1 desc;
```

Which businesses: console › Refunds & disputes, filter card disputes; or the Stripe dashboard › Payments › Disputes
(search by `metadata[northline_merchant_id]`).

**Impact.** Each dispute holds the escrow and costs Stripe's dispute fee; a lost dispute is charged to the business up
to the escrow, Northline carries the tax part ([stripe.md § 5](stripe.md#5-webhooks-s-12)). A dispute rate above
Stripe's monitoring thresholds (around 0.75 % of charges) puts the platform account in a monitoring programme —
fines, and eventually the account.

**Decide:**

```
concentrated on one business?
├─ yes → fraud by or against that business (stolen cards, items not delivered):
│        hide it from search and suspend it (console › Sellers › oversight, audited); payouts pause while suspended;
│        trust & safety reviews; evidence through the cases as usual
└─ no  → many businesses, same card BIN / country / time → card testing or a stolen-card wave:
         tighten Radar (block :risk_level: = 'elevated' too, 3-D Secure for all), consider pausing new customers' checkout
         by market (console › Markets: Pilot/Waitlist) until it stops
same reason everywhere ("product not received")? → a fulfilment failure (couriers, a kitchen): fix it; proactive refunds
                                                   avoid the dispute fee
```

**Commands.** Suspend / reinstate a business (staff with the oversight permission, acr=mfa; reason required):

```sh
curl -X POST -H "Authorization: Bearer <staff token>" -H 'Content-Type: application/json' \
  -d '{"reason":"Dispute spike INC-…"}' https://<api host>/api/v1/console/merchants/<business id>/suspend
curl -X POST … https://<api host>/api/v1/console/merchants/<business id>/reinstate
```

Radar rules: Stripe dashboard › Radar › Rules (by hand; not managed as code). Evidence: still submitted by an agent
in the Stripe dashboard (not built in Northline — DECISIONS § S-12).

**Verify.** The hourly count falls back to normal; Stripe's dispute rate (dashboard › Disputes › overview) under the
threshold; the suspended business's new orders stop (search and checkout refuse it).

**Rollback.** Reinstate the business; remove the extra Radar rules once the wave is over (they also block good
customers).

**Comms.** The business (support, before suspending when it isn't the fraudster); customers affected by a fulfilment
failure (support offers refunds); Stripe, if they reach out about the dispute rate (finance + the platform's Stripe
contact). Legal for large fraud.

**Exercised** (2026-10-02): `StripeWebhookApiTest` dispute flows re-run — created → updated → lost, closed before
created, a dispute joining the customer's case, a late older update ignored. **Not exercised:** Radar, Stripe's
monitoring programme, a real spike; the suspend endpoint's own tests are S-82's (`SellerOversightApiTest`), not re-run
as part of this drill.

## 5. Leaked keys

**Symptoms.** A `sk_live_…` / `rk_live_…` / `whsec_…` in a commit, a log, a ticket, a screenshot, a chat; GitHub secret
scanning or Stripe's own leak notice; API requests in Stripe › Developers › Logs that Northline didn't make (unknown
IPs, unusual endpoints).

**Impact.** A secret or restricted key lets anyone act as Northline's platform: create transfers and payouts, read
customers. A webhook secret lets anyone forge events to our endpoints (payout states, disputes). The publishable key
(`pk_…`) is public by design — not an incident.

**Decide:**

```
which key?
├─ secret / restricted key (sk_/rk_) → roll NOW with "expire immediately" — accept failed calls for the minutes until
│                                     the new key is deployed (jobs retry with the same idempotency keys; checkout 503s)
├─ webhook secret (whsec_)          → roll with no overlap ("expire now"); events signed with the old one are refused
│                                     (400) and Stripe retries them, so nothing is lost once the new secret is live
└─ test-mode key (sk_test_)         → roll it too (in dev/staging), lower urgency
```

**Commands.** Roll in Stripe: Developers › API keys › the key › **Roll key…** › expiration **Now** (or Developers ›
Webhooks › endpoint › **Roll secret** › now). Then put the new value in the environment's secrets manager and restart
the api — the per-cloud commands, the force-sync and the restart are in
[key-rotation.md § 4 and § 6](key-rotation.md#4-stripe-api-keys-and-webhook-secrets). In short (AWS):

```sh
aws secretsmanager put-secret-value --secret-id northline/prod/stripe-secret-key --secret-string 'rk_live_…'
kubectl -n northline-prod annotate externalsecret northline-api force-sync=$(date +%s) --overwrite
kubectl -n northline-prod rollout restart deploy/northline-api && kubectl -n northline-prod rollout status deploy/northline-api
```

Then: Stripe › Developers › Logs, filter by the old key's period and unknown IPs — what did they call? Transfers or
payouts you don't recognise: contact Stripe support at once (they can reverse some), and finance. Remove the leaked
value where it was posted (rewrite the commit history only with security's agreement; the key is dead anyway).

**Verify.** Stripe › Developers › API keys shows the old key expired; the api's log has no `401`/`Invalid API Key`
after the restart; checkout and the payments job succeed; for a webhook secret, the endpoint's next deliveries are
200 (`StripeEventVerifier` accepts the new one).

**Rollback.** None for a leak (the old key must die). If the new value was entered wrong, the api fails Stripe calls
(401) — fix the value in the secrets manager and restart again; jobs catch up.

**Comms.** Security lead and the CTO at once; incident note with the timeline (when leaked, when rolled). If Stripe
logs show misuse: legal and finance; affected businesses and customers per the privacy incident procedure. No public
comms for a leak without misuse.

**Exercised** (2026-10-02): **not exercised** — needs a Stripe account (rolling a key) and a cluster (the External
Secrets refresh; the S-6 kind rehearsal covers the refresh → restart path for another secret, [secrets.md § Rotation](secrets.md#rotation)).
The api's behaviour with a wrong key (Stripe answers 401 → `StripeCallFailed`, not `payments_unavailable`) follows from
`StripeOutageTest` (a 4xx is not an outage).
