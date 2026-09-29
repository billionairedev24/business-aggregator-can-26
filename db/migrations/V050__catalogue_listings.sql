-- Catalogue workstream (V050–V059). Additive only: display columns, editor fields, vetting state, bulk imports and
-- integration connections for design/02 Provider Studio › Listings · product editor · service editor · bulk upload.
-- Every addition is recorded in docs/DECISIONS.md › Catalogue.

-- ── Shared catalogue record (Amazon-ASIN style) ────────────────────────────────────────────────────────────────────
ALTER TABLE catalogue.catalog_products
  ADD COLUMN ref text,                                   -- display code NL-P-88120
  ADD COLUMN title text,                                 -- default-language title (title_i18n keeps translations)
  ADD COLUMN identifier_type text NOT NULL DEFAULT 'gtin' CHECK (identifier_type IN ('gtin', 'ean', 'isbn', 'none')),
  ADD COLUMN mpn text,                                   -- manufacturer part #
  ADD COLUMN description text,
  ADD COLUMN bullets text[] NOT NULL DEFAULT '{}',
  ADD COLUMN owner_merchant_id text,                     -- seller-owned record (local / handmade goods); null = shared GTIN record
  ADD COLUMN created_by_merchant_id text,                -- first seller of a shared record (may edit it until locked)
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
CREATE SEQUENCE catalogue.catalog_product_ref_seq START 88200;
CREATE UNIQUE INDEX ux_catalog_products_gtin ON catalogue.catalog_products (gtin) WHERE gtin IS NOT NULL;
CREATE UNIQUE INDEX ux_catalog_products_ref ON catalogue.catalog_products (ref) WHERE ref IS NOT NULL;
CREATE INDEX ix_catalog_products_category ON catalogue.catalog_products (category_id);

