-- S-22 · Identity verification (Stripe Identity) for owners ≥ 25 % (onboarding / merchants range V030–V039).
-- Additive only. One row per principal that needs KYC (merchant_principals.kyc_verification_id is set): the current
-- Stripe Identity VerificationSession and what Northline keeps of its result. Northline never stores document images,
-- ID numbers, the date of birth or the names Stripe read — only the session id, the status and two match results.
-- The merchants.verifications 'kyc' row is derived from these rows (all verified → verified; all finished → submitted).

CREATE TABLE merchants.owner_identity_checks (
  id               text        PRIMARY KEY,
  merchant_id      text        NOT NULL REFERENCES merchants.merchants(id),
  -- merchants.merchant_principals.id (logical ref: principals are re-saved with the Business step; ids are kept for
  -- principals whose legal name did not change)
  principal_id     text        NOT NULL,
  -- current Stripe Identity VerificationSession (vs_…); a new session replaces it, the old one is canceled
  stripe_session   text        UNIQUE,
  -- pending: session open, nothing submitted · processing: Stripe is checking · verified: Stripe verified and the
  -- names match · retry: Stripe couldn't verify (last_error) — the owner starts again · review: Stripe verified but the
  -- name or date of birth doesn't match what Northline knows — a Northline agent decides · canceled
  status           text        NOT NULL CHECK (status IN ('pending', 'processing', 'verified', 'retry', 'review', 'canceled')),
  -- Stripe's last_error.code (document_expired, selfie_face_mismatch, consent_declined, …); never the reason text
  last_error       text,
  -- verified name vs the principal's legal name; verified date of birth vs the Stripe Connect person's
  name_match       text        CHECK (name_match IN ('match', 'mismatch', 'unavailable')),
  dob_match        text        CHECK (dob_match IN ('match', 'mismatch', 'unavailable')),
  -- self: the signed-in owner opened the hosted flow · email: a link was emailed to the owner
  delivery         text        NOT NULL CHECK (delivery IN ('self', 'email')),
  -- where the link went (delivery = email); needed to resend
  email            citext,
  attempts         integer     NOT NULL DEFAULT 1 CHECK (attempts > 0),
  requested_by     text        NOT NULL,
  -- Stripe `created` of the last webhook event applied (older events are ignored)
  stripe_updated_at timestamptz,
  verified_at      timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (merchant_id, principal_id)
);
CREATE INDEX ix_owner_identity_checks_merchant ON merchants.owner_identity_checks(merchant_id);

CREATE INDEX ix_merchant_principals_merchant ON merchants.merchant_principals(merchant_id);
-- at most one principal per business is "you" for a signed-in user (they verify themselves in the hosted flow)
CREATE UNIQUE INDEX ux_merchant_principals_user ON merchants.merchant_principals(merchant_id, user_id)
  WHERE user_id IS NOT NULL;
