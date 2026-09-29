-- schema: identity · owner: Java (java)
-- Generated from the Northline spec (docs/DATA_MODEL.md). Hand-edit freely; keep column names.
CREATE SCHEMA IF NOT EXISTS identity;

-- One identity for customers, merchant staff, couriers and admins; roles are per context.
CREATE TABLE identity.users (
  -- PK
  id text PRIMARY KEY,
  -- unique · E.164 · verified
  phone text,
  -- unique
  email citext,
  display_name text,
  -- en-CA | fr-CA
  locale text,
  -- passkey | totp | sms
  mfa_primary text CHECK (mfa_primary IN ('passkey', 'totp', 'sms')),
  -- customer trust, 0–5
  reliability_score numeric(3,2),
  -- active | suspended | erased
  status text CHECK (status IN ('active', 'suspended', 'erased')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
-- TODO indexes/constraints: unique(phone), unique(email); RLS: self or admin
-- outbox events: user.created · user.mfa_changed

-- WebAuthn credentials per user/device.
CREATE TABLE identity.passkeys (
  -- PK
  id text PRIMARY KEY,
  -- FK users
  user_id text,
  -- unique
  credential_id bytea,
  public_key bytea,
  device_label text,
  last_used_at timestamptz
);
-- TODO indexes/constraints: index(user_id)
-- outbox events: —

-- Refresh tokens and device list (Redis holds access tokens).
CREATE TABLE identity.sessions (
  -- PK
  id text PRIMARY KEY,
  -- FK users
  user_id text,
  device text,
  ip inet,
  city text,
  revoked_at timestamptz
);
-- TODO indexes/constraints: index(user_id) · TTL job
-- outbox events: session.revoked

-- Plus membership unit; up to 4 members.
CREATE TABLE identity.households (
  -- PK
  id text PRIMARY KEY,
  name text,
  -- none | monthly | annual
  plus_plan text CHECK (plus_plan IN ('none', 'monthly', 'annual')),
  renews_at timestamptz,
  stripe_subscription_id text
);
-- outbox events: household.plus_changed

-- M:N users ↔ households.
CREATE TABLE identity.household_members (
  -- FK
  household_id text,
  -- FK
  user_id text,
  -- owner | member
  role text CHECK (role IN ('owner', 'member')),
  PRIMARY KEY (household_id,user_id)
);
-- TODO indexes/constraints: PK(household_id,user_id)

-- Customer addresses resolved via Google Places; zone precomputed.
CREATE TABLE identity.addresses (
  -- PK
  id text PRIMARY KEY,
  -- FK users
  user_id text,
  -- Google
  place_id text,
  street text,
  unit text,
  city text,
  province char(2),
  postal text,
  geom geography(Point),
  -- FK region.zones (by id)
  zone_id text,
  -- shared 2 h around visit
  access_note text,
  is_default boolean
);
-- TODO indexes/constraints: GiST(geom) · index(user_id)
-- outbox events: address.changed

-- Foreign keys (in-module only)
ALTER TABLE identity.passkeys ADD CONSTRAINT fk_passkeys_user_id FOREIGN KEY (user_id) REFERENCES identity.users(id);
ALTER TABLE identity.sessions ADD CONSTRAINT fk_sessions_user_id FOREIGN KEY (user_id) REFERENCES identity.users(id);
ALTER TABLE identity.household_members ADD CONSTRAINT fk_household_members_household_id FOREIGN KEY (household_id) REFERENCES identity.households(id);
ALTER TABLE identity.household_members ADD CONSTRAINT fk_household_members_user_id FOREIGN KEY (user_id) REFERENCES identity.users(id);
ALTER TABLE identity.addresses ADD CONSTRAINT fk_addresses_user_id FOREIGN KEY (user_id) REFERENCES identity.users(id);
-- logical ref (cross-module, no FK): identity.addresses.zone_id → region.zones.id
