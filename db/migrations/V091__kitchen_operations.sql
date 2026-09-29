-- Kitchen workstream (range V090–V099): hours, prep & capacity, live orders (KDS). Additive only.
-- See docs/DECISIONS.md "Kitchen".

-- One settings row per kitchen (V006 left merchant_id without a key). The option sets are the design's selects.
--   prep_bump_min      "Busy · +5 min" on Live orders (0–30, steps of 5), added to default_prep_min for customers.
--   large_order_*      "Large-order threshold": $120 → +15 min | $200 → +20 min.
--   auto_pause_late    "Auto-pause if late orders ≥" 3 | 5 | NULL = never.
--   fulfilment         codes: courier (Northline direct courier, hot) | pickup | meal_kits (pooled run) | scheduled
CREATE UNIQUE INDEX ux_kitchen_settings_merchant ON food.kitchen_settings(merchant_id);
ALTER TABLE food.kitchen_settings
  ALTER COLUMN merchant_id SET NOT NULL,
  ADD COLUMN prep_bump_min integer NOT NULL DEFAULT 0,
  ADD COLUMN large_order_cents bigint NOT NULL DEFAULT 12000,
  ADD COLUMN large_order_add_min integer NOT NULL DEFAULT 15,
  ADD COLUMN auto_pause_late integer DEFAULT 3,
  ADD COLUMN group_max integer NOT NULL DEFAULT 12,
  ADD COLUMN scheduled_days integer NOT NULL DEFAULT 7,
  ADD COLUMN delivery_areas text[] NOT NULL DEFAULT '{}',
  ADD COLUMN paused_by text,
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
  ADD CONSTRAINT chk_kitchen_prep CHECK (default_prep_min IN (20, 25, 30, 40)),
  ADD CONSTRAINT chk_kitchen_throttle CHECK (max_orders_per_15 IN (4, 6, 8, 12)),
  ADD CONSTRAINT chk_kitchen_bump CHECK (prep_bump_min BETWEEN 0 AND 30 AND prep_bump_min % 5 = 0),
  ADD CONSTRAINT chk_kitchen_large CHECK ((large_order_cents, large_order_add_min) IN ((12000, 15), (20000, 20))),
  ADD CONSTRAINT chk_kitchen_auto_pause CHECK (auto_pause_late IS NULL OR auto_pause_late IN (3, 5)),
  ADD CONSTRAINT chk_kitchen_fulfilment CHECK (fulfilment <@ ARRAY['courier', 'pickup', 'meal_kits', 'scheduled']::text[]),
  ADD CONSTRAINT chk_kitchen_radius CHECK (radius_km IS NULL OR radius_km BETWEEN 1 AND 25),
  ADD CONSTRAINT chk_kitchen_group_max CHECK (group_max BETWEEN 2 AND 50),
  ADD CONSTRAINT chk_kitchen_scheduled_days CHECK (scheduled_days BETWEEN 1 AND 14);

-- Opening hours (delivery & pickup) per ISO weekday (Mon = 1). ranges = [["11:00","21:00"], …]; [] = closed.
CREATE TABLE food.opening_hours (
  merchant_id text NOT NULL,
  weekday integer NOT NULL CHECK (weekday BETWEEN 1 AND 7),
  ranges jsonb NOT NULL DEFAULT '[]',
  note text CHECK (note IS NULL OR char_length(note) <= 80),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (merchant_id, weekday)
);

-- Holiday hours: a date with its own ranges ([] = closed all day).
CREATE TABLE food.holiday_hours (
  id text PRIMARY KEY,
  merchant_id text NOT NULL,
  day date NOT NULL,
  ranges jsonb NOT NULL DEFAULT '[]',
  note text CHECK (note IS NULL OR char_length(note) <= 80),
  created_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (merchant_id, day)
);

-- The kitchen's ticket for a food order (KDS): New → Cooking → Ready → handed off. The order itself stays in the
-- orders module; orders listens to the kitchen events and moves orders.orders.state (accepted / ready / picked_up |
-- delivered). No row = New.
CREATE TABLE food.kitchen_tickets (
  order_id text PRIMARY KEY,
  merchant_id text NOT NULL,
  stage text NOT NULL CHECK (stage IN ('new', 'cooking', 'ready', 'handed_off')),
  prep_min integer,
  accepted_at timestamptz,
  accepted_by text,
  ready_by timestamptz,
  ready_at timestamptz,
  handed_off_at timestamptz,
  handed_off_by text,
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (stage = 'new' OR (accepted_at IS NOT NULL AND ready_by IS NOT NULL)),
  CHECK (stage NOT IN ('ready', 'handed_off') OR ready_at IS NOT NULL),
  CHECK (stage <> 'handed_off' OR handed_off_at IS NOT NULL)
);
CREATE INDEX ix_kitchen_tickets_merchant_stage ON food.kitchen_tickets(merchant_id, stage);

-- Food orders: how the customer gets it (V009 has no column for it) and, for pickup, when the customer said they'd
-- arrive (consumer app). Written by checkout / the consumer app; read by the kitchen display.
ALTER TABLE orders.orders
  ADD COLUMN fulfilment_mode text CHECK (fulfilment_mode IS NULL OR fulfilment_mode IN ('delivery', 'pickup')),
  ADD COLUMN customer_eta timestamptz;
CREATE INDEX ix_orders_food_open ON orders.orders(type, state) WHERE type = 'food';
CREATE INDEX ix_fulfilment_stops_order ON fulfilment.stops(order_id);
