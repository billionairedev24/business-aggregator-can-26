-- S-159 (range V472–V481): group food orders on the baseline's orders.group_orders (V009: host, kitchen, link code,
-- members, locks_at; V181: unique link code) — additive only, no column renamed or dropped. See docs/DECISIONS.md
-- "S-159".

-- The group's life: open (guests add dishes) → locked (the host closed the cart; shares priced when each person pays)
-- → paying (the host's card payment is open, or shares are being paid) → placed (one kitchen ticket: `order_id` is the
-- orders.orders / food_checkouts id). `cancelled` by the host before it is placed, `expired` when nobody finished it.
ALTER TABLE orders.group_orders
  ADD COLUMN state text NOT NULL DEFAULT 'open'
    CHECK (state IN ('open', 'locked', 'paying', 'placed', 'cancelled', 'expired')),
  -- host = the host pays for everyone; split = each person pays their own share (the host may pay someone's)
  ADD COLUMN payment text NOT NULL DEFAULT 'host' CHECK (payment IN ('host', 'split')),
  ADD COLUMN kitchen_name text,                      -- display snapshot when started
  ADD COLUMN kitchen_slug text,
  ADD COLUMN group_max int NOT NULL DEFAULT 12 CHECK (group_max BETWEEN 2 AND 50), -- the kitchen's limit then
  ADD COLUMN fulfilment_mode text CHECK (fulfilment_mode IN ('delivery', 'pickup')),
  ADD COLUMN scheduled_for timestamptz,
  ADD COLUMN delivery jsonb,                         -- the host's address snapshot (FoodCheckout.Delivery), null for pickup
  ADD COLUMN tip_kind text CHECK (tip_kind IN ('none', 'amount', 'percent')),
  ADD COLUMN tip_value bigint NOT NULL DEFAULT 0 CHECK (tip_value >= 0),
  ADD COLUMN order_id text,                          -- logical ref → orders.food_checkouts.id / orders.orders.id
  ADD COLUMN order_ref text,
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN placed_at timestamptz,
  ADD COLUMN closed_at timestamptz;                  -- cancelled or expired
CREATE UNIQUE INDEX ux_group_orders_order ON orders.group_orders (order_id) WHERE order_id IS NOT NULL;
CREATE INDEX ix_group_orders_open ON orders.group_orders (created_at) WHERE state IN ('open', 'locked', 'paying');
CREATE INDEX ix_group_orders_members ON orders.group_orders USING gin (member_user_ids);

-- The dev seed's group (V108) is already a placed order on the kitchen display.
UPDATE orders.group_orders g SET state = 'placed', placed_at = o.placed_at, order_id = o.id, order_ref = o.ref
  FROM orders.orders o WHERE o.group_order_id = g.id;

-- What each person put in the shared cart, priced from the live menu when added (title, unit price and the choices'
-- names are a display snapshot; the order is priced again when it is paid).
CREATE TABLE orders.group_order_items (
  id             text PRIMARY KEY,
  group_order_id text NOT NULL REFERENCES orders.group_orders (id) ON DELETE CASCADE,
  user_id        text NOT NULL,                      -- logical ref → identity.users.id
  menu_item_id   text,                               -- logical ref → food.menu_items.id
  combo_id       text,                               -- logical ref → food.combos.id
  qty            int NOT NULL CHECK (qty BETWEEN 1 AND 20),
  option_ids     text[] NOT NULL DEFAULT '{}',
  combo_item_ids text[] NOT NULL DEFAULT '{}',
  note           text CHECK (note IS NULL OR length(note) <= 140),
  title          text NOT NULL,
  unit_cents     bigint NOT NULL CHECK (unit_cents >= 0),
  total_cents    bigint NOT NULL CHECK (total_cents >= 0),
  choices        jsonb NOT NULL DEFAULT '[]',
  age_class      text,
  added_at       timestamptz NOT NULL,
  CHECK ((menu_item_id IS NULL) <> (combo_id IS NULL))
);
CREATE INDEX ix_group_order_items_group ON orders.group_order_items (group_order_id, added_at);
CREATE INDEX ix_group_order_items_user ON orders.group_order_items (user_id);

-- Split payment: one share per person with dishes, priced when the host locks the cart. Each share is its own
-- manual-capture card payment and escrow hold (payments ref_type `food_order`, ref_id = this id; the host's share id is
-- the order's id, so the kitchen's hand-off releases it as any food order's, and the orders module releases the
-- others). The host's share carries the tip.
CREATE TABLE orders.group_order_shares (
  id                 text PRIMARY KEY,
  group_order_id     text NOT NULL REFERENCES orders.group_orders (id) ON DELETE CASCADE,
  user_id            text NOT NULL,                  -- whose dishes
  paid_by            text,                           -- whose card (the person, or the host paying for them)
  state              text NOT NULL DEFAULT 'due' CHECK (state IN ('due', 'paying', 'authorized', 'voided')),
  subtotal_cents     bigint NOT NULL CHECK (subtotal_cents >= 0),
  delivery_fee_cents bigint NOT NULL CHECK (delivery_fee_cents >= 0),
  service_fee_cents  bigint NOT NULL CHECK (service_fee_cents >= 0),
  fee_tax_cents      bigint NOT NULL CHECK (fee_tax_cents >= 0),
  tax_cents          bigint NOT NULL CHECK (tax_cents >= 0),
  tip_cents          bigint NOT NULL CHECK (tip_cents >= 0),
  total_cents        bigint NOT NULL,
  tax_calculation_id text,
  payment_intent     text,
  authorized_at      timestamptz,
  UNIQUE (group_order_id, user_id),
  CHECK (total_cents = subtotal_cents + delivery_fee_cents + service_fee_cents + fee_tax_cents + tax_cents + tip_cents)
);
