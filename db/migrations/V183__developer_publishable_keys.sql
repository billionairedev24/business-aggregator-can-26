-- S-76: publishable keys for the website embed snippet. A publishable key is public by design (it sits in the
-- merchant's page source), so it is stored as is, unlike the hashed secret API keys. One active key per business;
-- rolling it revokes the previous one. `allowed_origins` limits the sites the embed answers on (empty = any site).
CREATE TABLE developer.publishable_keys (
  id text PRIMARY KEY,
  merchant_id text NOT NULL,
  key text NOT NULL UNIQUE CHECK (key ~ '^pk_live_[A-Za-z0-9_-]{20,64}$'),
  allowed_origins text[] NOT NULL DEFAULT '{}',
  created_by text,
  created_at timestamptz NOT NULL DEFAULT now(),
  revoked_at timestamptz
);
CREATE UNIQUE INDEX ux_publishable_keys_active ON developer.publishable_keys (merchant_id) WHERE revoked_at IS NULL;

-- logical ref (cross-module, no FK): developer.publishable_keys.merchant_id → merchants.merchants.id
