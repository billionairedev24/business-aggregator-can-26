-- S-94 platform console taxonomy (console batch 2 range V230–V239, docs/IMPLEMENTATION_PLAN.md). Additive only.

-- How many categories a business of each type may hold, edited in the console. V016's constraint trigger
-- (trg_category_limit on merchants.merchant_categories) keeps enforcing it: only the function it calls changes, to read
-- the limit here. A type without a row keeps V016's number.
CREATE TABLE merchants.category_limits (
  merchant_type  text        PRIMARY KEY CHECK (merchant_type IN ('provider', 'seller', 'both', 'kitchen')),
  max_categories integer     NOT NULL CHECK (max_categories BETWEEN 1 AND 50),
  updated_by     text        NOT NULL,
  updated_at     timestamptz NOT NULL
);
INSERT INTO merchants.category_limits (merchant_type, max_categories, updated_by, updated_at) VALUES
  ('provider', 10, 'system', now()), ('seller', 5, 'system', now()), ('both', 10, 'system', now()), ('kitchen', 3, 'system', now())
ON CONFLICT (merchant_type) DO NOTHING;

CREATE OR REPLACE FUNCTION merchants.category_limit_check() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE lim int; mtype text; cnt int;
BEGIN
  SELECT type INTO mtype FROM merchants.merchants WHERE id = NEW.merchant_id;
  SELECT max_categories INTO lim FROM merchants.category_limits WHERE merchant_type = mtype;
  IF lim IS NULL THEN
    lim := CASE mtype WHEN 'provider' THEN 10 WHEN 'seller' THEN 5 WHEN 'both' THEN 10 WHEN 'kitchen' THEN 3 ELSE 10 END;
  END IF;
  SELECT count(*) INTO cnt FROM merchants.merchant_categories WHERE merchant_id = NEW.merchant_id AND status <> 'rejected';
  IF cnt > lim THEN
    RAISE EXCEPTION 'merchant % may select at most % categories', NEW.merchant_id, lim USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
