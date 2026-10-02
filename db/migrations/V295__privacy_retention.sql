-- S-107 data retention jobs per the Privacy Policy's retention schedule (range V295–V299, docs/IMPLEMENTATION_PLAN.md).
-- Additive only. Runbook: docs/runbooks/retention.md. The schedule itself is configuration
-- (server/api/src/main/resources/privacy/retention-schedule.yml); this records its runs and the one figure a province's
-- law adds to it.

-- One row per category per run (nightly, or a staff member's run or dry run from the console). The report's "last run",
-- "rows affected" and "held"; ids, codes and counts only. Every run is also in developer.audit_log
-- (privacy.retention_run).
CREATE TABLE privacy.retention_runs (
  id          text        PRIMARY KEY,
  category    text        NOT NULL,                       -- the catalogue's code: messaging.conversations
  module      text        NOT NULL,
  dry_run     boolean     NOT NULL,
  trigger     text        NOT NULL CHECK (trigger IN ('schedule', 'staff')),
  actor_id    text        NOT NULL,                       -- 'system', or the staff member
  started_at  timestamptz NOT NULL,
  finished_at timestamptz NOT NULL,
  outcome     text        NOT NULL CHECK (outcome IN ('succeeded', 'failed')),
  affected    bigint      NOT NULL DEFAULT 0 CHECK (affected >= 0),   -- rows deleted / pseudonymised (dry run: due)
  held        bigint      NOT NULL DEFAULT 0 CHECK (held >= 0),       -- rows past their period kept by a legal hold
  remaining   bigint      NOT NULL DEFAULT 0 CHECK (remaining >= 0),  -- still due after the run's batches
  error       text                                         -- the failure's class only (messages can hold data)
);
CREATE INDEX ix_retention_runs_category ON privacy.retention_runs (category, started_at DESC);
CREATE INDEX ix_retention_runs_success ON privacy.retention_runs (category, finished_at DESC)
  WHERE outcome = 'succeeded' AND NOT dry_run;

-- What a province's law adds: personal information used to make a decision about a person (a dispute decided with a
-- check-in's location or a delivery photo) is kept at least this many days after the decision, so the person can still
-- ask for it. Drafted from the statutes, for legal review (DECISIONS 2026-09-30 S-107):
--   BC PIPA s. 35(1): at least one year after using it.
--   PIPEDA Sch. 1 cl. 4.5.2, Alberta PIPA s. 35, Québec private sector act s. 23: "long enough", no number — 0 (the
--   dispute's own period applies).
ALTER TABLE region.privacy_laws
  ADD COLUMN decision_retention_days integer NOT NULL DEFAULT 0 CHECK (decision_retention_days BETWEEN 0 AND 3650);
UPDATE region.privacy_laws SET decision_retention_days = 365 WHERE code = 'bc_pipa';

-- The jobs' scans: each finds the oldest rows of its category.
CREATE INDEX IF NOT EXISTS ix_sessions_retention
  ON identity.sessions ((coalesce(revoked_at, last_seen_at, created_at)));
CREATE INDEX IF NOT EXISTS ix_threads_retention
  ON messaging.threads ((coalesce(last_message_at, created_at)));
CREATE INDEX IF NOT EXISTS ix_tickets_resolved ON messaging.tickets (resolved_at) WHERE state = 'resolved';
CREATE INDEX IF NOT EXISTS ix_customer_uploads_created ON messaging.customer_uploads (created_at);
CREATE INDEX IF NOT EXISTS ix_booking_events_located ON booking.booking_events (at) WHERE geom IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_disputes_decided ON payments.disputes (decided_at) WHERE state = 'decided';
CREATE INDEX IF NOT EXISTS ix_escrows_created ON payments.escrows (created_at);
CREATE INDEX IF NOT EXISTS ix_stops_proofs ON fulfilment.stops (done_at) WHERE proof_media_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_orders_placed ON orders.orders (placed_at);
CREATE INDEX IF NOT EXISTS ix_audit_log_at ON developer.audit_log (at);
