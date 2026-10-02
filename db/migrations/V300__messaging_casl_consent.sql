-- S-108 CASL consent capture and unsubscribe (range V300–V304, docs/IMPLEMENTATION_PLAN.md). Additive only.

-- Proof of consent to Northline's commercial electronic messages (CASL s. 6, 10, 13: the sender must prove consent).
-- Append-only history, one row per grant or withdrawal of one category by one person; the current state of a category
-- is its newest row. Express consent only (the only basis Northline's marketing relies on): no implied consent rows.
-- Minimised: the IP address is truncated (/24, /48) and the user agent hashed; the address the consent covered is
-- kept as a SHA-256 hash, so a complaint about an address can be answered after the account is erased. Rows outlive
-- a withdrawal or an erasure for the CASL proof period (3 years, ConsentRetention), then are purged.
CREATE TABLE messaging.consent_records (
  id              text        PRIMARY KEY,
  user_id         text        NOT NULL,                       -- logical ref → identity.users
  category        text        NOT NULL CHECK (category IN ('marketing_email', 'marketing_sms', 'marketing_push')),
  action          text        NOT NULL CHECK (action IN ('granted', 'withdrawn')),
  basis           text        NOT NULL DEFAULT 'express' CHECK (basis IN ('express')),
  at              timestamptz NOT NULL,
  -- where it happened: the person (web/app sign-up, settings, checkout, Studio), a list import, an unsubscribe link,
  -- a mailbox's one-click unsubscribe (RFC 8058), staff on the person's behalf, or the account's erasure
  source          text        NOT NULL CHECK (source IN ('web_signup', 'app_signup', 'web_settings', 'app_settings',
                                                         'checkout', 'studio', 'import', 'unsubscribe_link',
                                                         'list_unsubscribe', 'sms_keyword', 'console', 'erasure')),
  -- the consent wording shown (a version of the catalogue in code, never edited once published) and its language
  wording_version text        CHECK (wording_version IS NULL OR wording_version ~ '^[a-z0-9_.-]{3,64}$'),
  language        text        CHECK (language IN ('en', 'fr')),
  address_hash    text        CHECK (address_hash IS NULL OR address_hash ~ '^[0-9a-f]{64}$'),
  ip_prefix       text        CHECK (ip_prefix IS NULL OR char_length(ip_prefix) <= 64),
  user_agent_hash text        CHECK (user_agent_hash IS NULL OR user_agent_hash ~ '^[0-9a-f]{64}$'),
  actor_id        text,                                       -- staff member for 'console', else null
  CHECK ((action = 'granted') = (wording_version IS NOT NULL))
);
CREATE INDEX ix_consent_records_current ON messaging.consent_records (user_id, category, at DESC, id DESC);
CREATE INDEX ix_consent_records_address ON messaging.consent_records (address_hash) WHERE address_hash IS NOT NULL;
CREATE INDEX ix_consent_records_at ON messaging.consent_records (at);

COMMENT ON TABLE messaging.consent_records IS
  'S-108 CASL proof of express consent to commercial messages: append-only grants and withdrawals; kept 3 years after '
  'the withdrawal (ConsentRetention).';
