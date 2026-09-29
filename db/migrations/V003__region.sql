-- schema: region · owner: Java (java)
-- Generated from the Northline spec (docs/DATA_MODEL.md). Hand-edit freely; keep column names.
CREATE SCHEMA IF NOT EXISTS region;

-- A province or sub-market with its rollout stage.
CREATE TABLE region.regions (
  -- PK
  id text PRIMARY KEY,
  -- AB, BC…
  province char(2),
  name_i18n jsonb,
  -- off | waitlist | pilot | live
  stage text CHECK (stage IN ('off', 'waitlist', 'pilot', 'live')),
  -- FK
  tax_profile_id text,
  -- en, fr
  languages text[],
  -- own | contracted | hybrid
  courier_model text CHECK (courier_model IN ('own', 'contracted', 'hybrid'))
);
-- TODO indexes/constraints: unique(province)
-- outbox events: region.activated · region.stage_changed

-- GST/PST/HST/QST rates by effective date.
CREATE TABLE region.tax_profiles (
  -- PK
  id text PRIMARY KEY,
  gst numeric,
  pst numeric,
  hst numeric,
  qst numeric,
  effective_from date
);

-- Delivery zones (PostGIS polygons) with pooled-run pricing.
CREATE TABLE region.zones (
  -- PK
  id text PRIMARY KEY,
  -- FK
  region_id text,
  name text,
  polygon geography(Polygon),
  runs_per_day integer,
  fee_std_cents bigint,
  fee_plus_cents bigint,
  min_basket_cents bigint
);
-- TODO indexes/constraints: GiST(polygon)
-- outbox events: zone.pricing_changed
-- search projection: listings.zone_ids (geo filter)

-- Capabilities scoped by region/city (mirrors Unleash).
CREATE TABLE region.feature_flags (
  -- PK part
  key text,
  -- PK part
  region_id text,
  enabled boolean,
  variant jsonb,
  PRIMARY KEY (key,region_id)
);
-- TODO indexes/constraints: PK(key,region_id)
-- outbox events: flag.changed

-- Foreign keys (in-module only)
ALTER TABLE region.regions ADD CONSTRAINT fk_regions_tax_profile_id FOREIGN KEY (tax_profile_id) REFERENCES region.tax_profiles(id);
ALTER TABLE region.zones ADD CONSTRAINT fk_zones_region_id FOREIGN KEY (region_id) REFERENCES region.regions(id);
ALTER TABLE region.feature_flags ADD CONSTRAINT fk_feature_flags_region_id FOREIGN KEY (region_id) REFERENCES region.regions(id);
