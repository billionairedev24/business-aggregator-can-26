-- Search workstream (range V120–V129): what the Elasticsearch read model (S-42/S-43) needs from Postgres, which stays
-- the source of truth. Additive only. See docs/DECISIONS.md "S-43".

-- Where a business is — its shop, kitchen or base — and how far it travels or delivers: the `location` geo_point and
-- `serviceRadiusKm` of every search document (distance sort and filter, "comes to you"). Onboarding keeps addresses as
-- text and nothing geocodes them yet, so rows come from the console / by hand (source 'manual') or the dev seed until a
-- geocoder port exists ('geocoder'). A kitchen without a radius here uses food.kitchen_settings.radius_km.
CREATE TABLE merchants.locations (
  merchant_id text PRIMARY KEY REFERENCES merchants.merchants(id),
  geom geography(Point, 4326) NOT NULL,
  service_radius_km numeric(5,1) CHECK (service_radius_km IS NULL OR service_radius_km BETWEEN 0 AND 500),
  source text NOT NULL CHECK (source IN ('manual', 'geocoder', 'seed')),
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- The search module's own schema (ARCHITECTURE.md: search is a projection only). Written by the worker alone.
CREATE SCHEMA IF NOT EXISTS search;

-- Watermarks of the worker's background jobs: `reconcile` = the last sweep of changed rows (S-43) — edits that raise
-- no event (content, prices, hours, reviews) reach the indices through it.
CREATE TABLE search.sync_state (
  name text PRIMARY KEY,
  watermark timestamptz NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- The sweep asks "which merchants changed since …" of these tables.
CREATE INDEX IF NOT EXISTS ix_services_updated ON catalogue.services (updated_at);
CREATE INDEX IF NOT EXISTS ix_offers_updated ON catalogue.offers (updated_at);
CREATE INDEX IF NOT EXISTS ix_catalog_products_updated ON catalogue.catalog_products (updated_at);
CREATE INDEX IF NOT EXISTS ix_menu_items_updated ON food.menu_items (updated_at);
CREATE INDEX IF NOT EXISTS ix_merchants_updated ON merchants.merchants (updated_at);
CREATE INDEX IF NOT EXISTS ix_reviews_created ON trust.reviews (created_at);
