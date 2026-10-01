-- 2026-09-30 (S-57, consumer web): food checkout. Additive only. See docs/DECISIONS.md "S-57".

-- A food checkout before and after its card payment is authorized. The order itself (orders.orders + order_lines, which
-- the kitchen display reads) is written only once the escrow hold exists, with this row's id, so a kitchen never sees an
-- unpaid order. Keeps what the order needs beyond V009's columns: the priced lines as shown, the delivery snapshot
-- (address typed on the Location screen — PII, never in events), drop-off and extras, and the fee tax.
CREATE SEQUENCE IF NOT EXISTS orders.food_order_numbers START 10000;

CREATE TABLE orders.food_checkouts (
  id text PRIMARY KEY,                        -- = orders.orders.id once placed
  ref text NOT NULL UNIQUE,                   -- FD-10000
  customer_id text NOT NULL,
  merchant_id text NOT NULL,
  kitchen_name text NOT NULL,                 -- display snapshot at checkout
  kitchen_slug text,
  state text NOT NULL CHECK (state IN ('pending', 'placed', 'abandoned')),
  fulfilment_mode text NOT NULL CHECK (fulfilment_mode IN ('delivery', 'pickup')),
  scheduled_for timestamptz,
  customer_eta timestamptz,
  eta_from_min integer,
  eta_to_min integer,
  lines jsonb NOT NULL,
  subtotal_cents bigint NOT NULL CHECK (subtotal_cents > 0),
  delivery_fee_cents bigint NOT NULL DEFAULT 0 CHECK (delivery_fee_cents >= 0),
  service_fee_cents bigint NOT NULL DEFAULT 0 CHECK (service_fee_cents >= 0),
  fee_tax_cents bigint NOT NULL DEFAULT 0 CHECK (fee_tax_cents >= 0),
  tax_cents bigint NOT NULL DEFAULT 0 CHECK (tax_cents >= 0),
  tip_cents bigint NOT NULL DEFAULT 0 CHECK (tip_cents >= 0),
  total_cents bigint NOT NULL CHECK (total_cents = subtotal_cents + delivery_fee_cents + service_fee_cents
                                                  + fee_tax_cents + tax_cents + tip_cents),
  province char(2) NOT NULL,
  tax_calculation_id text,
  payment_intent text,                        -- Stripe pi_… (the escrow hold, refType food_order)
  delivery jsonb,                             -- {street, unit, city, postalCode, lat, lng, zoneId, zone, dropoff, note, extras}
  created_at timestamptz NOT NULL DEFAULT now(),
  placed_at timestamptz,
  CHECK (state <> 'placed' OR (placed_at IS NOT NULL AND payment_intent IS NOT NULL)),
  CHECK (fulfilment_mode = 'pickup' OR delivery IS NOT NULL)
);
CREATE INDEX ix_food_checkouts_customer ON orders.food_checkouts(customer_id, created_at DESC);
-- logical ref (cross-module, no FK): orders.food_checkouts.customer_id → identity.users.id
-- logical ref (cross-module, no FK): orders.food_checkouts.merchant_id → merchants.merchants.id

-- Northline's own charges captured with a food escrow (courier + service fee, their tax, the courier's tip). They are
-- never transferred to the merchant; 0 for every other escrow.
ALTER TABLE payments.escrows
  ADD COLUMN platform_fee_cents bigint NOT NULL DEFAULT 0 CHECK (platform_fee_cents >= 0),
  ADD COLUMN platform_tax_cents bigint NOT NULL DEFAULT 0 CHECK (platform_tax_cents >= 0),
  ADD COLUMN tip_cents bigint NOT NULL DEFAULT 0 CHECK (tip_cents >= 0);

-- A food order's card payment is one PaymentIntent, one Stripe Tax calculation and one escrow referenced as
-- ('food_order', <order id>). Widening the V011 / V112 checks only: every existing value stays valid.
ALTER TABLE payments.escrows DROP CONSTRAINT escrows_ref_type_check;
ALTER TABLE payments.escrows
  ADD CONSTRAINT escrows_ref_type_check CHECK (ref_type IN ('booking', 'order_line', 'food_order'));
ALTER TABLE payments.payment_intents DROP CONSTRAINT payment_intents_ref_type_check;
ALTER TABLE payments.payment_intents
  ADD CONSTRAINT payment_intents_ref_type_check CHECK (ref_type IN ('booking', 'order_line', 'order_delivery', 'food_order'));
ALTER TABLE payments.tax_calculations DROP CONSTRAINT tax_calculations_ref_type_check;
ALTER TABLE payments.tax_calculations
  ADD CONSTRAINT tax_calculations_ref_type_check CHECK (ref_type IN ('booking', 'order_line', 'order_delivery', 'food_order'));
