-- Two-person review of merchant applications (four-eyes, 2026-10-18): stage 1 is a reviewer's recommendation, stage 2
-- the decision (merchants.application_decisions, V210) by another staff member. Additive only.
CREATE TABLE merchants.application_reviews (
  id              text        PRIMARY KEY,
  merchant_id     text        NOT NULL REFERENCES merchants.merchants(id),
  recommendation  text        NOT NULL CHECK (recommendation IN ('approve', 'reject', 'request_info')),
  -- checklist keys the reviewer would send back (request_info), e.g. {insurance,licence:AMVIC}
  check_keys      text[]      NOT NULL DEFAULT '{}',
  -- the reviewer's words for the approver (internal; never emailed); never personal data about the agent
  note            text        CHECK (char_length(note) <= 500),
  reviewed_by     text        NOT NULL,
  -- the console role(s) the reviewer acted with (CurrentStaff.roleCodes)
  role            text        NOT NULL,
  -- the submission reviewed (merchants.submitted_at then)
  submitted_at    timestamptz,
  reviewed_at     timestamptz NOT NULL,
  -- the decision that closed this review's round; null = the round is open (the application waits for a decision)
  decision_id     text        REFERENCES merchants.application_decisions(id)
);
-- The open round of an application (the latest open review is the current one).
CREATE INDEX ix_application_reviews_open ON merchants.application_reviews(merchant_id, reviewed_at DESC)
  WHERE decision_id IS NULL;
CREATE INDEX ix_application_reviews_merchant ON merchants.application_reviews(merchant_id, reviewed_at DESC);

-- Which review a decision followed (null: decided without one — four-eyes off, or before 2026-10-18).
ALTER TABLE merchants.application_decisions ADD COLUMN review_id text;
