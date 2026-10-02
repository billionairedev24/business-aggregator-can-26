# Alert runbooks (S-113)

One page per alert (or alert family). Every rule in `deploy/observability/prometheus/rules` links here through its
`runbook_url` annotation; `scripts/validate-alerts.py` fails when a link points nowhere or a page has no alert.
Routing, SLOs and the on-call rota: [alerting.md](../alerting.md).

| page | alerts | severity |
|---|---|---|
| [sign-in-availability](sign-in-availability.md) | NorthlineSignInAvailabilityBurn | SLO burn — page / ticket |
| [sign-in-latency](sign-in-latency.md) | NorthlineSignInLatencyBurn | SLO burn — page / ticket |
| [checkout-availability](checkout-availability.md) | NorthlineCheckoutAvailabilityBurn | SLO burn — page / ticket |
| [checkout-latency](checkout-latency.md) | NorthlineCheckoutLatencyBurn | SLO burn — page / ticket |
| [payout-run](payout-run.md) | NorthlinePayoutRunFailureBurn, NorthlinePayoutRunStalled, NorthlinePayoutRunMissing | SLO burn / threshold — page / ticket |
| [payout-timeliness](payout-timeliness.md) | NorthlinePayoutTimelinessBurn | SLO burn — page / ticket |
| [kds-ticket-delivery](kds-ticket-delivery.md) | NorthlineKdsTicketDeliveryBurn | SLO burn — page / ticket |
| [kds-freshness](kds-freshness.md) | NorthlineKdsFreshnessBurn | SLO burn — page / ticket |
| [outbox-backlog](outbox-backlog.md) | NorthlineOutboxBacklog (ticket), NorthlineOutboxStuck (page) | threshold |
| [event-dead-lettered](event-dead-lettered.md) | NorthlineEventDeadLettered | threshold — page |
| [consumer-lag](consumer-lag.md) | NorthlineConsumerLag (ticket), NorthlineConsumerLagCritical (page) | threshold |
| [high-error-rate](high-error-rate.md) | NorthlineHighErrorRate | threshold — page |
| [slow-requests](slow-requests.md) | NorthlineSlowRequests | threshold — ticket |
| [sign-in-failures](sign-in-failures.md) | NorthlineSignInFailures | threshold — ticket |
| [lockout-spike](lockout-spike.md) | NorthlineLockoutSpike | threshold — ticket |
| [payout-failed](payout-failed.md) | NorthlinePayoutFailed | threshold — ticket |
| [kitchens-late](kitchens-late.md) | NorthlineKitchensLate | threshold — ticket |
| [retention-not-run](retention-not-run.md) | NorthlineRetentionNotRun, NorthlineRetentionMissing | threshold — ticket |

These are stubs written with the alerts (S-113); none has been used in a real incident yet.
