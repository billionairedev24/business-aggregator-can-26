# Payout failed

**Alerts:** `NorthlinePayoutFailed` · threshold — ticket · [all alerts](README.md) · [alerting](../alerting.md)

**What it measures.** A payout failed or was canceled by Stripe in the last hour.

**Impact.** A business didn't get its money; the amount is back on its balance and it got the payout.failed email (S-27).

## First checks

1. [stripe.md](../stripe.md): the failure code (account closed, invalid account number).

## Mitigate

Finance contacts the business if it doesn't update its bank account.

## Afterwards

Write down what happened in the incident notes (cause, impact, time to detect and fix); if the alert was noise, tune
it in `deploy/observability/` (make obs-rules-check) rather than silencing it for good. *Stub (S-113): never exercised
in a real incident — improve it the first time it is used.*
