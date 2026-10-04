-- Age-restricted purchases (owner decision 2026-10-04). Additive only. Module `restricted` owns the schema.
CREATE SCHEMA IF NOT EXISTS restricted;

-- A customer's age check, done once through the identity provider port (Stripe Identity: document + matching selfie).
-- Kept: only "verified over N, on date, by method" — never the document, the selfie, the date of birth or the ID number.
-- N is the person's age in whole years on the day, capped at the strictest minimum age the region model asks
-- (region.age_rules), so an adult's exact age is not kept either; N + whole years since verified_on is their age floor.
-- The provider's session id is kept only while the check is open, then cleared (and the session redacted at Stripe).
CREATE TABLE restricted.age_verifications (
  user_id      text PRIMARY KEY,                     -- identity.users id
  state        text NOT NULL CHECK (state IN ('pending', 'verified', 'failed')),
  over_age     integer CHECK (over_age BETWEEN 0 AND 25),
  verified_on  date,
  method       text CHECK (method IN ('stripe_identity_document_selfie', 'fake')),
  session_id   text,
  last_error   text CHECK (last_error ~ '^[a-z_]{1,60}$'),
  attempts     integer NOT NULL DEFAULT 0,
  started_at   timestamptz NOT NULL,
  updated_at   timestamptz NOT NULL,
  CONSTRAINT chk_age_verified CHECK (state <> 'verified' OR (over_age IS NOT NULL AND verified_on IS NOT NULL AND method IS NOT NULL)),
  CONSTRAINT chk_age_session CHECK (state = 'pending' OR session_id IS NULL)
);
CREATE UNIQUE INDEX ux_age_verifications_session ON restricted.age_verifications (session_id) WHERE session_id IS NOT NULL;

-- Every ID check at a handoff of an order with age-restricted items: the courier at the door, or the business at the
-- counter (pickup). What was confirmed, never the ID itself (no image, number or date of birth).
CREATE TABLE restricted.handoff_checks (
  id                text PRIMARY KEY,
  order_id          text NOT NULL,                   -- orders.orders id (logical reference)
  order_type        text NOT NULL CHECK (order_type IN ('goods', 'food')),
  merchant_id       text,                            -- the business, for a counter check
  province          text CHECK (province ~ '^[A-Z]{2}$'),
  required_age      integer NOT NULL CHECK (required_age BETWEEN 16 AND 25),
  actor_id          text NOT NULL,                   -- identity.users id of the courier or team member
  actor_role        text NOT NULL CHECK (actor_role IN ('courier', 'merchant')),
  place             text NOT NULL CHECK (place IN ('door', 'counter')),
  outcome           text NOT NULL CHECK (outcome IN ('passed', 'refused')),
  id_checked        boolean NOT NULL,
  recipient_matches boolean NOT NULL,
  of_age            boolean NOT NULL,
  reason            text CHECK (reason IN ('no_id', 'underage', 'mismatch', 'nobody_of_age', 'intoxicated', 'other')),
  checked_at        timestamptz NOT NULL,
  CONSTRAINT chk_handoff_outcome CHECK (
    (outcome = 'passed' AND id_checked AND recipient_matches AND of_age AND reason IS NULL)
    OR (outcome = 'refused' AND reason IS NOT NULL))
);
CREATE INDEX ix_handoff_checks_order ON restricted.handoff_checks (order_id, checked_at);
CREATE INDEX ix_handoff_checks_report ON restricted.handoff_checks (checked_at, province);
