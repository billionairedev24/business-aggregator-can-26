-- S-79 platform console verification queue (range V190–V199, docs/CONSOLE_PLAN.md). Additive only.

-- Every decision an agent takes on a submitted application: approve (pending → active) or request information
-- (pending → applicant, the listed checks reopened). Kept for the queue's "Decision" column, the median time to a
-- decision and the business's history; the audit log has the same facts as ids and codes.
CREATE TABLE merchants.application_decisions (
  id            text        PRIMARY KEY,
  merchant_id   text        NOT NULL REFERENCES merchants.merchants(id),
  decision      text        NOT NULL CHECK (decision IN ('approved', 'info_requested')),
  -- checklist keys the business must redo (info_requested), e.g. {insurance,licence:AMVIC}
  check_keys    text[]      NOT NULL DEFAULT '{}',
  -- the agent's words to the business (emailed to its owners); never personal data about the agent
  note          text        CHECK (char_length(note) <= 500),
  decided_by    text        NOT NULL,
  -- the console role(s) the agent acted with (CurrentStaff.roleCodes)
  role          text        NOT NULL,
  -- when the application decided on was submitted (time to a decision)
  submitted_at  timestamptz,
  decided_at    timestamptz NOT NULL
);
CREATE INDEX ix_application_decisions_merchant ON merchants.application_decisions(merchant_id, decided_at DESC);
CREATE INDEX ix_application_decisions_at ON merchants.application_decisions(decided_at DESC);

-- The queue lists submitted applications (status pending) oldest first.
CREATE INDEX IF NOT EXISTS ix_merchants_pending ON merchants.merchants(submitted_at) WHERE status = 'pending';

-- S-22 identity mismatches (status review): the agent's decision.
ALTER TABLE merchants.owner_identity_checks
  ADD COLUMN reviewed_by text,
  ADD COLUMN reviewed_at timestamptz,
  ADD COLUMN review_note text CHECK (char_length(review_note) <= 500);
CREATE INDEX ix_owner_identity_checks_review ON merchants.owner_identity_checks(merchant_id) WHERE status = 'review';
