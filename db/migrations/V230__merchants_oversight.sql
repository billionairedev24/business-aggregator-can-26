-- Console batch 2 (range V230–V239). S-82: staff oversight of a business from the console's seller detail — suspend,
-- reinstate, require re-verification of a check, change tier, hide from search — each with the reason the business is
-- told, and the automatic consequences of the trust rules (S-93: below the rating floor → hidden from search; an
-- off-platform payment after a warning → suspended). Additive only. See docs/DECISIONS.md "S-82".
CREATE TABLE merchants.oversight_actions (
  id          text PRIMARY KEY,
  merchant_id text NOT NULL REFERENCES merchants.merchants (id),
  action      text NOT NULL CHECK (action IN ('suspended', 'reinstated', 'reverification_required', 'tier_changed',
                                               'search_hidden', 'search_restored')),
  -- told to the business (email) and kept with the action
  reason      text NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 1 AND 500),
  -- codes and ids only: {"from": "trusted", "to": "registered"} · {"verificationId": …, "checkType": "insurance"}
  detail      jsonb NOT NULL DEFAULT '{}'::jsonb,
  actor_id    text NOT NULL,                           -- logical ref → identity.users.id (staff), or 'system'
  actor_role  text NOT NULL,                           -- the console roles acted with
  at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_oversight_actions_merchant ON merchants.oversight_actions (merchant_id, at DESC);

-- Hidden from search (the business's page and existing customers still work): by staff, or by the rating floor rule
-- until the average recovers. Search reads it as a status other than active.
ALTER TABLE merchants.merchants
  ADD COLUMN search_hidden_at timestamptz,
  ADD COLUMN search_hidden_cause text CHECK (search_hidden_cause IN ('staff', 'rating_floor')),
  ADD CONSTRAINT chk_merchants_search_hidden CHECK ((search_hidden_at IS NULL) = (search_hidden_cause IS NULL));
CREATE INDEX ix_merchants_search_hidden ON merchants.merchants (search_hidden_cause) WHERE search_hidden_at IS NOT NULL;
