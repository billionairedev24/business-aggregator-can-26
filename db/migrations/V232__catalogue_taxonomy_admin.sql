-- S-94 platform console taxonomy (console batch 2 range V230–V239, docs/IMPLEMENTATION_PLAN.md). Additive only.

-- A category staff edited in the console. The dev seeder (seedCategories, db/seed/categories.json) leaves such rows
-- alone from then on, so a re-seed never undoes a console edit.
ALTER TABLE catalogue.categories ADD COLUMN IF NOT EXISTS edited_at timestamptz;
ALTER TABLE catalogue.categories ADD COLUMN IF NOT EXISTS edited_by text;

-- The licensing bodies the console knows, each in one province (the region model's code). Staff add them; none are
-- seeded, since which body licenses what is per province and not something code may assume.
CREATE TABLE catalogue.regulators (
  code       text        PRIMARY KEY CHECK (code ~ '^[a-z0-9][a-z0-9_-]{1,39}$'),
  name       text        NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
  province   text        NOT NULL CHECK (province ~ '^[A-Z]{2}$'),
  website    text        CHECK (website IS NULL OR website ~ '^https://'),
  updated_by text        NOT NULL,
  updated_at timestamptz NOT NULL
);
CREATE INDEX ix_regulators_province ON catalogue.regulators(province);

-- A category's regulator in one province. regulator null = staff said "not regulated in this province". A province
-- without a row falls back to the category's default licence registry (catalogue.categories.regulated_registry).
CREATE TABLE catalogue.category_regulators (
  category_id text        NOT NULL REFERENCES catalogue.categories(id),
  province    text        NOT NULL CHECK (province ~ '^[A-Z]{2}$'),
  regulator   text        REFERENCES catalogue.regulators(code),
  updated_by  text        NOT NULL,
  updated_at  timestamptz NOT NULL,
  PRIMARY KEY (category_id, province)
);
CREATE INDEX ix_category_regulators_regulator ON catalogue.category_regulators(regulator);
