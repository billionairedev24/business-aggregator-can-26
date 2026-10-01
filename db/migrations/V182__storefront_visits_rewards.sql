-- S-75: storefront analytics and the provider-funded reward.

-- Visits to a business's public page, counted per day in the business's time zone: a number only. No cookie, IP,
-- user agent, user id or referrer is stored; a browser counts once per tab session (the page's sessionStorage).
CREATE TABLE merchants.storefront_visits (
  merchant_id text NOT NULL,
  day date NOT NULL,
  visits integer NOT NULL DEFAULT 0 CHECK (visits >= 0),
  PRIMARY KEY (merchant_id, day)
);

-- The provider-funded reward (design 02 "Provider-funded reward", V012 baseline table): one per business, switched on
-- and off from the Studio page; extra points on what `label` names through `ends_on` (the business's date — the
-- baseline's `ends_at` instant can't say "until Oct 1" in the business's time zone).
ALTER TABLE trust.merchant_rewards
  ADD COLUMN active boolean NOT NULL DEFAULT false,
  ADD COLUMN ends_on date,
  ADD COLUMN label text CHECK (label IS NULL OR char_length(label) <= 60),
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN updated_by text,
  ADD CONSTRAINT chk_merchant_rewards_multiplier CHECK (multiplier IS NULL OR multiplier IN (2, 3));
CREATE UNIQUE INDEX ux_merchant_rewards_merchant ON trust.merchant_rewards (merchant_id);
