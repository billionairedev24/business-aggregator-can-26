-- Engineering follow-ups (S-115 gap; docs/runbooks/key-rotation.md § 7): TOTP_KEY can be rotated. The id of the key
-- that encrypted each authenticator secret (TOTP_KEY_ID; NULL = written before key ids, i.e. the first key, "v1").
-- The re-encryption job moves rows to the current key and finds them by this column.
ALTER TABLE auth.totp_secrets ADD COLUMN key_id text;
CREATE INDEX totp_secrets_key_id ON auth.totp_secrets (key_id);
