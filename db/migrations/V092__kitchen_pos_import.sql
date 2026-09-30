-- S-36 POS menu import for kitchens (kitchen range V090–V099). Additive only; every addition is recorded in
-- docs/DECISIONS.md › S-36. docs/runbooks/pos-menu-import.md explains the flows.

-- ── The kitchen's POS connection (one per kitchen and POS) ─────────────────────────────────────────────────────────
CREATE TABLE food.pos_connections (
  merchant_id text NOT NULL,
  provider text NOT NULL CHECK (provider IN ('square', 'clover', 'toast')),
  id text NOT NULL UNIQUE,                               -- ULID; the sealed credentials are bound to it
  status text NOT NULL CHECK (status IN ('connected', 'reconnect', 'disconnected')),
  account_id text,                                       -- Square merchant id · Clover merchant id · Toast restaurant GUID
  account_label text,
  token_ref text,                                        -- key that wrapped the data key (SecretSealer, S-32)
  credentials_key bytea,
  credentials_enc bytea,                                 -- access + refresh token (Toast: none, partner credentials)
  connected_at timestamptz,
  last_import_at timestamptz,
  state_changed_at timestamptz,
  PRIMARY KEY (merchant_id, provider)
);

-- ── OAuth requests (state stored as SHA-256; single use, 10 min) ───────────────────────────────────────────────────
CREATE TABLE food.pos_oauth_requests (
  state_hash text PRIMARY KEY,
  merchant_id text NOT NULL,
  user_id text NOT NULL,
  provider text NOT NULL CHECK (provider IN ('square', 'clover')),
  menu_id text,                                          -- where the Studio returns (the menu being imported into)
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL
);
CREATE INDEX ix_pos_oauth_requests_expiry ON food.pos_oauth_requests (expires_at);

-- ── What each POS entity became (re-imports diff against it) ───────────────────────────────────────────────────────
CREATE TABLE food.pos_links (
  merchant_id text NOT NULL,
  provider text NOT NULL CHECK (provider IN ('square', 'clover', 'toast')),
  kind text NOT NULL CHECK (kind IN ('section', 'item', 'group', 'option')),
  scope text NOT NULL,                                   -- the menu id for sections and items; '' for groups/options
  external_id text NOT NULL,
  local_id text NOT NULL,                                -- menu_sections / menu_items / modifier_groups / modifier_options id
  content_hash text NOT NULL,                            -- what was imported (name, description, price, modifiers)
  removed_at timestamptz,                                -- gone from the POS → item hidden (draft), never deleted
  updated_at timestamptz NOT NULL,
  PRIMARY KEY (merchant_id, provider, kind, scope, external_id)
);
CREATE INDEX ix_pos_links_local ON food.pos_links (local_id);

-- ── Previews ("Review changes" → "Apply") ──────────────────────────────────────────────────────────────────────────
CREATE TABLE food.pos_imports (
  id text PRIMARY KEY,
  merchant_id text NOT NULL,
  menu_id text NOT NULL,
  provider text NOT NULL CHECK (provider IN ('square', 'clover', 'toast')),
  status text NOT NULL CHECK (status IN ('preview', 'applied', 'discarded')),
  menu jsonb NOT NULL,                                   -- the POS menu as read (normalised)
  diff jsonb NOT NULL,                                   -- what applying would do
  created_by text NOT NULL,
  created_at timestamptz NOT NULL,
  applied_at timestamptz
);
CREATE INDEX ix_pos_imports_merchant ON food.pos_imports (merchant_id, created_at DESC);

