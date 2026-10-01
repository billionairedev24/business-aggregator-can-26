-- Fulfilment workstream (range V200–V209). S-89: every order says how it reaches the customer.
-- See docs/DECISIONS.md "S-89".
--
-- Checkout already writes orders.orders.fulfilment_mode (shop: 'delivery'; food: 'delivery' | 'pickup') and, for
-- pickup, customer_eta (S-51, S-57). Rows written before that (dev seed, older fixtures) have no mode, and the kitchen
-- display read "empty" as delivery. Fill them in that way and make the column required with that default, so no reader
-- needs the fallback. customer_eta stays optional: it is the pickup customer's arrival time, null for deliveries.
UPDATE orders.orders SET fulfilment_mode = 'delivery' WHERE fulfilment_mode IS NULL;
ALTER TABLE orders.orders
  ALTER COLUMN fulfilment_mode SET DEFAULT 'delivery',
  ALTER COLUMN fulfilment_mode SET NOT NULL;
-- A pickup order without an arrival time is allowed (the customer didn't say); a delivery never has one.
ALTER TABLE orders.orders
  ADD CONSTRAINT chk_orders_customer_eta_pickup CHECK (customer_eta IS NULL OR fulfilment_mode = 'pickup');
