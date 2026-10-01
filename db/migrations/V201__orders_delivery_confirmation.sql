-- Fulfilment workstream (range V200–V209). S-78: delivery and the customer's confirmation drive goods escrow.
-- See docs/DECISIONS.md "S-78".
--
-- delivered_at (V042) is when the courier dropped the order off (or the customer confirmed first); the proof the
-- courier gave and the customer's confirmation are new. The escrow's own release clock stays in payments.escrows.
ALTER TABLE orders.orders
  ADD COLUMN delivery_proof text CHECK (delivery_proof IS NULL OR delivery_proof IN ('photo', 'signature', 'pin')),
  ADD COLUMN confirmed_at timestamptz,
  ADD CONSTRAINT chk_orders_confirmed_state CHECK (confirmed_at IS NULL OR state IN ('confirmed', 'refunded'));
