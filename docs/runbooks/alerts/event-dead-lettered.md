# Event dead-lettered

**Alerts:** `NorthlineEventDeadLettered` · threshold — page · [all alerts](README.md) · [alerting](../alerting.md)

**What it measures.** A worker consumer moved an event to its `.dlq` topic (S-26) after its retries (or at once for a
poison event): `increase(northline_events_dead_lettered_total[15m]) > 0`, labels `consumer` and `topic`.

**Impact.** That event's effect for that consumer group did not happen — a notification, a partner webhook, a search
document. The other groups processed it; nothing is lost (the `.dlq` keeps it 30 days).

## First checks

1. The worker log line `DEAD-LETTERED consumer=<group> event=<id> type=<type> topic=<topic> dlq=…@<offset> reason=…`
   (search the logs for `DEAD-LETTERED`), or list the group's DLQ — read-only:
   `./gradlew :worker:dlqReplay --args='list --topic=<topic>.dlq --group=<group> --since=1h'`.
2. Poison (`PoisonEventException`) or a handler failure? Many at once in one window = a provider outage (SMTP,
   Twilio, APNs/FCM, Elasticsearch); one type only = a bug.
3. Also check the deferred notifications' own dead rows (`DEAD-LETTERED deferred …`, not counted by this alert).

## Mitigate

Follow the decision tree in [events.md § 3](../events.md#3-dlq-investigate-and-replay): fix the cause (deploy the fix,
wait for the provider), then replay with filters, a gentle rate and your name on it:

```sh
./gradlew :worker:dlqReplay --args='replay --topic=<topic>.dlq --group=<group> --since=2h --rate=10 --actor=<you> --reason=<incident>'
```

Search indexer: a partial reindex of the merchants concerned does the same ([search.md § 9](../search.md#runbook-reindex-partial-reindex-rollback-s-115)).
Dead deferred notifications: `list|replay --deferred` ([events.md](../events.md#deferred-notifications-the-tables-own-dead-letters-s-115)).

## Afterwards

Write down what happened in the incident notes (cause, impact, time to detect and fix, the replay's audit entry
`events.dlq_replayed`); if the alert was noise, tune it in `deploy/observability/` (make obs-rules-check) rather than
silencing it for good. *Filled in S-115 from the DLQ replay drill (tests); not yet used in a real incident.*
