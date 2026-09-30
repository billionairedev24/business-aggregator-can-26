-- S-62 (consumer web): consumers may sign in with a code to their phone alone. The sign-in log (identity.sessions,
-- V020) records how each sign-in was made; the new method is 'phone_otp' (no second factor, no acr).
-- Widens the CHECK only: every existing row stays valid.
ALTER TABLE identity.sessions DROP CONSTRAINT IF EXISTS sessions_method_check;
ALTER TABLE identity.sessions
  ADD CONSTRAINT sessions_method_check
  CHECK (method IN ('passkey', 'totp', 'backup_code', 'registration', 'google', 'apple', 'phone_otp'));
