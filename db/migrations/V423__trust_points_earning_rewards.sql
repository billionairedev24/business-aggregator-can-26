-- S-152: the points ledger's writers (earning, reversals, referrals, expiry) and the provider-funded reward's budget.
-- Additive only.

-- Every ledger row a listener or job writes is written once per reference, so a retried listener never moves points
-- twice: earn / earn_reversal (a completed sale and its refunds), reward / reward_reversal (a business's extra points),
-- referral (both sides of Invite a neighbour), expiry (one row per expired credit, ref_id = the credit's id).
CREATE UNIQUE INDEX ux_points_ledger_ref_s152 ON trust.points_ledger (user_id, ref_type, ref_id)
  WHERE ref_type IN ('earn', 'earn_reversal', 'reward', 'reward_reversal', 'referral', 'expiry');
-- The expiry job reads credits by when they expire.
CREATE INDEX ix_points_ledger_expiring ON trust.points_ledger (expires_at) WHERE delta > 0 AND expires_at IS NOT NULL;

-- The provider-funded reward (S-75) gets its money: a budget the business sets (what the extra points may cost it),
-- what it has spent, when the budget ran out, and an optional list of listings it applies to (empty = everything).
ALTER TABLE trust.merchant_rewards
  ALTER COLUMN spent_cents SET DEFAULT 0,
  ADD COLUMN listing_ids text[] NOT NULL DEFAULT '{}',
  ADD COLUMN exhausted_at timestamptz,
  ADD COLUMN started_at timestamptz,   -- when it was last switched on: only sales from then on earn extra points
  ADD CONSTRAINT chk_merchant_rewards_budget CHECK (budget_cents IS NULL OR budget_cents BETWEEN 0 AND 100000000),
  ADD CONSTRAINT chk_merchant_rewards_spent CHECK (
    spent_cents IS NULL OR (spent_cents >= 0 AND (budget_cents IS NULL OR spent_cents <= budget_cents)));
UPDATE trust.merchant_rewards SET spent_cents = 0 WHERE spent_cents IS NULL;
UPDATE trust.merchant_rewards SET started_at = updated_at WHERE active AND started_at IS NULL;

-- One row per sale a reward paid extra points on: what it cost the business (drawn from the budget and charged to its
-- balance by payments) and what refunds took back.
CREATE TABLE trust.reward_credits (
  id              text PRIMARY KEY,
  merchant_id     text NOT NULL,
  user_id         text NOT NULL,
  escrow_id       text NOT NULL UNIQUE,
  multiplier      integer NOT NULL CHECK (multiplier IN (2, 3)),
  points          integer NOT NULL CHECK (points > 0),
  cents           bigint  NOT NULL CHECK (cents > 0),
  reversed_points integer NOT NULL DEFAULT 0 CHECK (reversed_points >= 0),
  reversed_cents  bigint  NOT NULL DEFAULT 0 CHECK (reversed_cents >= 0),
  created_at      timestamptz NOT NULL,
  CONSTRAINT chk_reward_credits_reversed CHECK (reversed_points <= points AND reversed_cents <= cents)
);
CREATE INDEX ix_reward_credits_merchant ON trust.reward_credits (merchant_id, created_at);
CREATE INDEX ix_reward_credits_user ON trust.reward_credits (user_id);
