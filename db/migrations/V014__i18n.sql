-- schema: i18n · owner: Java (java)
-- Generated from the Northline spec (docs/DATA_MODEL.md). Hand-edit freely; keep column names.
CREATE SCHEMA IF NOT EXISTS i18n;

-- UI strings: key → locale → text (ICU MessageFormat).
CREATE TABLE i18n.translations (
  -- PK part
  key text,
  -- PK part
  locale text,
  text text,
  -- human | mt
  source text CHECK (source IN ('human', 'mt')),
  approved_by text,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (key,locale)
);
-- TODO indexes/constraints: PK(key,locale) · published as CDN bundles
-- outbox events: i18n.bundle_published
-- search projection: per-language analyzers

-- Seller-authored text in other locales (MT draft → approved).
CREATE TABLE i18n.content_translations (
  -- PK part
  owner_type text,
  -- PK part
  owner_id text,
  -- PK part
  field text,
  -- PK part
  locale text,
  text text,
  source text,
  approved boolean,
  PRIMARY KEY (owner_type,owner_id,field,locale)
);
-- TODO indexes/constraints: PK(owner_type,owner_id,field,locale)
-- outbox events: listing.updated
-- search projection: listings.*_fr

-- Search synonyms per language (fr↔en, colloquial).
CREATE TABLE i18n.synonyms (
  -- PK
  id text PRIMARY KEY,
  locale text,
  terms text[],
  category_id text
);
-- outbox events: search.synonyms_changed
-- search projection: analyzer synonym filter

-- Foreign keys (in-module only)
-- logical ref (cross-module, no FK): i18n.content_translations.owner_id → catalogue.offers.id
-- logical ref (cross-module, no FK): i18n.content_translations.owner_id → food.menu_items.id
