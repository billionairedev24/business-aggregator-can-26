-- schema: food · owner: Java (java)
-- Generated from the Northline spec (docs/DATA_MODEL.md). Hand-edit freely; keep column names.
CREATE SCHEMA IF NOT EXISTS food;

-- A kitchen's menus with schedules (dinner, lunch, catering).
CREATE TABLE food.menus (
  -- PK
  id text PRIMARY KEY,
  merchant_id text,
  name_i18n jsonb,
  -- weekday windows
  schedule jsonb,
  -- draft | live | hidden
  status text CHECK (status IN ('draft', 'live', 'hidden'))
);
-- TODO indexes/constraints: index(merchant_id)
-- outbox events: menu.published

-- Ordered sections in a menu.
CREATE TABLE food.menu_sections (
  -- PK
  id text PRIMARY KEY,
  -- FK
  menu_id text,
  name_i18n jsonb,
  sort integer
);

-- Dishes with allergens, dietary tags, prep, limits, availability.
CREATE TABLE food.menu_items (
  -- PK
  id text PRIMARY KEY,
  -- FK
  section_id text,
  merchant_id text,
  name_i18n jsonb,
  desc_i18n jsonb,
  price_cents bigint,
  -- Health Canada 11
  allergens text[],
  dietary text[],
  prep_add_min integer,
  daily_limit integer,
  sold_today integer,
  available boolean,
  vetting text
);
-- TODO indexes/constraints: index(merchant_id,available)
-- outbox events: food.item_availability · listing.created
-- search projection: listings (food docs: allergens, dietary, eta)

-- Reusable option groups (size, spice, extras) with rules.
CREATE TABLE food.modifier_groups (
  -- PK
  id text PRIMARY KEY,
  merchant_id text,
  name_i18n jsonb,
  min_select integer,
  max_select integer,
  required boolean
);

-- Options with price deltas.
CREATE TABLE food.modifier_options (
  -- PK
  id text PRIMARY KEY,
  -- FK
  group_id text,
  name_i18n jsonb,
  price_delta_cents bigint,
  is_default boolean,
  sold_out boolean
);

-- M:N items ↔ groups.
CREATE TABLE food.item_modifiers (
  item_id text,
  group_id text,
  sort integer,
  PRIMARY KEY (item_id,group_id)
);
-- TODO indexes/constraints: PK(item_id,group_id)

-- Bundles with slot rules and a price or discount.
CREATE TABLE food.combos (
  -- PK
  id text PRIMARY KEY,
  merchant_id text,
  name_i18n jsonb,
  -- slots: any 2 mains…
  rules jsonb,
  price_cents bigint,
  discount_bps integer,
  schedule jsonb,
  status text
);
-- outbox events: listing.created
-- search projection: listings

-- Prep time, throttles, fulfilment modes, pause state.
CREATE TABLE food.kitchen_settings (
  -- PK
  merchant_id text,
  default_prep_min integer,
  max_orders_per_15 integer,
  paused_until timestamptz,
  fulfilment text[],
  radius_km numeric,
  group_orders boolean
);
-- outbox events: kitchen.paused
-- search projection: listings.open_now / eta

-- Foreign keys (in-module only)
-- logical ref (cross-module, no FK): food.menus.merchant_id → merchants.merchants.id
ALTER TABLE food.menu_sections ADD CONSTRAINT fk_menu_sections_menu_id FOREIGN KEY (menu_id) REFERENCES food.menus(id);
ALTER TABLE food.menu_items ADD CONSTRAINT fk_menu_items_section_id FOREIGN KEY (section_id) REFERENCES food.menu_sections(id);
ALTER TABLE food.modifier_options ADD CONSTRAINT fk_modifier_options_group_id FOREIGN KEY (group_id) REFERENCES food.modifier_groups(id);
ALTER TABLE food.item_modifiers ADD CONSTRAINT fk_item_modifiers_item_id FOREIGN KEY (item_id) REFERENCES food.menu_items(id);
ALTER TABLE food.item_modifiers ADD CONSTRAINT fk_item_modifiers_group_id FOREIGN KEY (group_id) REFERENCES food.modifier_groups(id);
-- logical ref (cross-module, no FK): food.combos.merchant_id → merchants.merchants.id
-- logical ref (cross-module, no FK): food.kitchen_settings.merchant_id → merchants.merchants.id
