-- S-154 (docs/DECISIONS.md § S-154): accounts with an email and a password, the email confirmed by a code or a link,
-- a second factor enrolled right after. Owner: northline-auth (auth.* and its identity.* columns). Additive only.

-- The password of an account: an Argon2id hash in Spring Security's format ({argon2}$argon2id$v=19$m=…,t=…,p=…$salt$hash).
-- Never the password itself; never logged. One row per identity.users id; accounts from before S-154 have none.
CREATE TABLE auth.passwords (
  user_id    text        PRIMARY KEY,
  hash       text        NOT NULL,
  changed_at timestamptz NOT NULL DEFAULT now()
);

-- An account that was created on the form but whose email isn't confirmed yet. identity.users gets the row only when
-- the code or the link is used (the api, the console and every module never see unconfirmed accounts). Registering the
-- same address again replaces the pending row (so nobody can squat an address by never confirming it).
CREATE TABLE auth.pending_accounts (
  id                         text        PRIMARY KEY,          -- the identity.users id it becomes
  email                      citext      NOT NULL,
  first_name                 text        NOT NULL,
  last_name                  text        NOT NULL,
  phone                      text,                             -- optional, E.164, stored unverified
  password_hash              text        NOT NULL,
  locale                     text        NOT NULL,             -- en-CA | fr-CA
  app                        text        NOT NULL CHECK (app IN ('studio', 'consumer', 'console')),
  terms_version              text        NOT NULL,
  terms_accepted_at          timestamptz NOT NULL,
  terms_language             text        CHECK (terms_language IN ('en', 'fr')),
  terms_english_requested_at timestamptz,
  created_at                 timestamptz NOT NULL DEFAULT now(),
  expires_at                 timestamptz NOT NULL
);
CREATE UNIQUE INDEX ux_pending_accounts_email ON auth.pending_accounts(email);

-- One-time codes sent by email: confirm the address of a pending account, or reset a password. Only SHA-256 hashes of
-- the 6-digit code and of the link's token are stored; a code works once, until expires_at, for a few wrong tries.
CREATE TABLE auth.email_codes (
  id              text        PRIMARY KEY,
  purpose         text        NOT NULL CHECK (purpose IN ('verify_email', 'reset_password')),
  subject_id      text        NOT NULL,                        -- pending_accounts.id | identity.users.id
  app             text        NOT NULL CHECK (app IN ('studio', 'consumer', 'console')),
  code_hash       text        NOT NULL,
  token_hash      text        NOT NULL,
  failed_attempts integer     NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
  created_at      timestamptz NOT NULL,
  expires_at      timestamptz NOT NULL,
  used_at         timestamptz
);
CREATE UNIQUE INDEX ux_email_codes_token ON auth.email_codes(token_hash);
CREATE INDEX ix_email_codes_subject ON auth.email_codes(subject_id, purpose, created_at DESC);

-- When the person proved they read this address (a verification or reset code / link). NULL for older accounts.
ALTER TABLE identity.users ADD COLUMN email_verified_at timestamptz;

-- Every phone stored before S-154 was confirmed by an SMS code at registration (RegistrationService sets
-- phone_verified_at), except rows inserted outside the auth server. The optional mobile of an email account is stored
-- unverified, and the phone sign-in codes (S-62) only go to confirmed numbers, so mark the older ones confirmed.
UPDATE identity.users SET phone_verified_at = coalesce(created_at, now())
 WHERE phone IS NOT NULL AND phone_verified_at IS NULL;

-- Sign-in log: a sign-in with the password alone (before enrolment, or a customer without a second factor).
ALTER TABLE identity.sessions DROP CONSTRAINT IF EXISTS sessions_method_check;
ALTER TABLE identity.sessions
  ADD CONSTRAINT sessions_method_check
  CHECK (method IN ('passkey', 'totp', 'backup_code', 'registration', 'google', 'apple', 'phone_otp', 'password'));

-- Why a session ended: a password reset or change signs out every other session.
ALTER TABLE identity.sessions DROP CONSTRAINT IF EXISTS sessions_revoke_reason_check;
ALTER TABLE identity.sessions
  ADD CONSTRAINT sessions_revoke_reason_check
  CHECK (revoke_reason IN ('revoked', 'revoked_others', 'signed_out', 'refresh_token_reused', 'erased',
                           'password_reset', 'password_changed'));
