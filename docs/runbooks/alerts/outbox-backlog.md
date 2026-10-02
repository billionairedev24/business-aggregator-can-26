# Outbox backlog

**Alerts:** `NorthlineOutboxBacklog (ticket)`, `NorthlineOutboxStuck (page)` · threshold · [all alerts](README.md) · [alerting](../alerting.md)

**What it measures.** The oldest incomplete event publication (Modulith outbox: `events.event_publication` in the api, `auth.event_publication` in auth) is older than 5 min (ticket) / 30 min (page).

**Impact.** Events don't leave: Kafka consumers (notifications, search, webhooks), kitchens' live signals, couriers' dispatch and partners' webhooks lag behind.

## First checks

1. `northline_events_outbox_pending` by application; the api's `/actuator/modulith` (event publications).
2. Kafka reachable? The externalizer's producer errors in the app logs.
3. One listener failing on every retry: `select listener_id, count(*) from events.event_publication where completion_date is null group by 1;` ([events.md](../events.md)).

## Mitigate

Fix Kafka or the failing listener; outstanding publications are resubmitted on restart (`republish-outstanding-events-on-restart`).

## Afterwards

Write down what happened in the incident notes (cause, impact, time to detect and fix); if the alert was noise, tune
it in `deploy/observability/` (make obs-rules-check) rather than silencing it for good. *Stub (S-113): never exercised
in a real incident — improve it the first time it is used.*
