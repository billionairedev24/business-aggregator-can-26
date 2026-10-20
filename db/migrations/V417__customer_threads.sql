-- S-153: customer↔provider messages on quote requests and bookings. The threads are the Studio's (V070); the customer
-- now reads and writes them too. One read mark for the customer (the business keeps merchant_read_at). Additive.
ALTER TABLE messaging.threads
  ADD COLUMN customer_read_at timestamptz;

-- The customer's own threads, most recent first (GET /me/threads).
CREATE INDEX ix_threads_customer ON messaging.threads (counterpart_id, last_message_at DESC NULLS LAST)
  WHERE kind = 'customer';
-- One thread per business and job: Conversations.open is idempotent on (merchant, ref) — now also under concurrency.
CREATE UNIQUE INDEX ux_threads_merchant_ref ON messaging.threads (merchant_id, ref_type, ref_id)
  WHERE kind = 'customer' AND ref_type IS NOT NULL AND ref_id IS NOT NULL;
