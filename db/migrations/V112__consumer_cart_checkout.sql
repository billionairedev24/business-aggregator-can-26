-- S-51 (consumer web, design 06 cart): the server-side cart (guests and signed-in people) and checkout.
-- Consumer range V111–V113. Additive only; see docs/DECISIONS.md "S-51".

-- ── Cart ─────────────────────────────────────────────────────────────────────────────────────────────────────────
-- orders.carts (V009) is kept: one row per person (customer_id) or per browsing session (device_key = SHA-256 of the
-- consumer-bff's guest id, never the id itself); the lines move to their own table instead of the `lines` jsonb.
ALTER TABLE orders.carts
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
CREATE UNIQUE INDEX ux_carts_customer ON orders.carts (customer_id) WHERE customer_id IS NOT NULL;
CREATE UNIQUE INDEX ux_carts_guest ON orders.carts (device_key) WHERE customer_id IS NULL AND device_key IS NOT NULL;
CREATE INDEX ix_carts_expires ON orders.carts (expires_at) WHERE customer_id IS NULL;

CREATE TABLE orders.cart_items (
  id text PRIMARY KEY,
  cart_id text NOT NULL REFERENCES orders.carts (id) ON DELETE CASCADE,
  offer_id text NOT NULL,                 -- catalogue.offers.id (logical)
  variant_id text,                        -- catalogue.variants.id (logical), null for an offer without variants
  qty integer NOT NULL CHECK (qty BETWEEN 1 AND 99),
  added_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_cart_items_line ON orders.cart_items (cart_id, offer_id, coalesce(variant_id, ''));

-- ── Checkout ─────────────────────────────────────────────────────────────────────────────────────────────────────
-- A checkout holds the stock it took and the payments it opened until the customer's card is authorized; placing it
-- creates the order. Open checkouts expire (stock back, authorizations canceled). `lines` is the snapshot:
-- [{lineId, offerId, variantId, productId, merchantId, name, option, qty, unitCents, amountCents, taxCents,
--   taxCalculationId, paymentIntent}] — ids and amounts, no personal data.
CREATE TABLE orders.checkouts (
  id text PRIMARY KEY,
  customer_id text NOT NULL,
  state text NOT NULL CHECK (state IN ('open', 'placed', 'abandoned')),
  order_id text NOT NULL UNIQUE,
  ref text NOT NULL UNIQUE,
  market text NOT NULL,
  delivery text NOT NULL CHECK (delivery IN ('pooled', 'direct')),
  window_id text,
  substitution text NOT NULL CHECK (substitution IN ('similar', 'refund', 'ask')),
  address_id text NOT NULL,               -- identity.addresses.id (logical)
  province char(2) NOT NULL,
  subtotal_cents bigint NOT NULL CHECK (subtotal_cents >= 0),
  delivery_fee_cents bigint NOT NULL CHECK (delivery_fee_cents >= 0),
  delivery_tax_cents bigint NOT NULL DEFAULT 0 CHECK (delivery_tax_cents >= 0),
  tax_cents bigint NOT NULL CHECK (tax_cents >= 0),
  total_cents bigint NOT NULL CHECK (total_cents >= 0),
  delivery_payment_intent text,
  lines jsonb NOT NULL,
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  placed_at timestamptz,
  CHECK ((delivery = 'pooled') = (window_id IS NOT NULL))
);
CREATE INDEX ix_checkouts_open ON orders.checkouts (expires_at) WHERE state = 'open';
CREATE INDEX ix_checkouts_customer ON orders.checkouts (customer_id, created_at DESC);

-- ── Orders ───────────────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE orders.orders
  ADD COLUMN checkout_id text,
  ADD COLUMN delivery_kind text CHECK (delivery_kind IN ('pooled', 'direct'));
CREATE UNIQUE INDEX ux_orders_checkout ON orders.orders (checkout_id) WHERE checkout_id IS NOT NULL;
-- Order references for consumer orders ("NL-50000"…); the dev seed uses NL-481xx / NL-482xx.
CREATE SEQUENCE orders.order_ref_seq START 50000;
