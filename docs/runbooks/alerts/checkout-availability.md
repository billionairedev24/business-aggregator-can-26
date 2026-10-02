# Checkout availability

**Alerts:** `NorthlineCheckoutAvailabilityBurn` · SLO burn — page / ticket · [all alerts](README.md) · [alerting](../alerting.md)

**What it measures.** Checkout availability: 99.9 % of checkout calls (`POST /api/v1/me/checkouts`, `…/{checkoutId}/place`, `/api/v1/me/bookings/checkout`) don't fail with a 5xx, over 30 days.

**Impact.** Customers can't pay: orders, food orders and booking deposits are lost revenue. Declined cards (4xx) don't count.

## First checks

1. Dashboard **checkout and payouts** (`northline-checkout-payouts`): checkouts by status, Stripe call errors (`http_client_requests` to `api.stripe.com`).
2. Stripe status page; the api's logs for `StripeException` by `trace.id` ([stripe.md](../stripe.md)).
3. Tax provider (`TAX_PROVIDER=stripe`): Stripe Tax errors fail the quote and the checkout ([stripe.md § 6](../stripe.md#6-stripe-tax-s-21)).
4. Database: escrow writes, idempotency keys in Valkey.

## Mitigate

Roll back a bad release. During a Stripe outage there is no fallback — communicate; holds retried by the customer are idempotent.

## Afterwards

Write down what happened in the incident notes (cause, impact, time to detect and fix); if the alert was noise, tune
it in `deploy/observability/` (make obs-rules-check) rather than silencing it for good. *Stub (S-113): never exercised
in a real incident — improve it the first time it is used.*
