-- schema: orders · owner: Java (java)
-- Generated from the Northline spec (docs/DATA_MODEL.md). Hand-edit freely; keep column names.
CREATE SCHEMA IF NOT EXISTS orders;

-- Persisted cart per customer (guest carts keyed by device).
CREATE TABLE orders.carts (
  -- PK
  id text PRIMARY KEY,
  -- null = guest
  customer_id text,
  device_key text,
  lines jsonb,
  expires_at timestamptz
);
-- TODO indexes/constraints: index(customer_id)

-- Goods (pooled) or food (direct) orders; multi-merchant.
CREATE TABLE orders.orders (
  -- PK
  id text PRIMARY KEY,
  customer_id text,
  -- goods | food
  type text CHECK (type IN ('goods', 'food')),
  -- placed | accepted | packing | ready | picked_up | delivered | confirmed | refunded | cancelled
  state text CHECK (state IN ('placed', 'accepted', 'packing', 'ready', 'picked_up', 'delivered', 'confirmed', 'refunded', 'cancelled')),
  address_id text,
  -- pooled
  window_id text,
  -- food
  scheduled_for timestamptz,
  substitution_policy text,
  subtotal_cents bigint,
  delivery_fee_cents bigint,
  service_fee_cents bigint,
  tax_cents bigint,
  tip_cents bigint,
  payment_intent_id text,
  group_order_id text
);
-- TODO indexes/constraints: index(customer_id) · index(state,window_id)
-- outbox events: order.placed · order.packed · order.delivered · order.confirmed
-- search projection: merchants.stats

-- Lines per merchant; food lines carry chosen modifiers.
CREATE TABLE orders.order_lines (
  -- PK
  id text PRIMARY KEY,
  -- FK
  order_id text,
  merchant_id text,
  offer_id text,
  variant_id text,
  menu_item_id text,
  qty integer,
  unit_cents bigint,
  modifiers jsonb,
  substituted_with text,
  -- pending | packed | short | refunded
  state text CHECK (state IN ('pending', 'packed', 'short', 'refunded'))
);
-- TODO indexes/constraints: index(order_id) · index(merchant_id,state)
-- outbox events: order.line_refunded

-- Pooled runs on offer per zone with cut-off.
CREATE TABLE orders.delivery_windows (
  -- PK
  id text PRIMARY KEY,
  zone_id text,
  starts_at timestamptz,
  ends_at timestamptz,
  cutoff_at timestamptz,
  capacity integer,
  -- fulfilment
  run_id text
);
-- TODO indexes/constraints: index(zone_id,starts_at)
-- outbox events: window.closing
-- search projection: listings.on_tonights_run

-- Shared food orders with per-person payment.
CREATE TABLE orders.group_orders (
  -- PK
  id text PRIMARY KEY,
  host_user_id text,
  merchant_id text,
  -- unique
  link_code text,
  member_user_ids text[],
  locks_at timestamptz
);
-- TODO indexes/constraints: unique(link_code)

-- Foreign keys (in-module only)
-- logical ref (cross-module, no FK): orders.carts.customer_id → identity.users.id
-- logical ref (cross-module, no FK): orders.orders.customer_id → identity.users.id
-- logical ref (cross-module, no FK): orders.orders.address_id → identity.addresses.id
ALTER TABLE orders.orders ADD CONSTRAINT fk_orders_window_id FOREIGN KEY (window_id) REFERENCES orders.delivery_windows(id);
-- logical ref (cross-module, no FK): orders.orders.payment_intent_id → payments.payment_intents.id
ALTER TABLE orders.orders ADD CONSTRAINT fk_orders_group_order_id FOREIGN KEY (group_order_id) REFERENCES orders.group_orders(id);
ALTER TABLE orders.order_lines ADD CONSTRAINT fk_order_lines_order_id FOREIGN KEY (order_id) REFERENCES orders.orders(id);
-- logical ref (cross-module, no FK): orders.order_lines.offer_id → catalogue.offers.id
-- logical ref (cross-module, no FK): orders.order_lines.variant_id → catalogue.variants.id
-- logical ref (cross-module, no FK): orders.order_lines.menu_item_id → food.menu_items.id
-- logical ref (cross-module, no FK): orders.delivery_windows.zone_id → region.zones.id
-- logical ref (cross-module, no FK): orders.delivery_windows.run_id → fulfilment.runs.id
