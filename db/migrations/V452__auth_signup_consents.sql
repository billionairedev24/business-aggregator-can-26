-- S-157 CASL consent at sign-up (range V452–V461). Additive only.
-- The Create account form's unticked marketing checkboxes (consumer site, Studio): a ticked box is kept with the
-- pending account — category, the wording version shown, its language, when, the truncated IP address and the hashed
-- user agent, as a JSON array — and becomes a messaging.consent_records grant (source web_signup) in the transaction
-- that creates the account. An abandoned sign-up is purged with its pending row, consent included. No address here:
-- the email is the row's own; the phone, when a text consent was ticked, is the row's optional mobile.
ALTER TABLE auth.pending_accounts
  ADD COLUMN marketing_consents jsonb NOT NULL DEFAULT '[]'::jsonb
    CHECK (jsonb_typeof(marketing_consents) = 'array' AND jsonb_array_length(marketing_consents) <= 2);

COMMENT ON COLUMN auth.pending_accounts.marketing_consents IS
  'S-157: sign-up consents to commercial messages ticked on the form (CASL), written to messaging.consent_records '
  'when the account is created.';
