-- S-18 Google and Apple sign-in (auth range V020–V029). See docs/DECISIONS.md "S-18 Google and Apple sign-in".

-- A Google / Apple account linked to a Northline account. Linked either when the person creates an account after
-- "Continue with Google/Apple", or when they confirm an existing account (same verified email) with their second
-- factor. The provider's `sub` is the key: the email may change (and Apple's may be a private relay address).
CREATE TABLE auth.federated_identities (
  provider      text        NOT NULL CHECK (provider IN ('google', 'apple')),
  subject       text        NOT NULL,
  user_id       text        NOT NULL,                  -- identity.users.id (logical)
  email         citext,                                -- as the provider last reported it
  private_relay boolean     NOT NULL DEFAULT false,    -- Apple "Hide My Email" (…@privaterelay.appleid.com)
  linked_at     timestamptz NOT NULL,
  last_used_at  timestamptz,
  PRIMARY KEY (provider, subject)
);
CREATE INDEX ix_federated_identities_user ON auth.federated_identities(user_id);

-- OAuth authorization requests to Google / Apple in flight, by `state`. Apple answers with a cross-site form POST
-- (response_mode=form_post), which doesn't carry the SameSite=Lax session cookie, so the request can't live in the
-- HTTP session. Rows live 10 minutes and are deleted when the answer arrives.
CREATE TABLE auth.federation_requests (
  state      text        PRIMARY KEY,
  request    bytea       NOT NULL,                     -- serialised OAuth2AuthorizationRequest
  expires_at timestamptz NOT NULL
);
CREATE INDEX ix_federation_requests_expiry ON auth.federation_requests(expires_at);
