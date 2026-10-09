-- Mobile gaps part 2: what a promo code and points took off a checkout, and the goods courier tip. Additive; the food
-- checkout's sum check is replaced by the same sum less the discount and the points (every existing row has 0 of both).

ALTER TABLE orders.checkouts
  ADD COLUMN discount_cents bigint NOT NULL DEFAULT 0 CHECK (discount_cents >= 0),
  ADD COLUMN points_cents   bigint NOT NULL DEFAULT 0 CHECK (points_cents >= 0),
  ADD COLUMN tip_cents      bigint NOT NULL DEFAULT 0 CHECK (tip_cents >= 0),
  ADD COLUMN promo_code     text;

ALTER TABLE orders.orders
  ADD COLUMN discount_cents bigint NOT NULL DEFAULT 0 CHECK (discount_cents >= 0),
  ADD COLUMN points_cents   bigint NOT NULL DEFAULT 0 CHECK (points_cents >= 0),
  ADD COLUMN promo_code     text;

ALTER TABLE orders.food_checkouts
  ADD COLUMN discount_cents bigint NOT NULL DEFAULT 0 CHECK (discount_cents >= 0),
  ADD COLUMN points_cents   bigint NOT NULL DEFAULT 0 CHECK (points_cents >= 0),
  ADD COLUMN promo_code     text;

DO $$
DECLARE c record;
BEGIN
  FOR c IN SELECT conname FROM pg_constraint
            WHERE conrelid = 'orders.food_checkouts'::regclass AND contype = 'c'
              AND pg_get_constraintdef(oid) LIKE '%total_cents = %'
  LOOP
    EXECUTE format('ALTER TABLE orders.food_checkouts DROP CONSTRAINT %I', c.conname);
  END LOOP;
END $$;
ALTER TABLE orders.food_checkouts
  ADD CONSTRAINT food_checkouts_total_chk
  CHECK (total_cents = subtotal_cents - discount_cents - points_cents + delivery_fee_cents + service_fee_cents
                       + fee_tax_cents + tax_cents + tip_cents);
