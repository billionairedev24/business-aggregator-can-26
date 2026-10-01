-- S-59 Account area: profile, addresses, payment methods, notifications, language, dietary & accessibility, Plus
-- (consumer-account range V160–V169). Additive only. See docs/DECISIONS.md "S-59".

-- ── identity.users: what the Profile tab adds, and "Delete account…" ───────────────────────────────────────────
ALTER TABLE identity.users
  ADD COLUMN pronouns text CHECK (pronouns IN ('she', 'he', 'they', 'none')),
  ADD COLUMN birthday_month smallint CHECK (birthday_month BETWEEN 1 AND 12),
  ADD COLUMN birthday_day smallint CHECK (birthday_day BETWEEN 1 AND 31),
  ADD COLUMN erasure_requested_at timestamptz,             -- staff erase the account (PIPEDA), status → erased
  ADD CONSTRAINT users_birthday_both CHECK ((birthday_month IS NULL) = (birthday_day IS NULL));
CREATE INDEX IF NOT EXISTS ix_users_erasure_requested ON identity.users (erasure_requested_at)
  WHERE erasure_requested_at IS NOT NULL;

-- ── identity.addresses: the person's name for an address ("Mum"), order, and removal ──────────────────────────────
-- Removed addresses keep their row (orders.orders.address_id may point at them) and leave the book.
ALTER TABLE identity.addresses
  ADD COLUMN label text CHECK (label IS NULL OR length(label) <= 40),
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN deleted_at timestamptz;
CREATE INDEX IF NOT EXISTS ix_addresses_user_live ON identity.addresses (user_id) WHERE deleted_at IS NULL;

-- ── account.preferences: Language & region, Dietary & accessibility ─────────────────────────────────────────────
CREATE TABLE account.preferences (
  user_id       text PRIMARY KEY,                           -- logical ref → identity.users
  province      char(2),                                    -- where the person shops; null = follow the location
  units         text NOT NULL DEFAULT 'metric' CHECK (units IN ('metric', 'imperial')),
  time_format   text NOT NULL DEFAULT '12h' CHECK (time_format IN ('12h', '24h')),
  dietary       text[] NOT NULL DEFAULT '{}',               -- halal, kosher, vegetarian, vegan, gluten_free …
  allergies     text CHECK (allergies IS NULL OR length(allergies) <= 200),
  accessibility text[] NOT NULL DEFAULT '{}',               -- step_free, deaf_text, low_vision …
  access_notes  text CHECK (access_notes IS NULL OR length(access_notes) <= 500),
  display       text[] NOT NULL DEFAULT '{}',               -- larger_text, high_contrast, reduce_motion
  updated_at    timestamptz NOT NULL DEFAULT now()
);

-- ── payments.customer_cards: the saved cards' summary (brand, last 4, expiry — never a card number) ────────────────
-- Stripe holds the cards (SetupIntents); this mirror is refreshed on every list and feeds the account menu
-- ("Visa ··4471") and the billing history's card column.
CREATE TABLE payments.customer_cards (
  customer_id    text NOT NULL,                             -- logical ref → identity.users
  payment_method text NOT NULL,                             -- pm_…
  brand          text NOT NULL,
  last4          char(4) NOT NULL,
  exp_month      smallint NOT NULL,
  exp_year       smallint NOT NULL,
  is_default     boolean NOT NULL DEFAULT false,
  added_at       timestamptz NOT NULL,
  PRIMARY KEY (customer_id, payment_method)
);
CREATE INDEX IF NOT EXISTS ix_payment_intents_customer ON payments.payment_intents (customer_id);

-- ── messaging.notification_prefs: the customer's matrix next to the Studio member's (same person, same row) ─────────
ALTER TABLE messaging.notification_prefs
  ADD COLUMN customer_matrix jsonb,                         -- {event: {push|sms|email: bool}} (changed cells)
  ADD COLUMN quiet_on boolean,                              -- customer quiet hours on/off (null = on)
  ADD COLUMN notify_lang text CHECK (notify_lang IN ('app', 'en', 'fr')),
  ADD COLUMN marketing text CHECK (marketing IN ('weekly', 'rewards', 'none'));
