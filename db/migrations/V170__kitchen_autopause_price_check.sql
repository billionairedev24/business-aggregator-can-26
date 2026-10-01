-- S-67: enforce two kitchen settings that were stored but not acted on.
--
-- Auto-pause on late orders: the Studio's "Auto-pause if late orders ≥ N" (food.kitchen_settings.auto_pause_late).
--   auto_paused_at  when the kitchen was auto-paused (N or more accepted orders past their ready-by time); cleared when
--                   it catches up. Customers are refused at read time either way (KitchenCalendar); this column marks the
--                   transition so kitchen.auto_paused / kitchen.auto_resumed are published once each.
ALTER TABLE food.kitchen_settings
  ADD COLUMN auto_paused_at timestamptz;

-- Menu price vetting: a published dish priced more than 40 % above or below the median of comparable live dishes
-- (kitchens of the same cuisine in the same market) waits for the owner to confirm its price before it goes live.
--   price_median_cents     the comparable median when the price was last checked (NULL = too few dishes to compare)
--   price_confirmed_cents  the price the owner confirmed despite the check (a new price is checked again)
ALTER TABLE food.menu_items
  ADD COLUMN price_median_cents bigint,
  ADD COLUMN price_confirmed_cents bigint;
