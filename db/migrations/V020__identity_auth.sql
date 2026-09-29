-- Auth / identity workstream (range V020–V029). See docs/DECISIONS.md "Auth workstream".
-- Owner split: northline-auth (server/auth) writes identity.users, identity.sessions, identity.platform_roles and
-- every auth.* table; the api `identity` module reads identity.users for /api/v1/me. Same database, no FK across schemas.

-- Registration (validation-rules.md § Registration): separate first/last name, verified phone, accepted terms version.
ALTER TABLE identity.users
  ADD COLUMN first_name text,
  ADD COLUMN last_name text,
  ADD COLUMN phone_verified_at timestamptz,
  ADD COLUMN terms_version text,
  ADD COLUMN terms_accepted_at timestamptz;

-- The TODO from V002: unique(phone) (E.164) and unique(email) (citext → case-insensitive).
CREATE UNIQUE INDEX ux_users_phone ON identity.users(phone) WHERE phone IS NOT NULL;
CREATE UNIQUE INDEX ux_users_email ON identity.users(email) WHERE email IS NOT NULL;

-- Platform roles carried in the token `roles` claim (merchant team roles live in merchants.merchant_members).
CREATE TABLE identity.platform_roles (
  user_id    text        NOT NULL REFERENCES identity.users(id),
  role       text        NOT NULL CHECK (role IN ('staff', 'admin')),
  granted_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, role)
);

-- Sign-in log: one identity.sessions row per successful sign-in ("every sign-in is logged").
ALTER TABLE identity.sessions
  ADD COLUMN created_at   timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN last_seen_at timestamptz,
  ADD COLUMN method       text CHECK (method IN ('passkey', 'totp', 'backup_code', 'registration', 'google', 'apple')),
  ADD COLUMN acr          text;
CREATE INDEX ix_sessions_user ON identity.sessions(user_id, created_at DESC);
CREATE INDEX ix_passkeys_user ON identity.passkeys(user_id);

-- One-time backup codes ("One of your 10 printed codes"). Only a SHA-256 of the normalised code is stored.
CREATE TABLE auth.backup_codes (
  id         text        PRIMARY KEY,
  user_id    text        NOT NULL,
  code_hash  text        NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  used_at    timestamptz
);
CREATE INDEX ix_backup_codes_user ON auth.backup_codes(user_id) WHERE used_at IS NULL;

-- TOTP replay protection: the last accepted 30-second step can't be used twice.
ALTER TABLE auth.totp_secrets ADD COLUMN last_used_step bigint;

-- WebAuthn user handle ↔ identity.users id lookup (user_entities.name holds the identity.users id).
CREATE UNIQUE INDEX ux_user_entities_name ON auth.user_entities(name);
CREATE INDEX ix_user_credentials_user ON auth.user_credentials(user_entity_user_id);

-- Failed and successful sign-ins are also written to the audit log (action auth.sign_in / auth.sign_in_failed).
CREATE INDEX ix_audit_log_actor ON developer.audit_log(actor_id, at DESC);
