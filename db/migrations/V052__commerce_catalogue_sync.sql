-- S-35 Shopify, Square and Lightspeed catalogue sync (catalogue range V050–V059). Additive only; every addition is
-- recorded in docs/DECISIONS.md › S-35. docs/runbooks/commerce-sync.md explains the flows.

-- ── The connection (one per merchant and platform, V050) ───────────────────────────────────────────────────────────
ALTER TABLE catalogue.integrations
  ADD COLUMN id text,                                    -- ULID; the sealed credentials are bound to it
  ADD COLUMN connection_state text NOT NULL DEFAULT 'ok' CHECK (connection_state IN ('ok', 'reconnect')),
  ADD COLUMN external_account_id text,                   -- Shopify shop domain · Square merchant id · Lightspeed domain prefix
  ADD COLUMN scopes text[] NOT NULL DEFAULT '{}',
  ADD COLUMN token_ref text,                             -- key that wrapped the data key (SecretSealer, S-32)
  ADD COLUMN credentials_key bytea,                      -- wrapped data key
  ADD COLUMN credentials_enc bytea,                      -- access + refresh token, AES-256-GCM (never in clear)
  ADD COLUMN webhooks text NOT NULL DEFAULT 'none' CHECK (webhooks IN ('none', 'active', 'failed')),
  ADD COLUMN sync_status text CHECK (sync_status IN ('importing', 'ok', 'failed')),
  ADD COLUMN last_error text,
  ADD COLUMN created_count integer NOT NULL DEFAULT 0,   -- last full sync: drafts created
  ADD COLUMN hidden_count integer NOT NULL DEFAULT 0,    -- last full sync: listings hidden (removed on the platform)
  -- [{ "externalId", "title", "error" }] products the last full sync couldn't import
  ADD COLUMN sync_errors jsonb NOT NULL DEFAULT '[]',
  ADD COLUMN last_polled_at timestamptz,
  ADD COLUMN state_changed_at timestamptz;
CREATE UNIQUE INDEX ux_integrations_id ON catalogue.integrations (id) WHERE id IS NOT NULL;
CREATE INDEX ix_integrations_account ON catalogue.integrations (provider, external_account_id);

-- ── OAuth requests (state stored as SHA-256; single use, 10 min) ───────────────────────────────────────────────────
CREATE TABLE catalogue.commerce_oauth_requests (
  state_hash text PRIMARY KEY,
  merchant_id text NOT NULL,
  user_id text NOT NULL,
  provider text NOT NULL CHECK (provider IN ('shopify', 'square', 'lightspeed')),
  shop text,                                             -- Shopify: the store the merchant typed
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL
);
CREATE INDEX ix_commerce_oauth_requests_expiry ON catalogue.commerce_oauth_requests (expires_at);

-- ── External id → listing mapping (kept after a disconnect, so a reconnect re-links instead of duplicating) ────────
CREATE TABLE catalogue.commerce_products (
  merchant_id text NOT NULL,
  provider text NOT NULL CHECK (provider IN ('shopify', 'square', 'lightspeed')),
  external_id text NOT NULL,                             -- Shopify product gid · Square ITEM id · Lightspeed family id
  offer_id text NOT NULL,                                -- catalogue.offers.id (logical: the merchant may delete it)
  content_hash text NOT NULL,                            -- title, description, images, variant structure last applied
  external_updated_at timestamptz,
  removed_at timestamptz,                                -- gone / archived on the platform → listing hidden
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (merchant_id, provider, external_id)
);
CREATE INDEX ix_commerce_products_offer ON catalogue.commerce_products (offer_id);

CREATE TABLE catalogue.commerce_variants (
  merchant_id text NOT NULL,
  provider text NOT NULL CHECK (provider IN ('shopify', 'square', 'lightspeed')),
  external_id text NOT NULL,                             -- variant id on the platform
  product_external_id text NOT NULL,
  offer_id text NOT NULL,
  sku text NOT NULL,                                     -- the Northline variant (or single-variant offer) SKU
  stock_ref text,                                        -- Shopify inventory item · Square variation · Lightspeed product
  PRIMARY KEY (merchant_id, provider, external_id)
);
CREATE INDEX ix_commerce_variants_stock_ref ON catalogue.commerce_variants (provider, stock_ref);
CREATE INDEX ix_commerce_variants_product ON catalogue.commerce_variants (merchant_id, provider, product_external_id);

-- ── Webhook dedupe (purged after 7 days) ───────────────────────────────────────────────────────────────────────────
CREATE TABLE catalogue.commerce_webhook_receipts (
  provider text NOT NULL,
  delivery_id text NOT NULL,                             -- Shopify X-Shopify-Event-Id · Square event_id · Lightspeed hash
  received_at timestamptz NOT NULL,
  PRIMARY KEY (provider, delivery_id)
);
CREATE INDEX ix_commerce_webhook_receipts_received ON catalogue.commerce_webhook_receipts (received_at);
