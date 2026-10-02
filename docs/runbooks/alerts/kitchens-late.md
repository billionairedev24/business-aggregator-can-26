# Kitchens late

**Alerts:** `NorthlineKitchensLate` · threshold — ticket · [all alerts](README.md) · [alerting](../alerting.md)

**What it measures.** Over 30 % of kitchen orders are ready after the promised time for 15 minutes.

**Impact.** Customers wait longer than promised.

## First checks

1. The **kitchens** dashboard; a kitchen keeps its default prep too low (Settings › Kitchen hours), or the KDS isn't used live.

## Mitigate

Merchant success contacts the kitchen; nothing is broken on Northline's side.

## Afterwards

Write down what happened in the incident notes (cause, impact, time to detect and fix); if the alert was noise, tune
it in `deploy/observability/` (make obs-rules-check) rather than silencing it for good. *Stub (S-113): never exercised
in a real incident — improve it the first time it is used.*