-- ── Offers (a merchant's product listing) ──────────────────────────────────────────────────────────────────────────
ALTER TABLE catalogue.offers
  ADD COLUMN title text,                                 -- Studio listing name
  ADD COLUMN variant_theme text NOT NULL DEFAULT 'none'
    CHECK (variant_theme IN ('none', 'size', 'colour', 'size_colour', 'length', 'length_position')),
  ADD COLUMN image_source text NOT NULL DEFAULT 'own' CHECK (image_source IN ('shared', 'own')),
  ADD COLUMN own_images text[] NOT NULL DEFAULT '{}',    -- catalogue.media ids, main first
  ADD COLUMN handling_time text CHECK (handling_time IN ('same_day', 'next_day', 'two_days')),
  ADD COLUMN returns_policy text CHECK (returns_policy IN ('standard_14', 'final_sale')),
  ADD COLUMN country_of_origin text,
  ADD COLUMN restricted_ok boolean NOT NULL DEFAULT false,  -- "Not a restricted product" attestation
  ADD COLUMN bilingual_ok boolean NOT NULL DEFAULT false,   -- bilingual labelling attestation
  ADD COLUMN warranty boolean NOT NULL DEFAULT false,
  ADD COLUMN search_keywords text,
  ADD COLUMN vetting_flags text[] NOT NULL DEFAULT '{}',
  ADD COLUMN submitted_at timestamptz,
  ADD COLUMN sales_30d integer NOT NULL DEFAULT 0,       -- read model; fed by the orders workstream
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE catalogue.offers ADD CONSTRAINT ck_offers_condition
  CHECK (condition IS NULL OR condition IN ('new', 'open_box', 'refurbished', 'used_good'));
ALTER TABLE catalogue.offers ADD CONSTRAINT ck_offers_money
  CHECK ((price_cents IS NULL OR price_cents >= 0) AND (stock IS NULL OR stock >= 0));
CREATE INDEX ix_offers_merchant ON catalogue.offers (merchant_id);
CREATE INDEX ix_offers_product ON catalogue.offers (product_id);
CREATE UNIQUE INDEX ux_offers_merchant_sku ON catalogue.offers (merchant_id, sku) WHERE sku IS NOT NULL;

ALTER TABLE catalogue.variants ADD COLUMN position integer NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX ux_variants_offer_sku ON catalogue.variants (offer_id, sku) WHERE sku IS NOT NULL;

-- ── Services ───────────────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE catalogue.services
  ADD COLUMN name text,                                  -- default-language name (name_i18n keeps translations)
  ADD COLUMN sku text,
  ADD COLUMN included text,                              -- "What's included"
  ADD COLUMN vetting_flags text[] NOT NULL DEFAULT '{}',
  ADD COLUMN submitted_at timestamptz,
  ADD COLUMN sales_30d integer NOT NULL DEFAULT 0,
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE catalogue.services ADD CONSTRAINT ck_services_vetting
  CHECK (vetting IS NULL OR vetting IN ('draft', 'pending', 'approved', 'rejected'));
ALTER TABLE catalogue.services ADD CONSTRAINT ck_services_status CHECK (status IS NULL OR status IN ('live', 'hidden'));
CREATE UNIQUE INDEX ux_services_merchant_sku ON catalogue.services (merchant_id, sku) WHERE sku IS NOT NULL;

-- ── Media ──────────────────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE catalogue.media
  ADD COLUMN merchant_id text,                           -- uploader
  ADD COLUMN content_type text,
  ADD COLUMN width integer,
  ADD COLUMN height integer,
  ADD COLUMN byte_size integer,
  ADD COLUMN on_white boolean,                           -- border pixels are near-white (main-image standard)
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX ix_media_phash ON catalogue.media (phash);
CREATE INDEX ix_media_owner ON catalogue.media (owner_type, owner_id);

-- ── Category attribute templates (required attributes + variation themes per leaf) ─────────────────────────────────
-- Kept apart from catalogue.categories (which is upserted from db/seed/categories.json by `seedCategories`), so the
-- rows can exist before the categories are seeded. No FK for the same reason.
CREATE TABLE catalogue.attribute_templates (
  category_id text PRIMARY KEY,
  -- [{ "key", "label", "options": [..], "required": true }]
  attributes jsonb NOT NULL DEFAULT '[]',
  -- subset of offers.variant_theme codes, in display order
  variant_themes text[] NOT NULL DEFAULT '{size,colour,size_colour}'
);

-- ── Bulk imports ───────────────────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE catalogue.imports (
  id text PRIMARY KEY,
  merchant_id text NOT NULL,
  file_name text NOT NULL,
  template text NOT NULL CHECK (template IN ('auto_parts', 'groceries', 'clothing', 'services', 'price_stock')),
  row_count integer NOT NULL,
  create_count integer NOT NULL DEFAULT 0,
  update_count integer NOT NULL DEFAULT 0,
  error_count integer NOT NULL DEFAULT 0,
  -- [{ "row", "sku", "error" }]
  errors jsonb NOT NULL DEFAULT '[]',
  -- validated rows waiting for "Import N valid rows"
  pending_rows jsonb NOT NULL DEFAULT '[]',
  status text NOT NULL CHECK (status IN ('validated', 'imported')),
  created_by text,
  created_at timestamptz NOT NULL DEFAULT now(),
  imported_at timestamptz
);
CREATE INDEX ix_imports_merchant ON catalogue.imports (merchant_id, created_at DESC);

-- ── Commerce integrations (Shopify / Square / Lightspeed) ───────────────────────────────────────────────────────────
CREATE TABLE catalogue.integrations (
  merchant_id text NOT NULL,
  provider text NOT NULL CHECK (provider IN ('shopify', 'square', 'lightspeed')),
  status text NOT NULL CHECK (status IN ('connected', 'disconnected')),
  account_label text,
  connected_at timestamptz,
  last_sync_at timestamptz,
  last_sync_count integer,
  PRIMARY KEY (merchant_id, provider)
);
