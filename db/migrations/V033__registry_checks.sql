-- S-23 · Business registry lookups (onboarding / merchants range V030–V039). Additive only.
-- Every lookup behind the checklist's `registry` row and licence rows is kept as evidence: which source was asked,
-- for which number, what it answered (the public registry record: name, number, status, expiry), when, and the
-- provider's reference. Anything that doesn't match opens a manual review that a Northline agent decides in the
-- console. merchants.verifications.rechecked_at (baseline column) holds the last successful re-check.

CREATE TABLE merchants.registry_checks (
  id               text        PRIMARY KEY,
  merchant_id      text        NOT NULL REFERENCES merchants.merchants(id),
  -- the merchants.verifications row this evidences (logical ref)
  verification_id  text        NOT NULL,
  -- corporations_canada (ISED Federal Corporation API) · alberta_corporate_registry (search service, or manual)
  -- · calgary_business_licences (Open Calgary, Socrata) · manual (regulators without an API: AMVIC, AHS, AGLC, …)
  source           text        NOT NULL CHECK (source IN ('corporations_canada', 'alberta_corporate_registry', 'calgary_business_licences', 'manual')),
  subject          text        NOT NULL CHECK (subject IN ('corporation', 'extra_provincial', 'partnership', 'trade_name', 'cooperative', 'society', 'municipal_licence', 'licence')),
  -- the regulator of a licence subject (AMVIC, AHS, Mobile permit, …)
  registry         text,
  -- what was looked up: corporate access #, corporation #, registration #, licence #
  query_number     text        NOT NULL,
  -- the name entered in onboarding that the record must carry
  expected_name    text,
  -- initial = onboarding / owner action · recheck = the scheduled re-check
  trigger          text        NOT NULL CHECK (trigger IN ('initial', 'recheck')),
  outcome          text        NOT NULL CHECK (outcome IN ('matched', 'mismatch', 'not_found', 'manual', 'unavailable')),
  -- why it isn't a match: name | status | expired
  reasons          text[]      NOT NULL DEFAULT '{}',
  -- the public registry record as the source returned it
  record_name      text,
  record_number    text,
  record_status    text,
  record_expires_on date,
  -- provider evidence: record URL, search id, dataset row id
  reference        text,
  checked_at       timestamptz NOT NULL,
  -- manual review (console): open → approved | rejected
  review_state     text        CHECK (review_state IN ('open', 'approved', 'rejected')),
  reviewed_by      text,
  reviewed_at      timestamptz,
  review_note      text,
  created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_registry_checks_merchant ON merchants.registry_checks(merchant_id);
CREATE INDEX ix_registry_checks_verification ON merchants.registry_checks(verification_id, checked_at DESC);
CREATE INDEX ix_registry_checks_open_reviews ON merchants.registry_checks(checked_at) WHERE review_state = 'open';

-- The scheduled re-check picks verified registry / licence rows by when they were last checked.
CREATE INDEX ix_verifications_rechecked ON merchants.verifications(rechecked_at)
  WHERE check_key IS NOT NULL AND status = 'verified';
