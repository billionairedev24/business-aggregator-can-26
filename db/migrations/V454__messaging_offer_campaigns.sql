-- S-157 a minimal producer of Northline's commercial messages (range V452–V461). Additive only.
-- An offer staff send from the console (screen `offers`) to the customers of one market who consented to that channel
-- (CASL express consent, messaging.consent_records). Sending queues one messaging.deferred_notifications row per
-- recipient (event_type messaging.offer, event_id = the campaign id) spread over time; the worker's deferred job sends
-- them, re-checking each person's consent at send time and adding the sender identification and the unsubscribe link.
-- The words are the offer's own (no personal data); recipients are counted, not listed.
CREATE TABLE messaging.offer_campaigns (
  id          text        PRIMARY KEY,
  market      text        NOT NULL CHECK (market ~ '^[A-Z]{2}$'),          -- province / territory code
  channel     text        NOT NULL CHECK (channel IN ('email', 'sms')),
  title_en    text        NOT NULL CHECK (char_length(title_en) BETWEEN 1 AND 80),
  body_en     text        NOT NULL CHECK (char_length(body_en) BETWEEN 1 AND 600),
  title_fr    text        NOT NULL CHECK (char_length(title_fr) BETWEEN 1 AND 80),
  body_fr     text        NOT NULL CHECK (char_length(body_fr) BETWEEN 1 AND 600),
  path        text        NOT NULL CHECK (path ~ '^/[A-Za-z0-9/_.?=&%-]{0,199}$' AND path NOT LIKE '//%'),
  recipients  int         NOT NULL CHECK (recipients >= 0),
  per_minute  int         NOT NULL CHECK (per_minute BETWEEN 1 AND 50),
  sent_by     text        NOT NULL,                                       -- staff member (identity.users)
  sent_at     timestamptz NOT NULL,
  finishes_at timestamptz NOT NULL                                        -- when the last recipient is due
);
CREATE INDEX ix_offer_campaigns_sent ON messaging.offer_campaigns (sent_at DESC);

COMMENT ON TABLE messaging.offer_campaigns IS
  'S-157: offers staff sent from the console to the consenting customers of a market (email or SMS), throttled '
  'through messaging.deferred_notifications.';
