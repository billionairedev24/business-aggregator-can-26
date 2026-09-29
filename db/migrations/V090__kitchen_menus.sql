-- Kitchen workstream (range V090–V099): menu builder, modifier groups, combos. Additive only.
-- See docs/DECISIONS.md "Kitchen".

-- Menus: a plain display name (V006 only has name_i18n, which the search projection keeps reading), order in the menu
-- picker, and audit columns. schedule jsonb: {"mode":"open_hours"} | {"mode":"window","days":[2,3,4,5],"from":"11:00",
-- "to":"14:00"} | {"mode":"quote","noticeHours":48}.
ALTER TABLE food.menus
  ADD COLUMN name text,
  ADD COLUMN sort integer NOT NULL DEFAULT 0,
  ADD COLUMN published_at timestamptz,
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
  ADD CONSTRAINT chk_menus_name CHECK (name IS NULL OR char_length(btrim(name)) BETWEEN 1 AND 60);
CREATE INDEX ix_menus_merchant ON food.menus(merchant_id, sort);

ALTER TABLE food.menu_sections
  ADD COLUMN name text,
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD CONSTRAINT chk_menu_sections_name CHECK (name IS NULL OR char_length(btrim(name)) BETWEEN 1 AND 60);
CREATE INDEX ix_menu_sections_menu ON food.menu_sections(menu_id, sort);

-- Menu items.
--   status        draft | published — the kitchen's intent ("Save as draft" / "Save & publish"). Customers see an item
--                 only when it is published, vetting = approved (kitchen approved + allergen audit + photo), the menu is
--                 live and it is not sold out.
--   allergens     NULL = not declared yet (fails the audit); '{}' = declared "none". Codes of the Health Canada list.
--   availability  always | lunch | after_5 | weekends (the editor's "Availability" options).
--   sold_out_on   "Sold out today": the Edmonton date it was switched off; it comes back the next day.
ALTER TABLE food.menu_items
  ADD COLUMN name text,
  ADD COLUMN description text,
  ADD COLUMN sort integer NOT NULL DEFAULT 0,
  ADD COLUMN status text NOT NULL DEFAULT 'draft',
  ADD COLUMN availability text NOT NULL DEFAULT 'always',
  ADD COLUMN combo_eligible boolean NOT NULL DEFAULT true,
  ADD COLUMN sold_out_on date,
  ADD COLUMN photo_key text,
  ADD COLUMN photo_content_type text,
  ADD COLUMN published_at timestamptz,
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
  ADD CONSTRAINT chk_menu_items_status CHECK (status IN ('draft', 'published')),
  ADD CONSTRAINT chk_menu_items_availability CHECK (availability IN ('always', 'lunch', 'after_5', 'weekends')),
  ADD CONSTRAINT chk_menu_items_vetting CHECK (vetting IS NULL OR vetting IN ('draft', 'pending', 'approved', 'rejected')),
  ADD CONSTRAINT chk_menu_items_name CHECK (name IS NULL OR char_length(btrim(name)) BETWEEN 1 AND 80),
  ADD CONSTRAINT chk_menu_items_description CHECK (description IS NULL OR char_length(description) <= 500),
  ADD CONSTRAINT chk_menu_items_price CHECK (price_cents IS NULL OR price_cents > 0),
  ADD CONSTRAINT chk_menu_items_prep CHECK (prep_add_min IS NULL OR prep_add_min IN (0, 5, 10)),
  ADD CONSTRAINT chk_menu_items_limit CHECK (daily_limit IS NULL OR daily_limit BETWEEN 1 AND 999),
  ADD CONSTRAINT chk_menu_items_allergens CHECK (allergens IS NULL OR allergens <@ ARRAY['eggs', 'milk', 'peanuts',
      'tree_nuts', 'sesame', 'soy', 'wheat', 'fish', 'shellfish', 'mustard', 'sulphites']::text[]),
  -- a published item always carries its allergen declaration
  ADD CONSTRAINT chk_menu_items_published_allergens CHECK (status <> 'published' OR allergens IS NOT NULL);
CREATE INDEX ix_menu_items_merchant ON food.menu_items(merchant_id, available);
CREATE INDEX ix_menu_items_section ON food.menu_items(section_id, sort);

-- Modifier groups: display name, the rule the owner picked (exactly / at least / up to N) and nesting ("show only for
-- certain sizes" = only when one of these options of another group is chosen). min_select / max_select stay the
-- machine-readable form of the rule.
ALTER TABLE food.modifier_groups
  ADD COLUMN name text,
  ADD COLUMN pick_rule text NOT NULL DEFAULT 'exactly',
  ADD COLUMN pick_count integer NOT NULL DEFAULT 1,
  ADD COLUMN show_for_option_ids text[] NOT NULL DEFAULT '{}',
  ADD COLUMN sort integer NOT NULL DEFAULT 0,
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD CONSTRAINT chk_modifier_groups_rule CHECK (pick_rule IN ('exactly', 'at_least', 'up_to')),
  ADD CONSTRAINT chk_modifier_groups_count CHECK (pick_count BETWEEN 1 AND 20),
  ADD CONSTRAINT chk_modifier_groups_name CHECK (name IS NULL OR char_length(btrim(name)) BETWEEN 1 AND 40);
CREATE INDEX ix_modifier_groups_merchant ON food.modifier_groups(merchant_id, sort);

ALTER TABLE food.modifier_options
  ADD COLUMN name text,
  ADD COLUMN sort integer NOT NULL DEFAULT 0,
  ADD CONSTRAINT chk_modifier_options_name CHECK (name IS NULL OR char_length(btrim(name)) BETWEEN 1 AND 40),
  ADD CONSTRAINT chk_modifier_options_delta CHECK (price_delta_cents IS NULL OR price_delta_cents BETWEEN 0 AND 10000);
CREATE INDEX ix_modifier_options_group ON food.modifier_options(group_id, sort);
CREATE INDEX ix_item_modifiers_group ON food.item_modifiers(group_id);

-- Combos: display name, fixed price or % off (discount_bps), a free status the owner sets, swaps allowed.
-- rules jsonb: {"slots":[{"label":"Any 2 mains","qty":2,"sectionId":"…"|null,"itemIds":["…"]}]}
-- schedule jsonb: null (always) | {"days":[1..7],"from":"11:00","to":"14:00"}
ALTER TABLE food.combos
  ADD COLUMN name text,
  ADD COLUMN pricing text NOT NULL DEFAULT 'fixed',
  ADD COLUMN swaps_allowed boolean NOT NULL DEFAULT false,
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
  ADD CONSTRAINT chk_combos_status CHECK (status IS NULL OR status IN ('draft', 'live', 'scheduled', 'paused')),
  ADD CONSTRAINT chk_combos_pricing CHECK (pricing IN ('fixed', 'percent_off')),
  ADD CONSTRAINT chk_combos_price CHECK ((pricing = 'fixed' AND price_cents > 0)
      OR (pricing = 'percent_off' AND discount_bps BETWEEN 100 AND 9000)),
  ADD CONSTRAINT chk_combos_name CHECK (name IS NULL OR char_length(btrim(name)) BETWEEN 1 AND 60);
CREATE INDEX ix_combos_merchant ON food.combos(merchant_id);

-- Northline-funded promos the kitchen opts into (Modifiers & combos → "Northline-funded promos").
CREATE TABLE food.kitchen_promos (
  merchant_id text NOT NULL,
  promo text NOT NULL CHECK (promo IN ('points_3x', 'first_order_5')),
  enabled boolean NOT NULL,
  updated_by text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (merchant_id, promo)
);
