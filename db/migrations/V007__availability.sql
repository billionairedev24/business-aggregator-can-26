-- schema: availability · owner: Java (java)
-- Generated from the Northline spec (docs/DATA_MODEL.md). Hand-edit freely; keep column names.
CREATE SCHEMA IF NOT EXISTS availability;

-- Weekly hours per bookable member; multiple ranges per day.
CREATE TABLE availability.availability_rules (
  -- PK
  id text PRIMARY KEY,
  merchant_id text,
  member_user_id text,
  weekday smallint,
  -- [[start,end],…]
  ranges jsonb,
  effective_from date
);
-- TODO indexes/constraints: index(merchant_id,member_user_id)
-- outbox events: availability.changed
-- search projection: listings.next_slot

-- Interval, buffer, notice, horizon, max/day, acceptance mode.
CREATE TABLE availability.booking_rules (
  -- PK
  merchant_id text,
  interval_min integer,
  buffer_min integer,
  min_notice_min integer,
  horizon_days integer,
  max_jobs_per_day integer,
  -- instant | approve | request
  accept_mode text CHECK (accept_mode IN ('instant', 'approve', 'request')),
  reschedule_free_min integer,
  late_cancel_fee_cents bigint
);
-- outbox events: availability.changed

-- Closures and special hours per member or whole team.
CREATE TABLE availability.time_off (
  -- PK
  id text PRIMARY KEY,
  merchant_id text,
  -- null = all
  member_user_id text,
  starts_on date,
  ends_on date,
  -- closed | special
  kind text CHECK (kind IN ('closed', 'special')),
  special_ranges jsonb,
  -- private
  reason text
);
-- TODO indexes/constraints: index(merchant_id,starts_on)
-- outbox events: availability.changed

-- Google/Outlook two-way sync tokens and busy blocks.
CREATE TABLE availability.calendar_links (
  -- PK
  id text PRIMARY KEY,
  member_user_id text,
  provider text,
  -- KMS
  token_ref text,
  last_sync_at timestamptz
);

-- Foreign keys (in-module only)
-- logical ref (cross-module, no FK): availability.availability_rules.merchant_id → merchants.merchants.id
-- logical ref (cross-module, no FK): availability.booking_rules.merchant_id → merchants.merchants.id
-- logical ref (cross-module, no FK): availability.time_off.merchant_id → merchants.merchants.id
-- logical ref (cross-module, no FK): availability.calendar_links.member_user_id → identity.users.id
