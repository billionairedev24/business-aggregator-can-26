-- S-105 privacy rights: access, correction and erasure (range V270–V279, docs/IMPLEMENTATION_PLAN.md). Additive only.
-- Runbook: docs/runbooks/privacy-requests.md.
CREATE SCHEMA IF NOT EXISTS privacy;

CREATE SEQUENCE privacy.request_number_seq START 1001;

-- One row per data subject request (PIPEDA, Alberta/BC PIPA, Québec Law 25: access, correction, erasure). The row is
-- the accountability record and outlives the erasure: it holds ids, codes and dates only. What the person told us
-- (their contact at the time, the corrections they asked for) is sealed (envelope encryption, SecretSealer) and wiped
-- when the request closes.
CREATE TABLE privacy.requests (
  id               text        PRIMARY KEY,
  number           bigint      NOT NULL UNIQUE DEFAULT nextval('privacy.request_number_seq'), -- "PR-1001"
  subject_id       text        NOT NULL,                     -- logical ref → identity.users
  subject_kind     text        NOT NULL CHECK (subject_kind IN ('customer', 'team')),
  merchant_ids     text[]      NOT NULL DEFAULT '{}',        -- businesses the person was on the team of when asking
  type             text        NOT NULL CHECK (type IN ('access', 'correction', 'erasure')),
  state            text        NOT NULL CHECK (state IN ('awaiting_verification', 'verified', 'in_progress',
                                                         'completed', 'rejected', 'withdrawn')),
  channel          text        NOT NULL CHECK (channel IN ('self', 'staff')),
  province         char(2)     NOT NULL,                     -- whose law applies (region model)
  law              text        NOT NULL CHECK (law IN ('pipeda', 'ab_pipa', 'bc_pipa', 'qc_law25')),
  received_at      timestamptz NOT NULL,
  due_at           timestamptz NOT NULL,                     -- the law's response deadline (region.privacy_laws)
  extended_to      timestamptz,                              -- once, within the law's extension
  extension_reason text        CHECK (extension_reason IN ('volume', 'consultation', 'conversion')),
  verification     text        CHECK (verification IN ('step_up', 'code', 'staff')),
  verified_at      timestamptz,
  verified_by      text,                                     -- staff id when verified by staff
  code_hash        text,                                     -- sha-256 of the 6-digit code texted to the person
  code_expires_at  timestamptz,
  code_attempts    integer     NOT NULL DEFAULT 0 CHECK (code_attempts >= 0),
  scheduled_for    timestamptz,                              -- erasure: when the pipeline starts (grace period)
  started_at       timestamptz,
  completed_at     timestamptz,
  decision         text        CHECK (decision IN ('identity_not_verified', 'not_our_data', 'legal_exception',
                                                   'duplicate', 'frivolous')),
  decision_note    text        CHECK (char_length(decision_note) <= 500),
  decided_by       text,
  created_by       text,                                     -- staff who recorded a request made by email or mail
  sealed_key_ref   text,
  sealed_key       bytea,
  sealed_data      bytea,                                    -- {email, phone, corrections, note} (sealed)
  export_key       text,                                     -- privacy/exports/<id>.bin (object storage)
  export_bytes     bigint,
  export_expires_at timestamptz,
  link_hash        text,                                     -- sha-256 of the current download link token
  link_expires_at  timestamptz,
  holds_open       integer     NOT NULL DEFAULT 0 CHECK (holds_open >= 0),
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  version          integer     NOT NULL DEFAULT 0,
  CHECK ((sealed_key_ref IS NULL) = (sealed_data IS NULL)),
  CHECK (state <> 'completed' OR completed_at IS NOT NULL),
  CHECK (state <> 'rejected' OR decision IS NOT NULL),
  CHECK (extended_to IS NULL OR (extended_to > due_at AND extension_reason IS NOT NULL))
);
-- One open request of a kind per person (a second "Delete my account" answers 409).
CREATE UNIQUE INDEX ux_privacy_requests_open ON privacy.requests (subject_id, type)
  WHERE state IN ('awaiting_verification', 'verified', 'in_progress');
CREATE INDEX ix_privacy_requests_queue ON privacy.requests (state, due_at);
CREATE INDEX ix_privacy_requests_subject ON privacy.requests (subject_id, received_at DESC);
CREATE INDEX ix_privacy_requests_due_work ON privacy.requests (scheduled_for)
  WHERE state IN ('verified', 'in_progress');
CREATE INDEX ix_privacy_requests_exports ON privacy.requests (export_expires_at) WHERE export_key IS NOT NULL;
CREATE UNIQUE INDEX ux_privacy_requests_link ON privacy.requests (link_hash) WHERE link_hash IS NOT NULL;

-- The erasure pipeline's progress, one row per module that holds personal data (its PersonalDataContributor). A row
-- is written before the module runs and updated after, so a crash resumes where it stopped; every module's erasure is
-- idempotent. `held` = something stops erasure for now (an open order, an open dispute): retried until it clears.
CREATE TABLE privacy.erasure_steps (
  request_id      text        NOT NULL REFERENCES privacy.requests (id),
  module          text        NOT NULL CHECK (module ~ '^[a-z]{2,32}$'),
  sort            integer     NOT NULL,
  status          text        NOT NULL CHECK (status IN ('pending', 'done', 'held', 'failed')),
  attempts        integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  last_error      text        CHECK (char_length(last_error) <= 200),   -- exception class only, never a message
  holds           jsonb       NOT NULL DEFAULT '[]',        -- [{category, reason}]
  retained        jsonb       NOT NULL DEFAULT '[]',        -- [{category, reason}] what is kept and why
  next_attempt_at timestamptz,
  done_at         timestamptz,
  updated_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (request_id, module)
);
CREATE INDEX ix_privacy_erasure_steps_due ON privacy.erasure_steps (next_attempt_at)
  WHERE status IN ('pending', 'held', 'failed');
