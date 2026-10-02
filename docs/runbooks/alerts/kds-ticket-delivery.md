# KDS ticket delivery

**Alerts:** `NorthlineKdsTicketDeliveryBurn` · SLO burn — page / ticket · [all alerts](README.md) · [alerting](../alerting.md)

**What it measures.** KDS ticket delivery: 99.5 % of food orders are signalled to the kitchen's display within 5 s of being placed, over 30 days.

**Impact.** Kitchens see new orders late (the screen polls every 15 s only when its live stream is down) — food gets cold, couriers wait.

## First checks

1. The outbox ([outbox-backlog](outbox-backlog.md)): the signal is a module listener after commit; a backlog delays it.
2. Valkey (`LIVE_BUS=redis`): latency, connections.
3. Dashboard **kitchens** (`northline-kitchens`): `northline_kds_ticket_delivery_seconds`.

## Mitigate

Fix the outbox or Valkey. Tell affected kitchens to refresh the screen.

## Afterwards

Write down what happened in the incident notes (cause, impact, time to detect and fix); if the alert was noise, tune
it in `deploy/observability/` (make obs-rules-check) rather than silencing it for good. *Stub (S-113): never exercised
in a real incident — improve it the first time it is used.*
