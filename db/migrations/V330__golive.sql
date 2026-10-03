-- S-118 go-live (range V330–V334, above main's V326; docs/IMPLEMENTATION_PLAN.md). Additive only. Module `golive` owns the
-- schema: the per-market go-live checklist's manual records, the two-person launch requests, the market's launch and
-- rollback history, and the 14-day hypercare rota. Runbook: docs/runbooks/go-live.md. The stage itself stays in
-- region.regions (S-134); every change here is also in developer.audit_log.
CREATE SCHEMA IF NOT EXISTS golive;

-- A gate staff (or `make go-live-check RECORD=1`) recorded by hand: the latest row per (market, gate) counts while it is
-- younger than GO_LIVE_RECORD_MAX_AGE. Rows are never updated: a new record supersedes the previous one (history kept).
CREATE TABLE golive.gate_records (
  id            text        PRIMARY KEY,
  market_id     text        NOT NULL,                      -- region.regions id (logical reference)
  gate          text        NOT NULL CHECK (gate ~ '^[a-z][a-z0-9_]{1,40}$'),
  status        text        NOT NULL CHECK (status IN ('pass', 'fail', 'not_applicable')),
  evidence      text        NOT NULL CHECK (char_length(btrim(evidence)) BETWEEN 1 AND 1000),
  evidence_url  text        CHECK (char_length(evidence_url) <= 500),
  source        text        NOT NULL DEFAULT 'console' CHECK (source IN ('console', 'script')),
  recorded_by   text        NOT NULL,                      -- identity.users id (staff)
  recorded_at   timestamptz NOT NULL
);
CREATE INDEX ix_gate_records_latest ON golive.gate_records (market_id, gate, recorded_at DESC);

-- Two-person launch: one admin requests, another approves (or anyone of them withdraws / rejects). One open request per
-- market. `override` = launched with required gates failing — an emergency, with its reason; both audited.
CREATE TABLE golive.launch_requests (
  id               text        PRIMARY KEY,
  market_id        text        NOT NULL,
  state            text        NOT NULL DEFAULT 'pending'
                               CHECK (state IN ('pending', 'approved', 'rejected', 'withdrawn', 'expired')),
  requested_by     text        NOT NULL,
  requested_at     timestamptz NOT NULL,
  expires_at       timestamptz NOT NULL,
  note             text        CHECK (char_length(note) <= 500),
  override         boolean     NOT NULL DEFAULT false,
  override_reason  text        CHECK (char_length(btrim(override_reason)) BETWEEN 20 AND 500),
  blocking         text[]      NOT NULL DEFAULT '{}',      -- required gates not passing when it was requested
  decided_by       text,
  decided_at       timestamptz,
  decision_note    text        CHECK (char_length(decision_note) <= 500),
  CONSTRAINT chk_launch_override CHECK (override = (override_reason IS NOT NULL)),
  CONSTRAINT chk_launch_decided CHECK ((state = 'pending') = (decided_at IS NULL)),
  CONSTRAINT chk_launch_two_people CHECK (state <> 'approved' OR decided_by <> requested_by),
  CHECK (expires_at > requested_at)
);
CREATE UNIQUE INDEX ux_launch_requests_open ON golive.launch_requests (market_id) WHERE state = 'pending';
CREATE INDEX ix_launch_requests_market ON golive.launch_requests (market_id, requested_at DESC);

-- What happened to the market: launched (pilot → live, by the approver of a request) or rolled back (live → pilot).
CREATE TABLE golive.market_events (
  id          text        PRIMARY KEY,
  market_id   text        NOT NULL,
  kind        text        NOT NULL CHECK (kind IN ('launched', 'rolled_back')),
  request_id  text        REFERENCES golive.launch_requests (id),
  actor_id    text        NOT NULL,
  reason      text        CHECK (char_length(reason) <= 500),
  occurred_at timestamptz NOT NULL,
  CHECK ((kind = 'launched') = (request_id IS NOT NULL))
);
CREATE INDEX ix_market_events_market ON golive.market_events (market_id, occurred_at DESC);

-- Hypercare: 14 days after launch, per day a primary and a secondary engineer (paged: each also has a shift in
-- identity.oncall_shifts, so S-113's on-call export carries them) and a business contact (not paged).
CREATE TABLE golive.hypercare_days (
  market_id            text NOT NULL,
  day                  date NOT NULL,                       -- the market's local date
  primary_user_id      text NOT NULL,
  secondary_user_id    text NOT NULL,
  business_user_id     text NOT NULL,
  primary_shift_id     text,                                -- identity.oncall_shifts ids (logical references)
  secondary_shift_id   text,
  created_by           text NOT NULL,
  created_at           timestamptz NOT NULL,
  PRIMARY KEY (market_id, day),
  CHECK (primary_user_id <> secondary_user_id)
);
