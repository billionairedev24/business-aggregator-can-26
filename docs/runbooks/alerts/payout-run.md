# Payout run

**Alerts:** `NorthlinePayoutRunFailureBurn`, `NorthlinePayoutRunStalled`, `NorthlinePayoutRunMissing` · SLO burn / threshold — page / ticket · [all alerts](README.md) · [alerting](../alerting.md)

**What it measures.** The payments job (every minute on every api replica: escrow releases, Stripe events, refunds, payouts) — 99 % of payout runs succeed (SLO); the run last succeeded under 30 min (ticket) / 2 h (page) ago; some replica reports it at all (ticket after 30 min).

**Impact.** Merchants' payouts, escrow releases and refunds wait. Money is safe (nothing is lost), but late.

## First checks

1. The api logs: `Payments job 'payouts' failed; retrying next run` with the exception.
2. `northline_jobs_runs_total{job=~"payments.*"}` by `outcome`: one step failing or all of them (the database, Stripe).
3. No series at all (`NorthlinePayoutRunMissing`): are api pods running with a profile other than `test`? Is the scheduler stuck (thread dump)?
4. Stripe Connect errors (account disabled, insufficient balance): [stripe.md](../stripe.md).

## Mitigate

During a Stripe outage the run fails every minute and catches up afterwards with the same idempotency keys
([stripe-incidents.md § 1](../stripe-incidents.md#1-stripe-is-down-degrade-checkout-what-queues)); payouts stuck "in
transit" without webhooks: [stripe-incidents.md § 2](../stripe-incidents.md#2-webhook-backlog-or-replay).

Fix the cause; the next run (≤ 1 min) catches up — the run is idempotent per business and day.

## Afterwards

Write down what happened in the incident notes (cause, impact, time to detect and fix); if the alert was noise, tune
it in `deploy/observability/` (make obs-rules-check) rather than silencing it for good. *Stub (S-113): never exercised
in a real incident — improve it the first time it is used.*
