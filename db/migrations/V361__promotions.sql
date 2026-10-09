-- Mobile gaps part 2: promo codes and points at checkout. New module `promotions` (schema promotions). Additive.
-- Codes are made by Northline staff in the console (finance); a code is funded by Northline (platform-wide) or by one
-- business (only that business's lines count). See docs/DECISIONS.md "Mobile gaps part 2".

CREATE SCHEMA IF NOT EXISTS promotions;

CREATE TABLE promotions.codes (
  id                  text        PRIMARY KEY,
  code                text        NOT NULL,                       -- upper case, A–Z 0–9 and dashes, 3–20
  description         text,                                       -- staff's note, never shown to customers
  kind                text        NOT NULL CHECK (kind IN ('percent', 'amount')),
  percent             integer     CHECK (percent BETWEEN 1 AND 100),
  amount_cents        bigint      CHECK (amount_cents >= 100),
  max_discount_cents  bigint      CHECK (max_discount_cents >= 100), -- cap of a percent code, optional
  min_spend_cents     bigint      NOT NULL DEFAULT 0 CHECK (min_spend_cents >= 0),
  starts_at           timestamptz NOT NULL,
  ends_at             timestamptz NOT NULL,
  per_customer_limit  integer     NOT NULL DEFAULT 1 CHECK (per_customer_limit >= 1),
  total_limit         integer     CHECK (total_limit >= 1),       -- null = no overall limit
  funded_by           text        NOT NULL CHECK (funded_by IN ('northline', 'merchant')),
  merchant_id         text,                                       -- the funding business (merchants.merchants.id, logical)
  applies_to          text[]      NOT NULL,                       -- goods | food | service
  active              boolean     NOT NULL DEFAULT true,
  created_by          text        NOT NULL,
  created_at          timestamptz NOT NULL,
  updated_at          timestamptz NOT NULL,
  CHECK ((kind = 'percent') = (percent IS NOT NULL)),
  CHECK ((kind = 'amount') = (amount_cents IS NOT NULL)),
  CHECK ((funded_by = 'merchant') = (merchant_id IS NOT NULL)),
  CHECK (ends_at > starts_at),
  CHECK (cardinality(applies_to) >= 1 AND applies_to <@ ARRAY['goods', 'food', 'service']::text[])
);
CREATE UNIQUE INDEX ux_codes_code ON promotions.codes (code);

-- One checkout's use of a code and/or points. Reserved when the payment opens (it counts towards the limits until
-- reserved_until), redeemed when the order is placed / the booking confirmed, released when the checkout is abandoned.
-- ref = the checkout: ('goods', orders.checkouts.order_id), ('food', orders.food_checkouts.id), ('service', booking id).
CREATE TABLE promotions.redemptions (
  id               text        PRIMARY KEY,
  customer_id      text        NOT NULL,
  kind             text        NOT NULL CHECK (kind IN ('goods', 'food', 'service')),
  ref_id           text        NOT NULL,
  code_id          text        REFERENCES promotions.codes (id),
  discount_cents   bigint      NOT NULL DEFAULT 0 CHECK (discount_cents >= 0),
  points           bigint      NOT NULL DEFAULT 0 CHECK (points >= 0),
  points_cents     bigint      NOT NULL DEFAULT 0 CHECK (points_cents >= 0),
  state            text        NOT NULL CHECK (state IN ('reserved', 'redeemed', 'released')),
  reserved_until   timestamptz NOT NULL,
  created_at       timestamptz NOT NULL,
  redeemed_at      timestamptz,
  released_at      timestamptz,
  CHECK (code_id IS NOT NULL OR points > 0)
);
CREATE UNIQUE INDEX ux_redemptions_ref ON promotions.redemptions (kind, ref_id);
CREATE INDEX ix_redemptions_code ON promotions.redemptions (code_id, state);
CREATE INDEX ix_redemptions_customer ON promotions.redemptions (customer_id, created_at DESC);

-- How a redemption splits over the escrows it pays into: one line per order line (goods), food order or booking.
-- escrow_ref_type / escrow_ref_id = payments.escrows (ref_type, ref_id).
CREATE TABLE promotions.redemption_lines (
  redemption_id    text        NOT NULL REFERENCES promotions.redemptions (id),
  escrow_ref_type  text        NOT NULL CHECK (escrow_ref_type IN ('order_line', 'food_order', 'booking')),
  escrow_ref_id    text        NOT NULL,
  merchant_id      text        NOT NULL,
  amount_cents     bigint      NOT NULL CHECK (amount_cents > 0),        -- the line before the discount
  discount_cents   bigint      NOT NULL DEFAULT 0 CHECK (discount_cents >= 0),
  points_cents     bigint      NOT NULL DEFAULT 0 CHECK (points_cents >= 0),
  points_returned_cents bigint NOT NULL DEFAULT 0 CHECK (points_returned_cents >= 0),
  PRIMARY KEY (escrow_ref_type, escrow_ref_id),
  CHECK (discount_cents < amount_cents)
);
CREATE INDEX ix_redemption_lines_redemption ON promotions.redemption_lines (redemption_id);
