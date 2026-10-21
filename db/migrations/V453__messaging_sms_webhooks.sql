-- S-157 inbound SMS keywords and delivery statuses (range V452–V461). Additive only.
-- Webhooks from the SMS provider (Twilio: signature-verified). Minimised: no phone number and no message text are
-- stored — the sender as the SHA-256 of its E.164 number (the consent records' address hash), the keyword only. Kept
-- 90 days (SmsWebhookPurge), long enough to answer "did this STOP arrive?" next to the consent history.

-- One row per inbound text the provider delivered: the provider's message id dedupes retries (an opt-out is applied
-- and answered once).
CREATE TABLE messaging.sms_inbound (
  provider     text        NOT NULL CHECK (provider IN ('twilio', 'local')),
  message_id   text        NOT NULL CHECK (char_length(message_id) BETWEEN 1 AND 64),
  received_at  timestamptz NOT NULL,
  from_hash    text        NOT NULL CHECK (from_hash ~ '^[0-9a-f]{64}$'),
  keyword      text        NOT NULL CHECK (keyword IN ('stop', 'start', 'help', 'none')),
  accounts     int         NOT NULL DEFAULT 0 CHECK (accounts >= 0),   -- accounts with that number it was applied to
  PRIMARY KEY (provider, message_id)
);
CREATE INDEX ix_sms_inbound_received ON messaging.sms_inbound (received_at);

-- The latest delivery status the provider reported for a text Northline sent (StatusCallback): queued → sent →
-- delivered | undelivered | failed. A final status is never replaced by an earlier one arriving late.
CREATE TABLE messaging.sms_delivery_status (
  provider     text        NOT NULL CHECK (provider IN ('twilio', 'local')),
  message_id   text        NOT NULL CHECK (char_length(message_id) BETWEEN 1 AND 64),
  status       text        NOT NULL CHECK (status IN ('accepted', 'scheduled', 'queued', 'sending', 'sent',
                                                       'delivered', 'undelivered', 'failed', 'canceled', 'read')),
  error_code   text        CHECK (error_code IS NULL OR char_length(error_code) <= 16),
  first_at     timestamptz NOT NULL,
  updated_at   timestamptz NOT NULL,
  PRIMARY KEY (provider, message_id)
);
CREATE INDEX ix_sms_delivery_status_updated ON messaging.sms_delivery_status (updated_at);
CREATE INDEX ix_sms_delivery_status_problems ON messaging.sms_delivery_status (status, updated_at)
  WHERE status IN ('undelivered', 'failed');

COMMENT ON TABLE messaging.sms_inbound IS
  'S-157: inbound texts from the SMS provider (STOP / START / HELP keywords), deduplicated by message id; no number or text.';
COMMENT ON TABLE messaging.sms_delivery_status IS
  'S-157: delivery status callbacks of texts Northline sent (latest per message); kept 90 days.';
