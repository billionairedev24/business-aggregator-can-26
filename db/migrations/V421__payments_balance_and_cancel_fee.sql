-- S-153: two new escrow references of a booking — the balance of a deposit quote (its own PaymentIntent, held before
-- the event) and a late-cancellation fee (captured from the booking's hold, the rest released). Additive.
ALTER TABLE payments.escrows DROP CONSTRAINT escrows_ref_type_check;
ALTER TABLE payments.escrows
  ADD CONSTRAINT escrows_ref_type_check
  CHECK (ref_type IN ('booking', 'order_line', 'food_order', 'booking_balance', 'booking_cancel_fee'));
ALTER TABLE payments.payment_intents DROP CONSTRAINT payment_intents_ref_type_check;
ALTER TABLE payments.payment_intents
  ADD CONSTRAINT payment_intents_ref_type_check
  CHECK (ref_type IN ('booking', 'order_line', 'order_delivery', 'food_order', 'courier_tip', 'booking_balance'));

-- The hold-expiry sweeper: a booking's PaymentIntent left without an escrow (the slot hold or the acceptance lapsed
-- after the card was confirmed) is canceled at Stripe instead of lapsing after 7 days.
CREATE INDEX ix_payment_intents_unheld_bookings ON payments.payment_intents (created_at)
  WHERE ref_type IN ('booking', 'booking_balance') AND state IN ('requires_action', 'authorized');
