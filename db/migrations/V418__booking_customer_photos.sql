-- S-153: photos a customer adds to a quote request or to a booking's job details. Bytes are in object storage
-- (customers/<userId>/<id>.<ext>, the shared ObjectStore and its virus-scanner hook); this is the metadata. A photo is
-- uploaded first (ref null) and attached when the request or booking is written; only its owner and the businesses
-- the request went to (or the booking's business) can read it. Personal data: exported and erased with the customer.
CREATE TABLE booking.customer_photos (
  id           text        PRIMARY KEY,
  customer_id  text        NOT NULL,
  storage_key  text        NOT NULL,
  content_type text        NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'image/heic', 'image/heif', 'image/webp')),
  byte_size    bigint      NOT NULL CHECK (byte_size > 0),
  file_name    text        NOT NULL,
  ref_type     text        CHECK (ref_type IN ('quote_request', 'booking')),
  ref_id       text,
  created_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT customer_photos_ref_pair_chk CHECK ((ref_type IS NULL) = (ref_id IS NULL))
);
CREATE INDEX ix_customer_photos_owner ON booking.customer_photos (customer_id, created_at DESC);
CREATE INDEX ix_customer_photos_ref ON booking.customer_photos (ref_type, ref_id) WHERE ref_id IS NOT NULL;
