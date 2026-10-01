-- S-56 (consumer web · quotes): customers request quotes from several providers, compare them and accept one by
-- paying its escrow deposit. Additive only.

-- Accepting a quote is two calls: the PaymentIntent is opened (Stripe.js may still have to confirm the card), then the
-- quote is accepted and booked once the card is authorized. This row carries the booking id chosen up front (the
-- PaymentIntent's reference and transfer group) and the amounts between the two. One per quote version.
CREATE TABLE booking.quote_acceptances (
  quote_id text PRIMARY KEY REFERENCES booking.quotes(id),
  customer_id text NOT NULL,
  booking_id text NOT NULL UNIQUE,
  payment_intent text NOT NULL,
  amount_cents bigint NOT NULL CHECK (amount_cents > 0),
  tax_cents bigint NOT NULL CHECK (tax_cents >= 0),
  started_at timestamptz NOT NULL DEFAULT now(),
  accepted_at timestamptz
);

-- A customer's own requests ("Orders & bookings", the compare page).
CREATE INDEX ix_quote_requests_customer ON booking.quote_requests (customer_id, created_at DESC);
