-- schema: trust · loyalty · owner: Java (java)
-- Generated from the Northline spec (docs/DATA_MODEL.md). Hand-edit freely; keep column names.
CREATE SCHEMA IF NOT EXISTS trust · loyalty;

-- Two-way reviews, only on paid jobs/orders.
CREATE TABLE trust · loyalty.reviews (
  -- PK
  id text PRIMARY KEY,
  ref_type text,
  ref_id text,
  author_id text,
  -- merchant | customer | courier
  target_type text CHECK (target_type IN ('merchant', 'customer', 'courier')),
  target_id text,
  rating smallint,
  tags text[],
  text text,
  reply text,
  lang text
);
-- TODO indexes/constraints: unique(ref_id,author_id) · index(target_type,target_id)
-- outbox events: review.created
-- search projection: merchants.rating · listings.merchant_rating

-- Nightly score components per merchant (from ClickHouse).
CREATE TABLE trust · loyalty.quality_scores (
  -- PK part
  merchant_id text,
  -- PK part
  date date,
  score integer,
  -- on_time, photos, response, rebook, disputes
  components jsonb,
  PRIMARY KEY (merchant_id,date)
);
-- TODO indexes/constraints: PK(merchant_id,date)
-- outbox events: trust.score_changed
-- search projection: listings.trust_boost

-- Promotions/demotions with reasons.
CREATE TABLE trust · loyalty.tier_history (
  -- PK
  id text PRIMARY KEY,
  merchant_id text,
  tier text,
  from_at timestamptz,
  to_at timestamptz,
  reason text
);
-- outbox events: merchant.tier_changed

-- Trust & safety flags with actions taken.
CREATE TABLE trust · loyalty.flags (
  -- PK
  id text PRIMARY KEY,
  target_type text,
  target_id text,
  -- off_platform_payment | floor_breach | no_show…
  rule text,
  evidence jsonb,
  state text,
  action text,
  actor_id text
);
-- TODO indexes/constraints: index(state)
-- outbox events: trust.flagged

-- Points earned, redeemed, expired; provider-funded multipliers.
CREATE TABLE trust · loyalty.points_ledger (
  -- PK
  id text PRIMARY KEY,
  user_id text,
  delta integer,
  ref_type text,
  ref_id text,
  funded_by_merchant_id text,
  expires_at timestamptz
);
-- TODO indexes/constraints: index(user_id)
-- outbox events: points.credited

-- Merchant-funded multipliers and budgets.
CREATE TABLE trust · loyalty.merchant_rewards (
  -- PK
  id text PRIMARY KEY,
  merchant_id text,
  -- listing ids or all
  scope jsonb,
  multiplier numeric,
  starts_at timestamptz,
  ends_at timestamptz,
  budget_cents bigint,
  spent_cents bigint
);
-- search projection: listings.reward_multiplier

-- Foreign keys (in-module only)
-- logical ref (cross-module, no FK): trust · loyalty.reviews.ref_id → booking.bookings.id
-- logical ref (cross-module, no FK): trust · loyalty.reviews.ref_id → orders.orders.id
-- logical ref (cross-module, no FK): trust · loyalty.quality_scores.merchant_id → merchants.merchants.id
-- logical ref (cross-module, no FK): trust · loyalty.tier_history.merchant_id → merchants.merchants.id
-- logical ref (cross-module, no FK): trust · loyalty.points_ledger.user_id → identity.users.id
-- logical ref (cross-module, no FK): trust · loyalty.merchant_rewards.merchant_id → merchants.merchants.id
