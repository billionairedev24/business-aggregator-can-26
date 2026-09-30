-- S-33 partner webhook delivery (settings range V080–V089, developer schema). Written by the api (endpoints, resend,
-- test events) and the worker's `webhooks` consumer group and dispatcher (deliveries, attempts, endpoint health).
-- Additive only: V015/V083 columns keep their meaning.

-- ── Endpoints ──────────────────────────────────────────────────────────────────────────────────────────────────────
-- Secret rotation with overlap: the previous secret (AES-256-GCM like secret_enc) keeps signing next to the new one
-- until secret_prev_until. Health: failing_since / consecutive_failures drive the auto-disable (disabled_reason
-- 'failing'); disable_notice_id keys the owners' email (claims in events.processed_events), disabled_notified_at
-- records that it went out. lease_owner / lease_until: the dispatcher replica currently delivering to this endpoint
-- (one in flight per endpoint; a crashed replica's lease simply expires).
ALTER TABLE developer.webhook_endpoints
  ADD COLUMN secret_prev_enc      bytea,
  ADD COLUMN secret_prev_until    timestamptz,
  ADD COLUMN failing_since        timestamptz,
  ADD COLUMN consecutive_failures integer NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
  ADD COLUMN disabled_at          timestamptz,
  ADD COLUMN disabled_reason      text CHECK (disabled_reason IN ('failing')),
  ADD COLUMN disable_notice_id    text,
  ADD COLUMN disabled_notified_at timestamptz,
  ADD COLUMN lease_owner          text,
  ADD COLUMN lease_until          timestamptz;

-- ── Deliveries: one row per (endpoint, event) — plus one per resend or test ────────────────────────────────────────
-- V015 columns: attempt = attempts made so far, status_code = HTTP status of the latest attempt (null when none came
-- back), at = time of the latest attempt. state null = a row written before S-33 (read as its status code says).
ALTER TABLE developer.webhook_deliveries
  ADD COLUMN merchant_id      text,
  ADD COLUMN event_type       text,
  ADD COLUMN payload          text,          -- the exact JSON bytes sent (and signed) on every attempt and resend
  ADD COLUMN state            text CHECK (state IN ('pending', 'succeeded', 'failed')),
  ADD COLUMN next_attempt_at  timestamptz,
  ADD COLUMN duration_ms      integer,
  ADD COLUMN error            text,
  ADD COLUMN response_snippet text,
  ADD COLUMN resend_of        text,
  ADD COLUMN test             boolean NOT NULL DEFAULT false,
  ADD COLUMN created_at       timestamptz NOT NULL DEFAULT now();

-- Fan-out is idempotent per (endpoint, event): a redelivered Kafka record adds nothing. Resends and tests are extra rows.
CREATE UNIQUE INDEX IF NOT EXISTS ux_webhook_deliveries_endpoint_event
  ON developer.webhook_deliveries (endpoint_id, event_id) WHERE resend_of IS NULL AND NOT test;
-- The dispatcher's "what is due" scan.
CREATE INDEX IF NOT EXISTS ix_webhook_deliveries_due
  ON developer.webhook_deliveries (endpoint_id, next_attempt_at) WHERE state = 'pending';
CREATE INDEX IF NOT EXISTS ix_webhook_deliveries_created ON developer.webhook_deliveries (endpoint_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_webhook_endpoints_subscribers
  ON developer.webhook_endpoints (merchant_id) WHERE active;

-- ── Attempts: the delivery log's history (every HTTP try, its status, timing and the first bytes of the answer) ────
CREATE TABLE IF NOT EXISTS developer.webhook_attempts (
  id               text PRIMARY KEY,
  delivery_id      text NOT NULL REFERENCES developer.webhook_deliveries (id) ON DELETE CASCADE,
  attempt          integer NOT NULL CHECK (attempt >= 1),
  at               timestamptz NOT NULL,
  status_code      integer,
  duration_ms      integer,
  error            text,
  response_snippet text,
  UNIQUE (delivery_id, attempt)
);
