-- Mobile gaps part 2: the live ETA of a service visit and what a promo code / points took off a booking. Additive.

-- Where the visit happens, when the wizard knew it (the address picked from the saved location or the map): the
-- customer's ETA screen measures the provider's latest position (Valkey, 5 min, never Postgres) against it. Personal
-- data like address_line: exported, blanked by erasure (S-105) and by the retention job with the address.
ALTER TABLE booking.bookings
  ADD COLUMN site_lat double precision CHECK (site_lat BETWEEN -90 AND 90),
  ADD COLUMN site_lng double precision CHECK (site_lng BETWEEN -180 AND 180),
  ADD COLUMN discount_cents bigint NOT NULL DEFAULT 0 CHECK (discount_cents >= 0),
  ADD COLUMN points_cents   bigint NOT NULL DEFAULT 0 CHECK (points_cents >= 0),
  ADD CONSTRAINT bookings_site_pair_chk CHECK ((site_lat IS NULL) = (site_lng IS NULL));
