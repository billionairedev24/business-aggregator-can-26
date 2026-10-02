# Data retention (S-107)

The Privacy Policy's section 6, "How long we keep it" (`web/packages/legal/pages/privacy.html`, shipped verbatim from
design 10), carried out every night. Erasure on request is [privacy-requests.md](privacy-requests.md) (S-105); this
page is what happens to data nobody asked about once its period ends.

## 1. The schedule is configuration

`server/api/src/main/resources/privacy/retention-schedule.yml` — one entry per category of data: the policy's clause
and its words (verbatim), the period and what starts its clock, the legal basis (en/fr), the action at expiry
(`delete`, `pseudonymise` — the person's id and every personal field go, amounts and dates stay — or `aggregate`),
the owning module, the legal holds that stop it, and who enforces it. **The periods are the policy's, not environment
variables**: change the policy first (counsel, design 10), then the file. `RetentionScheduleTest` fails while the two
disagree (a clause with no category, a misquoted clause, a period the clause doesn't state) and when a category with a
job has no module to run it.

| category | policy clause | kept | clock starts | action | module · enforcement | legal holds |
|---|---|---|---|---|---|---|
| `account.closed_profile` | Account profile | 30 days | the account's closure (an erasure starts) | pseudonymise (S-105 pipeline) | privacy · pipeline | the erasure's own (open order, upcoming booking, held escrow, dispute, refund, delivery, sole owner) |
| `orders.sales_records` | Transaction and tax records | 7 years | the order placed | pseudonymise: customer, address, area; checkout snapshots deleted | orders · job | order under way |
| `booking.sales_records` | Transaction and tax records | 7 years | the booking's date | pseudonymise: customer, address, details, access notes; quote requests' customer and description; accepted-quote snapshots deleted | booking · job | upcoming booking |
| `payments.financial_records` | Transaction and tax records | 7 years | the payment | pseudonymise: customer id and name on escrows, payments, refunds, disputes; refund and dispute words | payments · job | held escrow, dispute, refund |
| `messaging.conversations` | Messages and dispute evidence | 2 years; a dispute: 1 year after its decision | the last message | delete: messages, attachment rows, files | messaging · job | order, booking, delivery under way; dispute |
| `messaging.help_cases` | Messages and dispute evidence | 2 years; a dispute: 1 year after | the case resolved (photos: uploaded) | delete the conversation and files; the case keeps number, topic, dates, amounts | messaging · job | order, booking under way; dispute |
| `payments.dispute_evidence` | Messages and dispute evidence | 2 years; 1 year after the decision; + the province's minimum | the transaction disputed | pseudonymise: statements, response, note; evidence files deleted | payments · job | dispute |
| `fulfilment.delivery_proofs` | Messages and dispute evidence | 2 years; 1 year after a decision; + the province's minimum | the drop-off | delete the photo / signature (the stop keeps how it was proven) | fulfilment · job | order, delivery under way; dispute |
| `booking.checkin_locations` | GPS check-in records | 90 days; until a dispute is decided; + the province's minimum | the check-in | delete the location (the transition stays) | booking · job | upcoming booking; dispute |
| `identity.sign_ins` | Login and security logs | 12 months | the sign-in's last activity | delete | identity · job | — |
| `merchants.kyc_records` | KYC documents | 5 years after the relationship ends | the relationship ending | pseudonymise | merchants · **blocked** (no business can end yet) | sole owner |
| `trust.reviews` | Reviews | while public | — | pseudonymised on erasure (S-105) | trust · none | — |
| `infrastructure.backups` | Backups | 35 days | the deletion | delete (provider settings) | Terraform · infrastructure | — |
| `developer.audit_log` | *not in the policy* | 7 years | the action | delete (V181's trigger lets only this purge) | developer · job | — |
| `messaging.consent_records` | *not in the policy's section 6* (CASL, S-108) | 3 years after the withdrawal | the consent's withdrawal | delete (`ConsentRetention.purgeExpiredProofs`; an active consent's proof stays) | messaging · job | — |

Every job also leaves alone the data of a person with a **privacy request still open** (PIPEDA s. 8(8) and the
provincial acts: what a request is about is kept until the person has had their answer).

**The law of the person's province.** `region.privacy_laws.decision_retention_days` (V301) is what a law adds after a
decision about a person: categories marked `lawMinimum` (dispute evidence, delivery proofs, check-in locations) keep
what decided a dispute at least that many days after the decision, by the customer's province (their default
address, else the configured default — the same rule as S-105). Values drafted from the statutes, for legal review:
BC PIPA 365 (s. 35(1)); PIPEDA, Alberta PIPA and Québec 0 (no number in the act). No province is in code.

**Other automatic deletions** (short-lived technical data, already deleted by their own jobs, listed in the report):
access exports 7 days (`PRIVACY_EXPORT_TTL`, plus the bucket rule below), drop-off addresses 30 days after delivery,
processed Stripe events 30 days, commerce receipts and calendar notifications 7 days, idempotency keys and live courier
positions 24 h (Valkey), push installations 90 days (worker, `PUSH_STALE_AFTER`), notifications given up on 30 days
(worker, S-115), event dedupe claims 60 days (`EVENTS_PROCESSED_RETENTION`), partner webhook log 30 days
(`WEBHOOKS_LOG_RETENTION`), erased accounts' sign-in credentials within 5 minutes (northline-auth).

## 2. A run

`RetentionScheduler` runs every category nightly at `RETENTION_CRON` (02:47 in the platform zone). For each category:

1. **Holds.** Every module's `RetentionContributor.holds()` reports what it knows over its own schema: orders under
   way, upcoming bookings, deliveries under way, held escrow, refunds under review, disputes (open, or decided with
   their decision date and customer). `relate()` names a hold the way other modules refer to it (payments holds an
   order line; orders adds its order). The privacy module keeps the holds in force for the category: its hold codes;
   a decided dispute for `afterDisputeClosed` (and the province's minimum when `lawMinimum`).
2. **Count.** Rows past the period with and without holds: the run's `held` figure. A dry run stops here.
3. **Purge** in batches of `RETENTION_BATCH`, one transaction each under the category's advisory lock (another replica
   on the same category stops), at most `RETENTION_MAX_BATCHES` per night; the rest is `remaining` and waits for the
   next night. Objects go through the module's storage port (`AttachmentStorage`, `DisputeEvidenceStorage`,
   `ProofStorage`) in the same batch: a failed delete fails the batch, which is retried next night; deleting a file
   that is already gone is fine. Every job is idempotent — a row already dealt with no longer matches.
4. **Record.** A `privacy.retention_runs` row (ids, codes, counts; the error's class only), an audit log entry
   `privacy.retention_run` (target `retention_category`, actor `system` or the staff member), metrics.

A category that fails is logged and recorded; the others still run. A category that already ran tonight (another
replica) is skipped.

`account.closed_profile` uses the S-105 erasure pipeline — no second anonymiser: an erasure whose account closed more
than 30 days ago with a step still pending or failed has those steps made due and runs them now. Its held steps (an
open order, a dispute) are the run's "held"; what they keep is the other categories' business.

## 3. Console and API

Console › Privacy › **Retention** (screen `privacy`: privacy officer, support lead, admin; acr=mfa):

- each category with what it keeps, the action, the last run (dry runs marked), rows changed by the last real run,
  rows held, the next run, and its state (up to date, overdue after 2 days without success, failed, not run yet, not
  enforceable); the detail drawer quotes the policy and gives the basis, the clock and the holds;
- **Dry run everything** / a category's **Dry run** count what is due and change nothing; **Run everything now** /
  **Run now** (after a confirmation) purge — both need the `privacy` action;
- **Export CSV** (`retention-report.csv`, one category per line, in the viewer's language);
- what each privacy law adds after a decision, and the other automatic deletions.

| endpoint | access |
|---|---|
| `GET /api/v1/console/retention` | `@RequiresConsole(PRIVACY)` |
| `GET /api/v1/console/retention/export` (text/csv) | `@RequiresConsole(PRIVACY)` |
| `POST /api/v1/console/retention/runs {dryRun, category?}` | `@RequiresConsole(PRIVACY, actions = PRIVACY)`; 422 `category` for a code without a job |

## 4. Configuration

| variable | default | notes |
|---|---|---|
| `RETENTION_ENABLED` | `true` | nightly runs; `false` = only runs from the console |
| `RETENTION_CRON` | `0 47 2 * * *` | Spring cron, in `REGION_PLATFORM_ZONE` |
| `RETENTION_BATCH` | `500` | rows per transaction (≤ 10 000) |
| `RETENTION_MAX_BATCHES` | `40` | batches per category per night |
| `RETENTION_DRY_RUN` | `false` | nightly runs only count — use it for the first week of a new environment, read the report, then unset |

No secret. The periods are not variables (§ 1).

## 5. Metrics and alerts

- `northline_retention_rows_total{category, action}` — rows deleted / pseudonymised by real runs;
- `northline_retention_runs_total{category, mode=run|dry_run, outcome}`;
- `northline_retention_last_success_seconds{category}` — the last successful real run, read from
  `privacy.retention_runs` (the same on every replica; before a first success, the first run of any category).

Alerts (S-113 style, `deploy/observability/prometheus/rules/northline-alerts.yml`, ticket):
`NorthlineRetentionNotRun` (a category hasn't succeeded for 2 days, for 1 h) and `NorthlineRetentionMissing` (no
replica reports the gauge for 6 h) — [alerts/retention-not-run.md](alerts/retention-not-run.md).

## 6. Object storage, search, backups

- **Bucket lifecycle where it suffices**: access exports are deleted at a fixed age with no hold, so the buckets (and
  their replicas) delete `privacy/exports/` 8 days after writing — a backstop behind the api's hourly sweep
  (Terraform `buckets.<name>.expire_prefixes`, all three clouds). Everything else depends on holds (an open dispute
  keeps a photo), so the jobs delete it through the storage ports.
- **Older versions** of every object expire after 30 days (S-114), and now on the prod **replicas** too (was 90 days):
  "Backups roll off within 35 days of deletion."
- **Search** holds no personal data (S-105): business pages, listings and dishes. Nothing to purge there; erasure
  already refreshes a business's documents.
- **Database backups**: PITR / automated backups 35 days in prod (S-114, `backup_retention_days`); a row a job deletes
  is gone from backups 35 days later.
- **Kafka** topics carry ids only (retention ≤ 7 days, DLQ 30); **logs** are redacted (S-111) and kept 30 / 90 days.

## 7. Mismatches between the policy and the code

Fixed here (the policy was clearly right):

- The prod bucket replicas kept older versions (deleted objects) **90 days**; the policy says backups roll off within
  35. Now 30 on AWS, Google Cloud and Azure.
- Sign-ins (`identity.sessions`), conversations, help cases, dispute evidence, delivery proofs, check-in locations and
  seven-year-old transaction records were **never deleted**; the audit log had its 7-year purge allowed by V181's
  trigger but no job. All have jobs now.

Flagged for counsel / the product (not changed):

1. **CASL proof of consent (3 years after withdrawal, S-108)** is not in section 6 (the policy's Marketing paragraph
   names CASL but no period). S-108's own daily purge (`CASL_PURGE_CRON`) is retired in favour of this job.
2. **Audit log (7 years)** is not named in the policy. It holds ids and codes of privileged actions (some of them
   sign-ins and security changes — "Login and security logs: 12 months"?). Kept at 7 years as the records it concerns.
3. **Québec**: the civil prescription for most claims is 3 years (C.c.Q. art. 2925); "2 years after the transaction"
   for messages and evidence may be short for Québec customers.
4. **KYC (5 years after the relationship ends)**: no business can be closed or refused yet, so no relationship ends.
   The documents are at Stripe Identity; Northline keeps owner names and check outcomes (`merchants.kyc_records` is
   `blocked` until business closure exists).
5. **Google Cloud and Azure replicas don't receive deletions** (S-114: Storage Transfer Service / object replication),
   so a file the jobs delete stays in the replica bucket. Fix proposed: a periodic sync with deletion (STS
   `deleteObjectsUniqueInSink` batch job, `azcopy sync --delete-destination`), or replicate with deletes. AWS
   replicates delete markers and is compliant.
6. **Account closure beyond 30 days**: an erasure step held by an open order or dispute keeps that module's data after
   30 days (the hold is the legal reason). The profile itself is blanked at once.
7. Not in the policy and **kept with no end**: notification inbox (`messaging.notifications`), abandoned carts,
   saved addresses of open accounts, trust flags, AI usage counters, storefront visit counts, job photos merchants
   upload (`booking.media`). Each needs a period from counsel before a job can delete it.
8. **Login and security logs in northline-auth** (`auth.authorization_sessions`, `auth.issued_refresh_tokens`, Spring
   Authorization Server's `oauth2_authorization`) have no purge of their own beyond sign-outs and erasures; the auth
   server is its own deployable and schema — a job there is a follow-up.
9. **Payment intents without an escrow** (delivery fees) have no date column, so the 7-year unlinking can't reach them.

## 8. Operations

- **New environment**: deploy with `RETENTION_DRY_RUN=true`; after a night, read the report (rows due per category,
  held) and the CSV; unset it.
- **A category fails**: the console shows the run failed; the api log has the exception; fix and **Run now**.
- **A backlog** (`left` > 0 night after night): raise `RETENTION_BATCH` / `RETENTION_MAX_BATCHES`, or run it from the
  console during the day.
- **A new kind of personal data**: add a category to the schedule (with the clause it falls under), implement it in the
  owning module's `<Module>Retention` (`persistence`, package-private, its own schema only), extend the integration
  test. `RetentionScheduleTest` and `PrivacyContributorsTests` keep the two in step.
- **A legal hold that must outlive the policy** (litigation hold, a regulator's order): not modelled yet — stop the
  category (`RETENTION_ENABLED=false` stops all) and record it in the incident notes.
