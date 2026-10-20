-- S-152: schema `loyalty` (module ca.northline.loyalty): what each completed sale earned, Invite a neighbour, and the
-- Northline Plus membership behind the MembershipBilling port. Points themselves stay in trust.points_ledger; money
-- stays in payments. Additive only.
CREATE SCHEMA IF NOT EXISTS loyalty;

-- One row per released escrow that earned points (the listener's idempotency key is the escrow id). Refunds take back
-- their share: reversed_* grow, never past the earned amounts.
CREATE TABLE loyalty.earnings (
  escrow_id            text PRIMARY KEY,
  customer_id          text NOT NULL,
  merchant_id          text NOT NULL,
  kind                 text NOT NULL CHECK (kind IN ('goods', 'food', 'service')),
  ref_type             text NOT NULL,
  ref_id               text NOT NULL,
  amount_cents         bigint  NOT NULL CHECK (amount_cents >= 0),
  rule_id              text,
  points_per_dollar    integer NOT NULL CHECK (points_per_dollar >= 0),
  plus_multiplier_pct  integer NOT NULL DEFAULT 100 CHECK (plus_multiplier_pct BETWEEN 100 AND 500),
  points               integer NOT NULL CHECK (points >= 0),
  reward_points        integer NOT NULL DEFAULT 0 CHECK (reward_points >= 0),
  reversed_cents       bigint  NOT NULL DEFAULT 0 CHECK (reversed_cents >= 0),
  reversed_points      integer NOT NULL DEFAULT 0 CHECK (reversed_points >= 0),
  -- refunds the earning already left out (approved before the release): their refund.issued takes nothing back
  excluded_refund_ids  text[]  NOT NULL DEFAULT '{}',
  expires_at           timestamptz,
  created_at           timestamptz NOT NULL,
  CONSTRAINT chk_earnings_reversed CHECK (reversed_points <= points AND reversed_cents <= amount_cents)
);
CREATE INDEX ix_earnings_customer ON loyalty.earnings (customer_id, created_at);

-- Invite a neighbour: one code per person (made on first view), one claim per invitee.
CREATE TABLE loyalty.referral_codes (
  user_id    text PRIMARY KEY,
  code       text NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9]{6,12}$'),
  created_at timestamptz NOT NULL
);

-- state: pending (claimed, waiting for the invitee's first completed order) · rewarded (both sides credited) ·
-- refused (an abuse limit stopped it; the reason says which).
CREATE TABLE loyalty.referrals (
  invitee_id     text PRIMARY KEY,
  referrer_id    text NOT NULL,
  code           text NOT NULL,
  state          text NOT NULL CHECK (state IN ('pending', 'rewarded', 'refused')),
  claimed_at     timestamptz NOT NULL,
  decided_at     timestamptz,
  escrow_id      text,
  points         integer NOT NULL DEFAULT 0 CHECK (points >= 0),
  refused_reason text CHECK (refused_reason IS NULL OR refused_reason IN
                   ('referrer_cap', 'order_too_small', 'window_passed', 'same_household', 'no_points_here')),
  CONSTRAINT chk_referrals_not_self CHECK (invitee_id <> referrer_id)
);
CREATE INDEX ix_referrals_referrer ON loyalty.referrals (referrer_id, decided_at);

-- Wrong codes a person typed (the claim endpoint's abuse limit: a few per hour).
CREATE TABLE loyalty.referral_attempts (
  user_id text NOT NULL,
  at      timestamptz NOT NULL
);
CREATE INDEX ix_referral_attempts_user ON loyalty.referral_attempts (user_id, at);

-- Northline Plus: one membership per household (identity.households; up to 4 people share it), paid by one person.
-- provider: local (the fake billing, local/test) | stripe (Stripe Billing subscription sub_…). status follows Stripe's
-- subscription statuses that matter here; the benefits apply while trialing or active (and past_due during Stripe's
-- retries). The household's plus_plan / renews_at mirror this row for the account area (identity.api).
CREATE TABLE loyalty.memberships (
  household_id         text PRIMARY KEY,
  payer_id             text NOT NULL,
  plan                 text NOT NULL CHECK (plan IN ('monthly', 'annual')),
  status               text NOT NULL CHECK (status IN ('incomplete', 'trialing', 'active', 'past_due', 'canceled')),
  provider             text NOT NULL CHECK (provider IN ('local', 'stripe')),
  subscription_id      text UNIQUE,
  current_period_end   timestamptz,
  cancel_at_period_end boolean NOT NULL DEFAULT false,
  trial_used           boolean NOT NULL DEFAULT false,
  started_at           timestamptz NOT NULL,
  canceled_at          timestamptz,
  updated_at           timestamptz NOT NULL,
  -- the Stripe event time last applied (webhooks arrive in any order; an older one is ignored)
  last_event_at        timestamptz
);
CREATE INDEX ix_memberships_payer ON loyalty.memberships (payer_id);
