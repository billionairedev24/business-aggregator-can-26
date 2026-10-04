-- Age-restricted purchases (owner decision 2026-10-04). Additive only. See docs/DECISIONS.md and
-- docs/runbooks/age-restricted.md.
--
-- A taxonomy category carries an age-restriction class; its leaves inherit a group's class, products inherit their
-- category's, and nothing a seller sends can lower it (the class is never stored on a listing). Kept beside the
-- categories, like catalogue.category_labels (V111): categories are seeded from db/seed/categories.json after the
-- migrations run, so a column set here would find no rows on a fresh database. No FK for the same reason.
CREATE TABLE catalogue.category_age_classes (
  category_id text PRIMARY KEY,                    -- catalogue.categories.id (a leaf or a group; logical reference)
  age_class   text NOT NULL CHECK (age_class IN ('alcohol', 'tobacco', 'cannabis')),
  updated_by  text,                                -- identity.users id of the staff member (console), null = migration
  updated_at  timestamptz NOT NULL DEFAULT now()
);

INSERT INTO catalogue.category_age_classes (category_id, age_class) VALUES
  ('shop.restricted.alcohol', 'alcohol'),
  ('shop.restricted.tobacco-and-vape', 'tobacco'),
  ('shop.restricted.cannabis-accessories', 'cannabis'),
  ('food.service.alcohol-with-food', 'alcohol');

-- The class a category carries: its own row, else its group's (one level: leaves sit under groups).
CREATE FUNCTION catalogue.age_class_of(category text) RETURNS text
  LANGUAGE sql STABLE AS $$
    select coalesce(
      (select a.age_class from catalogue.category_age_classes a where a.category_id = category),
      (select a.age_class from catalogue.categories c
         join catalogue.category_age_classes a on a.category_id = c.parent_id
        where c.id = category))
  $$;

-- An approved listing in a restricted category whose business holds no approved, unexpired licence for the class in
-- its province is hidden by the platform (status 'hidden') and marked here, so approving or renewing the licence puts
-- back exactly what the platform hid — never a listing the seller hid themselves.
ALTER TABLE catalogue.offers ADD COLUMN licence_hold boolean NOT NULL DEFAULT false;
CREATE INDEX ix_offers_licence_hold ON catalogue.offers (merchant_id) WHERE licence_hold;
