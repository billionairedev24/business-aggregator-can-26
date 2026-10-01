-- S-58 Account area: orders & bookings, favourites, wallet & points (consumer-account range V160–V169). Additive only.
-- See docs/DECISIONS.md "S-58".

-- ── account: what only the consumer's account area keeps ────────────────────────────────────────────────────────
CREATE SCHEMA IF NOT EXISTS account;

-- Favourite providers & shops (design 06 favourites). merchant_id is a logical ref → merchants.merchants.
CREATE TABLE account.favourites (
  user_id     text NOT NULL,                      -- logical ref → identity.users
  merchant_id text NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, merchant_id)
);
CREATE INDEX IF NOT EXISTS ix_favourites_user ON account.favourites (user_id, created_at DESC);

-- ── trust.points_ledger: the wallet's read model (when each row was written, by person) ─────────────────────────
ALTER TABLE trust.points_ledger
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN note text;                           -- "Grocery run NL-48190", "Provider-funded 3×"
CREATE INDEX IF NOT EXISTS ix_points_ledger_user ON trust.points_ledger (user_id, created_at);

-- ── identity.households: when Plus started (the wallet's "Active since May 3") ─────────────────────────────────
ALTER TABLE identity.households ADD COLUMN plus_since timestamptz;
CREATE INDEX IF NOT EXISTS ix_household_members_user ON identity.household_members (user_id);

-- ── the customer's own lists ─────────────────────────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS ix_bookings_customer_starts ON booking.bookings (customer_id, starts_at DESC);
CREATE INDEX IF NOT EXISTS ix_escrows_customer ON payments.escrows (customer_id);
CREATE INDEX IF NOT EXISTS ix_disputes_opened_by ON payments.disputes (opened_by);
