-- S-55 (consumer web · booking wizard): bookings the customer makes and pays for themselves. Additive only.
--
-- Access instructions (gate code, buzzer, where the keys are) and the contact number for the day are private: the
-- provider sees them only around the visit (design 06: "shared with the provider only for the two hours around the
-- visit"). They are sealed (ca.northline.shared.crypto.SecretSealer, the api's envelope key — KMS_ENCRYPTION_KEY_ID)
-- in their own table, bound to the booking id, and never enter bookings.details, events or webhooks.
CREATE TABLE booking.access_notes (
  booking_id text PRIMARY KEY REFERENCES booking.bookings(id),
  key_ref text NOT NULL,
  wrapped_key bytea NOT NULL,
  ciphertext bytea NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- Who made the booking and how: 'studio' (seeded / merchant-created, before S-55) or 'customer' (the wizard); the
-- customer's own cancellation deadline shown on the confirmation ("free cancellation until …"); the tax collected on top.
ALTER TABLE booking.bookings
  ADD COLUMN source text CHECK (source IN ('studio', 'customer')),
  ADD COLUMN tax_cents bigint CHECK (tax_cents IS NULL OR tax_cents >= 0),
  ADD COLUMN free_cancel_until timestamptz;

-- The overlap guard of the checkout (an advisory lock per member, then this lookup) and the customer's list.
CREATE INDEX ix_bookings_member_starts ON booking.bookings (member_user_id, starts_at) WHERE state <> 'cancelled';
