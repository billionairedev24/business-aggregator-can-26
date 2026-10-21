-- S-158: partner API clients registered in the console (S-30 had them in configuration only) and the owners'
-- consent. northline-auth registers each active row as OAuth client 'partner:<name>' (private_key_jwt against its
-- JWK Set URL, client credentials) and puts only the businesses whose owner approved into the token's merchants
-- claim. A partner in northline.oauth.partners (configuration) with the same name wins; see docs/runbooks/partners.md.
-- Nothing secret: public key URLs, scopes, business ids.
CREATE TABLE developer.partner_clients (
  id               text PRIMARY KEY,
  name             text NOT NULL CHECK (name ~ '^[a-z0-9][a-z0-9-]{0,49}$'),   -- client id = 'partner:' || name
  display_name     text NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 80),
  jwk_set_url      text NOT NULL,
  scopes           text[] NOT NULL CHECK (cardinality(scopes) > 0 AND scopes <@ ARRAY['api.read', 'api.write']),
  token_ttl_seconds integer NOT NULL DEFAULT 900 CHECK (token_ttl_seconds BETWEEN 60 AND 3600),
  state            text NOT NULL DEFAULT 'active' CHECK (state IN ('active', 'disabled')),
  -- bumped by "Rotate keys": auth drops the cached keys and reads the JWK Set again (the URL may change too)
  key_version      integer NOT NULL DEFAULT 1,
  rotated_at       timestamptz,
  created_by       text NOT NULL,
  created_at       timestamptz NOT NULL,
  updated_at       timestamptz NOT NULL,
  disabled_by      text,
  disabled_at      timestamptz,
  CONSTRAINT ux_partner_clients_name UNIQUE (name),
  CHECK ((state = 'disabled') = (disabled_at IS NOT NULL))
);

-- The businesses a partner asks to act for, and each owner's answer. Only 'approved' rows reach a token.
CREATE TABLE developer.partner_bindings (
  partner_id   text NOT NULL REFERENCES developer.partner_clients (id),
  merchant_id  text NOT NULL,                            -- merchants.merchants.id (logical)
  state        text NOT NULL DEFAULT 'requested' CHECK (state IN ('requested', 'approved', 'declined', 'revoked')),
  requested_by text NOT NULL,                            -- the staff member
  requested_at timestamptz NOT NULL,
  decided_by   text,                                     -- the owner (or the staff member who removed it)
  decided_at   timestamptz,
  PRIMARY KEY (partner_id, merchant_id),
  CHECK ((state = 'requested') = (decided_at IS NULL))
);
CREATE INDEX ix_partner_bindings_merchant ON developer.partner_bindings (merchant_id, state);
