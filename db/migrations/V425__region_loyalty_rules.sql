-- S-152 Loyalty points, provider-funded rewards and Plus (range V425–V434). Additive only.
-- See docs/DECISIONS.md "S-152" and docs/runbooks/loyalty.md.
--
-- The earning rules are region data: how many points a dollar of a completed sale earns, when points expire and what a
-- referral pays, per market or province, else the platform's default row (region_id NULL). Code never names a place:
-- it asks region.api.LoyaltyRules with the business's province and market (MerchantPlaces). The console edits the rows.
--
-- earn_points_per_dollar  points per whole dollar of the merchant's amount after discounts and points (tax, fees and
--                         tips never earn); 0 = nothing earns there
-- earn_kinds              which sales earn: goods (shop order lines), food (food orders), service (bookings)
-- expiry_months           points expire this many months after they were earned; NULL = never
-- referral_points         what each side of "Invite a neighbour" gets after the invitee's first completed order
-- referral_monthly_cap    rewarded referrals per referrer in a rolling 30 days (abuse limit)
-- referral_min_order_cents the invitee's first completed order must be at least this much to reward the referral
CREATE TABLE region.loyalty_rules (
  id                       text PRIMARY KEY,
  region_id                text UNIQUE REFERENCES region.regions (id),
  earn_points_per_dollar   integer NOT NULL CHECK (earn_points_per_dollar BETWEEN 0 AND 100),
  earn_kinds               text[]  NOT NULL DEFAULT '{goods,food,service}'
                           CHECK (earn_kinds <@ ARRAY['goods', 'food', 'service']::text[]),
  expiry_months            integer CHECK (expiry_months IS NULL OR expiry_months BETWEEN 1 AND 60),
  referral_points          integer NOT NULL DEFAULT 0 CHECK (referral_points BETWEEN 0 AND 100000),
  referral_monthly_cap     integer NOT NULL DEFAULT 5 CHECK (referral_monthly_cap BETWEEN 0 AND 100),
  referral_min_order_cents bigint  NOT NULL DEFAULT 2000 CHECK (referral_min_order_cents >= 0),
  updated_at               timestamptz NOT NULL DEFAULT now(),
  updated_by               text
);
CREATE UNIQUE INDEX ux_loyalty_rules_default ON region.loyalty_rules ((region_id IS NULL)) WHERE region_id IS NULL;
COMMENT ON TABLE region.loyalty_rules IS
  'S-152 points earning, expiry and referral rules per market or province; the row with region_id NULL is the default.';

-- The platform default (owner to confirm the numbers: docs/runbooks/loyalty.md § 2). 2 points per dollar at 100 points
-- = $1 is 2 % back; points expire 18 months after they were earned; a referral pays each side 1,000 points ($10).
INSERT INTO region.loyalty_rules (id, region_id, earn_points_per_dollar, expiry_months, referral_points,
                                  referral_monthly_cap, referral_min_order_cents)
VALUES ('loyalty-default', NULL, 2, 18, 1000, 5, 2000)
ON CONFLICT (id) DO NOTHING;
