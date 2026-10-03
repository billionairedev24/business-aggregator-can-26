-- S-120 pilot merchant onboarding (range V320–V324, above main's V319). Additive only. See docs/DECISIONS.md "S-120".
--
-- A pilot business is a row staff create for a market before the business exists on Northline (an invite) or for a
-- business that already does (enrolment). Its pipeline stage is NOT stored: the console derives it from the business's
-- own state (onboarding, checklist, Stripe, kitchen visit, catalogue, approval, storefront). What is stored is what no
-- other table knows: the market it pilots in, who at Northline looks after it, the blocker staff wrote down, notes.
CREATE TABLE merchants.pilot_businesses (
  id            text PRIMARY KEY,
  market_id     text NOT NULL,                                   -- logical ref → region.regions.id (kind = market)
  business_type text NOT NULL CHECK (business_type IN ('provider', 'seller', 'kitchen', 'both')),
  -- the working name staff use until the business names itself in the Business step
  label         text NOT NULL CHECK (char_length(btrim(label)) BETWEEN 1 AND 80),
  merchant_id   text UNIQUE REFERENCES merchants.merchants (id), -- set when the invite is accepted or on enrolment
  owner_id      text,                                            -- the staff member who looks after it (identity.users)
  blocker       text CHECK (char_length(btrim(blocker)) BETWEEN 1 AND 300),
  blocker_owner text CHECK (blocker_owner IN ('business', 'northline', 'stripe', 'inspector')),
  blocker_since timestamptz,
  created_by    text NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT chk_pilot_blocker CHECK ((blocker IS NULL) = (blocker_owner IS NULL)
                                      AND (blocker IS NULL) = (blocker_since IS NULL))
);
CREATE INDEX ix_pilot_businesses_market ON merchants.pilot_businesses (market_id, created_at);

-- Signed-up links: only the SHA-256 of the token is kept. One pending invite per pilot business (a new one revokes the
-- last). The address is the business contact the staff member was given; it is not on any event.
CREATE TABLE merchants.pilot_invites (
  id          text PRIMARY KEY,
  pilot_id    text NOT NULL REFERENCES merchants.pilot_businesses (id),
  token_hash  text NOT NULL UNIQUE,
  email       citext NOT NULL CHECK (char_length(email) <= 254),
  sent_by     text NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  expires_at  timestamptz NOT NULL,
  accepted_at timestamptz,
  accepted_by text,
  revoked_at  timestamptz,
  CONSTRAINT chk_pilot_invite_accepted CHECK ((accepted_at IS NULL) = (accepted_by IS NULL))
);
CREATE INDEX ix_pilot_invites_pilot ON merchants.pilot_invites (pilot_id, created_at DESC);

CREATE TABLE merchants.pilot_notes (
  id         text PRIMARY KEY,
  pilot_id   text NOT NULL REFERENCES merchants.pilot_businesses (id),
  author_id  text NOT NULL,
  body       text NOT NULL CHECK (char_length(btrim(body)) BETWEEN 1 AND 2000),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_pilot_notes_pilot ON merchants.pilot_notes (pilot_id, created_at DESC);

-- A pilot business in a market that is not live yet is hidden from search (the pre-launch state): cause 'pilot', lifted
-- for the market's pilot businesses when the market goes live (console switchboard).
ALTER TABLE merchants.merchants DROP CONSTRAINT merchants_search_hidden_cause_check;
ALTER TABLE merchants.merchants
  ADD CONSTRAINT merchants_search_hidden_cause_check CHECK (search_hidden_cause IN ('staff', 'rating_floor', 'pilot'));
