-- Mobile gaps part 2: promo codes, points and courier tips in payments. Additive; checks widened only.

-- What a promo code and points took off an escrow. amount_cents stays the merchant's taxable sale after the code
-- (GST/HST/PST/QST are computed on it); points pay part of amount + tax and are Northline's money (ledger account
-- points_redeemed). discount_funded_by says who carries the code: northline tops the merchant up at release
-- (ledger account promotions), merchant means the business sold for less (shown gross in its ledger account).
ALTER TABLE payments.escrows
  ADD COLUMN discount_cents     bigint NOT NULL DEFAULT 0 CHECK (discount_cents >= 0),
  ADD COLUMN discount_funded_by text CHECK (discount_funded_by IN ('northline', 'merchant')),
  ADD COLUMN points_cents       bigint NOT NULL DEFAULT 0 CHECK (points_cents >= 0),
  ADD CONSTRAINT escrows_discount_funder_chk CHECK ((discount_cents > 0) = (discount_funded_by IS NOT NULL));

-- A refund gives back its share of the points (to the wallet, not the card) and, after a release, takes back its
-- share of a Northline top-up from the merchant.
ALTER TABLE payments.refunds
  ADD COLUMN points_cents       bigint NOT NULL DEFAULT 0 CHECK (points_cents >= 0),
  ADD COLUMN promo_return_cents bigint NOT NULL DEFAULT 0 CHECK (promo_return_cents >= 0);

-- Courier tips: at checkout (on the food order's PaymentIntent, or the goods order's delivery-fee one) or after the
-- delivery (a PaymentIntent of its own, ref_type courier_tip). 100 % is owed to the courier: held in the pooled
-- courier_tips ledger account until the delivery names its courier, then moved to courier:<user id>.
CREATE TABLE payments.courier_tips (
  id                text        PRIMARY KEY,
  order_id          text        NOT NULL,                    -- orders.orders.id (logical)
  customer_id       text        NOT NULL,
  courier_user_id   text,                                    -- identity.users.id of the courier, once known
  amount_cents      bigint      NOT NULL CHECK (amount_cents > 0),
  source            text        NOT NULL CHECK (source IN ('checkout', 'after_delivery')),
  stripe_payment_intent text,                                -- Stripe pi_… it was (or will be) charged on
  state             text        NOT NULL CHECK (state IN ('pending', 'captured', 'allocated', 'refunded', 'canceled')),
  created_at        timestamptz NOT NULL,
  captured_at       timestamptz,
  allocated_at      timestamptz,
  refunded_at       timestamptz,
  refund_reason     text CHECK (refund_reason IN ('not_delivered', 'duplicate', 'amount_error')),
  refunded_by       text,
  stripe_refund     text
);
CREATE UNIQUE INDEX ux_courier_tips_checkout ON payments.courier_tips (order_id) WHERE source = 'checkout';
CREATE UNIQUE INDEX ux_courier_tips_after ON payments.courier_tips (order_id)
  WHERE source = 'after_delivery' AND state <> 'canceled';
CREATE INDEX ix_courier_tips_order ON payments.courier_tips (order_id);
CREATE INDEX ix_courier_tips_courier ON payments.courier_tips (courier_user_id, state);

ALTER TABLE payments.payment_intents DROP CONSTRAINT payment_intents_ref_type_check;
ALTER TABLE payments.payment_intents
  ADD CONSTRAINT payment_intents_ref_type_check
  CHECK (ref_type IN ('booking', 'order_line', 'order_delivery', 'food_order', 'courier_tip'));
