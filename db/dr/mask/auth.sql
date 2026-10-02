-- auth: no prod credential may work in staging. Tokens, sessions, passkeys, TOTP secrets, backup codes and federated
-- links are deleted (staging testers sign up again); WebAuthn user handles are pseudonymised; confidential OAuth
-- clients lose their secret until the oauth-clients Job writes staging's (it runs on every sync).
delete from auth.authorization_sessions;
delete from auth.issued_refresh_tokens;
delete from auth.oauth2_authorization;
delete from auth.oauth2_authorization_consent;
delete from auth.federation_requests;
delete from auth.backup_codes;
delete from auth.totp_secrets;
delete from auth.user_credentials;
delete from auth.federated_identities;
update auth.user_entities set name = pg_temp.nl_email('u', id), display_name = 'User ' || lower(right(id, 6));
update auth.oauth2_registered_client set client_secret = null where client_secret is not null;
