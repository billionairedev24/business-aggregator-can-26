-- S-29 mobile and courier clients (auth range V024–V029). See docs/DECISIONS.md "S-29".
-- Refresh-token reuse detection for public (mobile) clients. Spring Authorization Server keeps only the current refresh
-- token of an authorization; a rotated one is simply unknown afterwards. Every refresh token issued to a public client
-- is remembered here (SHA-256, never the token) for as long as its authorization — the rotation "family" — exists, so
-- presenting one that was already rotated is recognised as reuse: the family is revoked and the sign-in ends.
CREATE TABLE auth.issued_refresh_tokens (
  token_hash       text        PRIMARY KEY,                    -- base64url SHA-256 of the refresh token
  authorization_id varchar(100) NOT NULL REFERENCES auth.oauth2_authorization(id) ON DELETE CASCADE,
  issued_at        timestamptz NOT NULL,
  expires_at       timestamptz NOT NULL
);
CREATE INDEX ix_issued_refresh_tokens_authorization ON auth.issued_refresh_tokens(authorization_id);

-- A sign-in ended because one of its rotated refresh tokens was presented again (possible theft).
ALTER TABLE identity.sessions DROP CONSTRAINT IF EXISTS sessions_revoke_reason_check;
ALTER TABLE identity.sessions
  ADD CONSTRAINT sessions_revoke_reason_check
  CHECK (revoke_reason IN ('revoked', 'revoked_others', 'signed_out', 'refresh_token_reused'));
