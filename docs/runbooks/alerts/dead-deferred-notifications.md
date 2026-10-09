# Dead deferred notifications

**Alert:** `NorthlineDeadDeferredNotifications` · threshold — ticket · [all alerts](README.md) · [alerting](../alerting.md)

**What it measures.** `northline_notifications_deferred_dead{channel}` (engineering follow-ups, S-115 gap): SMS, push
and email that quiet hours or a provider outage held back in `messaging.deferred_notifications` and that the worker
gave up after their ten attempts (the table's own dead letters, [events.md § Deferred
notifications](../events.md#deferred-notifications-the-tables-own-dead-letters-s-115)). Every worker replica counts
them from the database once a minute (one grouped query); the alert takes the largest count per channel and fires
when dead rows have waited 15 minutes.

**Impact.** Those people never got the message: a booking reminder, a "your order is on its way" text, a payout
notice. Nothing else is held up. Dead rows are deleted 30 days after they died
(`northline.notifications.deferred-dead-retention`), so the window to requeue them is that long.

## First checks

1. Which channel and why: the query in [events.md](../events.md#deferred-notifications-the-tables-own-dead-letters-s-115)
   (`channel`, `event_type`, `count`, `max(last_error)`), or
   `./gradlew :worker:dlqReplay --args='list --deferred --channel=<channel> --since=24h'`.
2. `last_error` names the failure's class: `SmsDeliveryFailed` → the SMS provider (status page, credentials,
   [notifications.md](../notifications.md)); a push error → [push.md](../push.md); email → [email.md](../email.md).
3. The worker log: `DEAD-LETTERED deferred <channel> <type> for user <id> after 10 attempts`.

## Mitigate

Fix the provider first (the rows would only die again). Then requeue them — not in the middle of the night for SMS or
push (requeued rows are not held for quiet hours again):

```sh
./gradlew :worker:dlqReplay --args='replay --deferred --channel=<channel> --since=24h --actor=<you> --reason=<ticket>'
```

The job sends them within a minute; one audit entry `notifications.deferred_requeued` per run. The alert resolves
when the gauge is back to 0 (next minute). Rows that no longer matter (a reminder for yesterday's booking) can be left
to the purge — the alert then stays until they are deleted, so acknowledge the ticket with that decision.

## Afterwards

Write down what happened in the incident notes (provider, how long, how many people missed a message); if the alert
was noise, tune it in `deploy/observability/` (make obs-rules-check) rather than silencing it for good. *Stub
(engineering follow-ups): never exercised in a real incident — improve it the first time it is used.*
