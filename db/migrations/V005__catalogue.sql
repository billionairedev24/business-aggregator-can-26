-- schema: catalogue · owner: Java (java)
-- Generated from the Northline spec (docs/DATA_MODEL.md). Hand-edit freely; keep column names.
CREATE SCHEMA IF NOT EXISTS catalogue;

-- Two roots (services, shop) + food; grouped taxonomy (~120 services) with regulator and tax code per category.
CREATE TABLE catalogue.categories (
  -- PK
  id text PRIMARY KEY,
  -- self · group → leaf
  parent_id text,
  -- service | shop | food
  root text CHECK (root IN ('service', 'shop', 'food')),
  name_i18n jsonb,
  -- visit | home | event | appointment | consult | null
  booking_type text CHECK (booking_type IN ('visit', 'home', 'event', 'appointment', 'consult', 'null')),
  -- AMVIC · Safety Codes · RECA · ProServe · RMT · null
  regulated_registry text,
  -- vulnerable-sector
  requires_vs_check boolean,
  tax_code text,
  required_attributes jsonb,
  -- synonyms for type-ahead
  search_terms text[]
);
-- TODO indexes/constraints: index(parent_id) · gin(search_terms)
-- outbox events: category.changed
-- search projection: facets

-- Shared record for standard goods (GTIN-matched, Amazon-ASIN style).
CREATE TABLE catalogue.catalog_products (
  -- PK
  id text PRIMARY KEY,
  -- unique
  gtin text,
  brand text,
  title_i18n jsonb,
  -- FK
  category_id text,
  attributes jsonb,
  -- media
  image_set text[],
  -- can lock content
  brand_owner_merchant_id text,
  locked boolean
);
-- TODO indexes/constraints: unique(gtin) · GIN(attributes)
-- outbox events: product.created
-- search projection: listings (title, attrs, images)

-- A merchant's sellable offer on a product (own or shared).
CREATE TABLE catalogue.offers (
  -- PK
  id text PRIMARY KEY,
  -- FK
  product_id text,
  merchant_id text,
  sku text,
  price_cents bigint,
  compare_at_cents bigint,
  -- private
  cost_cents bigint,
  stock integer,
  low_stock_at integer,
  condition text,
  -- pooled | install | pickup
  fulfilment text[],
  -- draft | pending | approved | rejected
  vetting text CHECK (vetting IN ('draft', 'pending', 'approved', 'rejected')),
  -- live | hidden
  status text CHECK (status IN ('live', 'hidden'))
);
-- TODO indexes/constraints: unique(merchant_id,sku) · index(product_id)
-- outbox events: listing.created · listing.updated · listing.stock_changed
-- search projection: listings (price, stock, merchant, zones)

-- Size/colour variants under an offer.
CREATE TABLE catalogue.variants (
  -- PK
  id text PRIMARY KEY,
  -- FK
  offer_id text,
  sku text,
  gtin text,
  -- {size, colour}
  attrs jsonb,
  price_cents bigint,
  stock integer,
  image_set text[]
);
-- TODO indexes/constraints: unique(offer_id,sku)
-- outbox events: listing.updated
-- search projection: listings.variants

-- Bookable services with pricing mode, duration, buffer.
CREATE TABLE catalogue.services (
  -- PK
  id text PRIMARY KEY,
  merchant_id text,
  -- FK
  category_id text,
  name_i18n jsonb,
  desc_i18n jsonb,
  -- fixed | quote | hourly
  pricing_mode text CHECK (pricing_mode IN ('fixed', 'quote', 'hourly')),
  price_cents bigint,
  duration_min integer,
  buffer_min integer,
  instant_book boolean,
  vetting text,
  status text
);
-- TODO indexes/constraints: index(merchant_id) · index(category_id)
-- outbox events: listing.created · listing.updated
-- search projection: listings (service docs)

-- Images/documents with perceptual hash for duplicate detection.
CREATE TABLE catalogue.media (
  -- PK
  id text PRIMARY KEY,
  owner_type text,
  owner_id text,
  -- S3 key
  url text,
  -- dupe index
  phash bytea,
  exif_ok boolean,
  -- main | gallery | proof | document
  kind text CHECK (kind IN ('main', 'gallery', 'proof', 'document'))
);
-- TODO indexes/constraints: index(phash) · index(owner_type,owner_id)
-- outbox events: media.flagged_duplicate

-- Foreign keys (in-module only)
ALTER TABLE catalogue.categories ADD CONSTRAINT fk_categories_parent_id FOREIGN KEY (parent_id) REFERENCES catalogue.categories(id);
ALTER TABLE catalogue.catalog_products ADD CONSTRAINT fk_catalog_products_category_id FOREIGN KEY (category_id) REFERENCES catalogue.categories(id);
ALTER TABLE catalogue.offers ADD CONSTRAINT fk_offers_product_id FOREIGN KEY (product_id) REFERENCES catalogue.catalog_products(id);
-- logical ref (cross-module, no FK): catalogue.offers.merchant_id → merchants.merchants.id
ALTER TABLE catalogue.variants ADD CONSTRAINT fk_variants_offer_id FOREIGN KEY (offer_id) REFERENCES catalogue.offers(id);
ALTER TABLE catalogue.services ADD CONSTRAINT fk_services_category_id FOREIGN KEY (category_id) REFERENCES catalogue.categories(id);
-- logical ref (cross-module, no FK): catalogue.services.merchant_id → merchants.merchants.id
