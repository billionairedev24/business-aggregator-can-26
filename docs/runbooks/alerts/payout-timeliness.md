# Payout timeliness

**Alerts:** `NorthlinePayoutTimelinessBurn` · SLO burn — page / ticket · [all alerts](README.md) · [alerting](../alerting.md)

**What it measures.** Payout timeliness: 99 % of scheduled payouts are sent within 1 h of the business's payout time (its own time zone, from the region model), over 30 days.

**Impact.** Businesses get their money late — support tickets, trust.

## First checks

1. Usually follows [payout-run](payout-run.md): runs failing or not running at the payout time.
2. `northline_payouts_delay_seconds` on the **checkout and payouts** dashboard.
3. Many businesses due at once after an outage: the run catches up business by business.

## Mitigate

During a Stripe outage the run fails every minute and catches up afterwards with the same idempotency keys
([stripe-incidents.md § 1](../stripe-incidents.md#1-stripe-is-down-degrade-checkout-what-queues)); payouts stuck "in
transit" without webhooks: [stripe-incidents.md § 2](../stripe-incidents.md#2-webhook-backlog-or-replay).

As payout-run. Tell finance which businesses were paid late (the console's payouts view).

## Afterwards

Write down what happened in the incident notes (cause, impact, time to detect and fix); if the alert was noise, tune
it in `deploy/observability/` (make obs-rules-check) rather than silencing it for good. *Stub (S-113): never exercised
in a real incident — improve it the first time it is used.*
