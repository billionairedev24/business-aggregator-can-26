-- Event management + Spring Authorization Server storage.
create schema if not exists events;

-- Spring Modulith JDBC event publication registry (transactional outbox). Matches Modulith 2.x schema.
create table if not exists events.event_publication (
  id                     uuid primary key,
  listener_id            text        not null,
  event_type             text        not null,
  serialized_event       text        not null,
  publication_date       timestamptz not null,
  completion_date        timestamptz,
  status                 text,
  completion_attempts    int         not null default 0,
  last_resubmission_date timestamptz
);
create index if not exists event_publication_incomplete on events.event_publication (publication_date) where completion_date is null;
create table if not exists events.event_publication_archive (like events.event_publication including all);

-- Consumer-side dedupe for at-least-once Kafka delivery.
create table events.processed_events (
  consumer     text        not null,
  event_id     text        not null,
  processed_at timestamptz not null default now(),
  primary key (consumer, event_id)
);

-- Spring Authorization Server (official DDL, Postgres types).
create schema if not exists auth;
create table auth.oauth2_registered_client (
  id varchar(100) primary key, client_id varchar(100) not null unique, client_id_issued_at timestamptz not null default now(),
  client_secret varchar(200), client_secret_expires_at timestamptz, client_name varchar(200) not null,
  client_authentication_methods varchar(1000) not null, authorization_grant_types varchar(1000) not null,
  redirect_uris varchar(1000), post_logout_redirect_uris varchar(1000), scopes varchar(1000) not null,
  client_settings varchar(2000) not null, token_settings varchar(2000) not null
);
create table auth.oauth2_authorization (
  id varchar(100) primary key, registered_client_id varchar(100) not null, principal_name varchar(200) not null,
  authorization_grant_type varchar(100) not null, authorized_scopes varchar(1000), attributes text, state varchar(500),
  authorization_code_value text, authorization_code_issued_at timestamptz, authorization_code_expires_at timestamptz, authorization_code_metadata text,
  access_token_value text, access_token_issued_at timestamptz, access_token_expires_at timestamptz, access_token_metadata text, access_token_type varchar(100), access_token_scopes varchar(1000),
  oidc_id_token_value text, oidc_id_token_issued_at timestamptz, oidc_id_token_expires_at timestamptz, oidc_id_token_metadata text,
  refresh_token_value text, refresh_token_issued_at timestamptz, refresh_token_expires_at timestamptz, refresh_token_metadata text,
  user_code_value text, user_code_issued_at timestamptz, user_code_expires_at timestamptz, user_code_metadata text,
  device_code_value text, device_code_issued_at timestamptz, device_code_expires_at timestamptz, device_code_metadata text
);
create table auth.oauth2_authorization_consent (
  registered_client_id varchar(100) not null, principal_name varchar(200) not null, authorities varchar(1000) not null,
  primary key (registered_client_id, principal_name)
);
-- Passkeys (Spring Security WebAuthn JDBC repositories).
create table auth.user_entities (id varchar(1000) primary key, name varchar(100) not null, display_name varchar(200));
create table auth.user_credentials (
  credential_id varchar(1000) primary key, user_entity_user_id varchar(1000) not null references auth.user_entities(id) on delete cascade,
  public_key bytea not null, signature_count bigint, uv_initialized boolean, backup_eligible boolean not null, authenticator_transports varchar(1000),
  public_key_credential_type varchar(100), backup_state boolean not null, attestation_object bytea, attestation_client_data_json bytea,
  created timestamptz, last_used timestamptz, label varchar(1000) not null
);
create table auth.totp_secrets (user_id text primary key, secret_enc bytea not null, confirmed_at timestamptz);
