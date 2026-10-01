-- S-60 "Something's wrong": customer cases in Northline's queue (consumer-account range V160–V169). Additive only.
-- See docs/DECISIONS.md "S-60". Refund cases themselves stay payments.refunds (S-11's case queue).

-- A customer's photos for a report or a case (messaging.attachments belong to a business; these to a person).
CREATE TABLE messaging.customer_uploads (
  id           text PRIMARY KEY,
  customer_id  text NOT NULL,                               -- logical ref → identity.users
  storage_key  text NOT NULL,                               -- messaging/customers/<user>/<id>.<ext> (AttachmentStorage)
  file_name    text NOT NULL,
  content_type text NOT NULL,
  byte_size    bigint NOT NULL CHECK (byte_size > 0),
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_customer_uploads_customer ON messaging.customer_uploads (customer_id);

-- Customer cases are tickets with requester_type = 'customer' and no business: find them by person.
CREATE INDEX IF NOT EXISTS ix_tickets_requester ON messaging.tickets (requester_type, requester_id, created_at DESC);
