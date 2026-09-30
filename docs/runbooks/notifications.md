# Runbook — team notifications: email, SMS, push (S-13, S-27)

Who tells a business's team about money events, on which channel, and how the Settings › Notifications matrix and
quiet hours apply. Events and the consumer framework: [events.md](events.md). Email providers: [email.md](email.md).
SMS providers: [README § SMS and voice codes](README.md#sms-and-voice-codes-s-8).

## 1. Who sends what

Nothing is sent twice: every delivery is claimed in `events.processed_events` as `(channel, <eventId>:<userId>)` before
the provider is called — the api's S-13 `Mailer` and the worker use the same claims.

| event (Kafka topic) | row | to | api (after commit, S-13) | worker `notifications` consumer (S-27) |
|---|---|---|---|---|
| `payout_account.changed` (`payments.payout_account`) | — security | owners | email, always | **SMS, always** — whatever the matrix, even in quiet hours |
| `payout.sent` (`payments.payout`) | `payout` | owners, bookkeepers | email | SMS, push |
| `payout.failed` (`payments.payout`) | `payout` | owners, bookkeepers | — | **email** (template `payout-failed`), SMS, push |
| `dispute.updated`, `dispute.decided` (`payments.dispute`) | `dispute` | owners | email | SMS, push |
| `refund.case_updated`, `refund.issued` (`payments.refund`) | `dispute` | owners | email | SMS, push |
| team invitation to a mobile number | — | the invitee | **SMS** (the link's token never leaves the api) | — |
| `custom_domain.changed` with a `notice` (`merchants.storefront`, S-31): the page's own domain went live, stopped pointing at Northline (with the grace deadline), was disconnected, failed its certificate, expired or was claimed by another business | — service notice | owners | **email** (template `custom-domain`), whatever the matrix — [custom-domains.md](custom-domains.md) | — |
| a webhook endpoint turned off after 3 days of failures (worker dispatcher, not a Kafka event — S-33, [webhooks.md](webhooks.md)) | — service notice | owners | — | **email** (template `webhook-disabled`), whatever the matrix; claimed per owner under the endpoint's `disable_notice_id`, retried every minute for 2 days while the provider is down |

The emails S-13 built stay in the api (they're tested there and send right after commit); the worker adds what S-13
left open: the SMS and push channels, and the `payout.failed` email. Technicians and cooks get no money notices. Rows
without events yet (`new_booking`, `quote_request`, `customer_message`, `low_stock`, `quality`) are not wired: their
events aren't published to Kafka yet.

## 2. Preferences and quiet hours

- Each member's own matrix decides (`messaging.notification_prefs`; members who never saved one get the defaults of
  `docs/spec/notification-matrix-defaults.json`, the file the api's `NotificationMatrix` is tested against). A cell
  off = nothing on that channel. Security notices ignore the matrix.
- **Quiet hours** (21:00–07:00 America/Edmonton, stored per member) hold back **SMS and push**, never email. A held
  notification is written to `messaging.deferred_notifications` (V074) and sent by the worker's job (every minute)
  when the quiet hours end — after re-reading the member (still on the team, active account) and their matrix (a cell
  turned off meanwhile cancels it). The bank-account SMS is never held.
- Contact details are read when sending, never carried in events: the worker reads `identity.users`,
  `merchants.merchant_members`, `merchants.merchants` and `messaging.notification_prefs` read-only (same database,
  like northline-auth).

## 3. Failures

| what | email | SMS | push (stub) |
|---|---|---|---|
| bad / opted-out / suppressed address or number | logged, claim kept, not retried | same | — |
| provider down, throttling, credentials | claim released; the event is retried (10 s, 60 s, 5 min on `<topic>.notifications.retry-<n>`), then `.dlq` + alert | same | — |
| deferred notification can't be sent | — | retried every 5 min, given up after 10 attempts with `DEAD-LETTERED deferred …` (ERROR) | same |

A retry sends only what is missing (sent deliveries stay claimed). A dead-lettered event is replayed with
`DlqReplayCommand` ([events.md § 3](events.md#3-dlq-investigate-and-replay)), group `notifications`.

Metrics: `northline_notifications_sent_total{channel,type,outcome=sent|already_sent|unreachable}` plus the consumer's
(`northline_events_*`, lag).

## 4. Push

**Stub.** No push provider and no device registration exist yet (no app ships push). The worker applies the matrix,
quiet hours and once-per-member rules and hands each push to `PushSender`, whose only implementation logs
`PUSH (stub — no push provider yet) to user … : <title>`. A real adapter (FCM/APNs, or one provider in front of both)
replaces that bean; nothing else changes.

## 5. Configuration

The worker uses the same variables as the api for email (`EMAIL_*`, `SMTP_*`, `EMAIL_UNSUBSCRIBE_KEY` — **the same
key**, since the api verifies the unsubscribe links the worker signs — `API_PUBLIC_URL`, `STUDIO_ORIGIN`) and the same
as northline-auth for SMS (`SMS_*`). The api now also needs `SMS_*` for invitations. Staging and prod refuse `local`
for both and list them among the required variables. Chart: `apps.worker.secretEnv` maps `EMAIL_UNSUBSCRIBE_KEY`
(required in staging/prod), `EMAIL_API_KEY`, `SMTP_PASSWORD`, `SMS_AUTH_TOKEN`; the cloud overlays set the worker's
`EMAIL_PROVIDER` like the api's.

Locally everything works without accounts: email to Mailpit (`--profile mail`) or the log, SMS to the worker/api log
(`[SMS] to +1 403 *** **48: Northline: …`), push to the log.

## 6. Tests

`NotificationsConsumerTest` (worker, Kafka 4 + PostGIS): `payout.failed` → exactly one email and one push per finance
member in their language, none for technicians, a redelivered event sends nothing more; each member's matrix; quiet
hours hold SMS/push until morning (email isn't held) and the job sends them once; the bank-change SMS goes out at night
whatever the matrix; a provider outage is retried and only the missing text is sent; a provider that stays down ends in
the DLQ with the claims released; an undeliverable number isn't retried. api: `TeamInvitationEmailTest` (a mobile
invitation is texted once, in the inviter's language), `NotificationMatrixDefaultsSpecTest`. Library: `server/sms`
(`SmsTransportsTest`, `TwilioSmsTransportTest`), auth's S-8 SMS tests unchanged over the shared adapters, email
templates incl. `payout-failed` in en/fr.
