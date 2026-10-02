# Retention not run

**Alerts:** `NorthlineRetentionNotRun`, `NorthlineRetentionMissing` · threshold — ticket · [all alerts](README.md) · [alerting](../alerting.md)

**What it measures.** `northline_retention_last_success_seconds{category}` (S-107): when each category of the
retention schedule last ran successfully (a real run, not a dry run), read from `privacy.retention_runs` by every api
replica. Ticket when a category hasn't succeeded for two days (`NotRun`, for 1 h), or no replica reports the gauge for
6 hours (`Missing`). Before a category's first success the gauge reports the first run of any category, so a category
that never succeeds alerts too.

**Impact.** Personal data past its period (sign-ins over 12 months, conversations over 2 years, check-in locations over
90 days …) is kept longer than the Privacy Policy says. Nothing is lost; it is a compliance gap that grows each night.

## First checks

1. Console › Privacy › Retention: the category's last run, its outcome and "left" (still due after the run's batches).
2. The api logs: `Retention of <category> failed; it runs again next time` with the exception (the run's
   `privacy.retention_runs.error` holds only its class).
3. `RETENTION_ENABLED` / `RETENTION_CRON` in the environment ([retention.md](../retention.md)); is the api running a
   profile other than `test`?
4. `remaining` growing night after night: the batch limit (`RETENTION_BATCH` × `RETENTION_MAX_BATCHES`) is too small
   for the backlog — raise it, or run the category from the console.

## Mitigate

Fix the cause, then **Run now** for the category in the console (or wait for the next night). Runs are idempotent:
running twice changes nothing more. A failing object storage delete fails the batch and retries it next time.

## Afterwards

Write down what happened in the incident notes (cause, impact, time to detect and fix); if the alert was noise, tune
it in `deploy/observability/` (make obs-rules-check) rather than silencing it for good. *Stub (S-107): never exercised
in a real incident — improve it the first time it is used.*
