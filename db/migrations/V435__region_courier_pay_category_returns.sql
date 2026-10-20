-- S-151 (range V432–V441): two pieces of configuration. See docs/DECISIONS.md "S-151".

-- The couriers' pay model of a province or market (a market's own wins over its province's; neither = the platform
-- defaults, northline.fulfilment.courier-pay.*): {"perRunCents":…, "perPickupCents":…, "perDropoffCents":…,
-- "perFailedCents":…, "perCollectCents":…, "perReturnCents":…}; any key left out takes the next level's value.
ALTER TABLE region.regions ADD COLUMN courier_pay jsonb CHECK (courier_pay IS NULL OR jsonb_typeof(courier_pay) = 'object');

-- How long after delivery a category's goods can be returned, in days (0 = not returnable). Looked up for the leaf,
-- then its group; neither = the platform default (northline.orders.returns.default-days). Logical refs to
-- catalogue.categories ids (no FK: the categories are seeded after migrations in a new database).
CREATE TABLE catalogue.category_return_windows (
  category_id text PRIMARY KEY,
  days        integer NOT NULL CHECK (days BETWEEN 0 AND 365),
  updated_at  timestamptz NOT NULL DEFAULT now()
);
-- perishables and restricted goods aren't returnable (a damaged or wrong item is still a refund case, S-60)
INSERT INTO catalogue.category_return_windows (category_id, days) VALUES
  ('shop.food-and-grocery', 0),
  ('shop.restricted', 0)
ON CONFLICT (category_id) DO NOTHING;
