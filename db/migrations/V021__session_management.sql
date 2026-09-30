-- S-19 session management (auth range V020–V029). See docs/DECISIONS.md "S-19 session management".
-- A "session" is one successful sign-in: an identity.sessions row. The auth server's HTTP session carries its id, and
-- every OAuth authorization (the BFF's or an app's refresh token) issued from that HTTP session is linked to it here,
-- so revoking the sign-in ends the auth session, the refresh tokens and — through the BFF's check — the BFF session.

-- Why a sign-in ended (revoked_at has been there since V002): revoked from Settings › Security (one, or "all others")
-- or signed out.
ALTER TABLE identity.sessions
  ADD COLUMN revoke_reason text CHECK (revoke_reason IN ('revoked', 'revoked_others', 'signed_out'));
CREATE INDEX ix_sessions_user_open ON identity.sessions(user_id) WHERE revoked_at IS NULL;

-- OAuth authorization (Spring Authorization Server row) → the sign-in it was issued from. Removed with the
-- authorization (cascade); session_id is a logical reference to identity.sessions. `refreshable` = its refresh token
-- still works (false once revoked, e.g. by the BFF's sign-out), i.e. it keeps the session listed as active.
CREATE TABLE auth.authorization_sessions (
  authorization_id varchar(100) PRIMARY KEY REFERENCES auth.oauth2_authorization(id) ON DELETE CASCADE,
  session_id       text        NOT NULL,
  refreshable      boolean     NOT NULL DEFAULT true,
  created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_authorization_sessions_session ON auth.authorization_sessions(session_id);
CREATE INDEX ix_oauth2_authorization_principal ON auth.oauth2_authorization(principal_name);
