-- Console batch 2 (range V230–V239). S-82: staff oversight of a business from the console's seller detail — suspend,
-- reinstate, require re-verification of a check, change tier — each with the reason the business is told. Additive
-- only. See docs/DECISIONS.md "S-82".
CREATE TABLE merchants.oversight_actions (
  id          text PRIMARY KEY,
  merchant_id text NOT NULL REFERENCES merchants.merchants (id),
  action      text NOT NULL CHECK (action IN ('suspended', 'reinstated', 'reverification_required', 'tier_changed')),
  -- told to the business (email) and kept with the action
  reason      text NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 1 AND 500),
  -- codes and ids only: {"from": "trusted", "to": "registered"} · {"verificationId": …, "checkType": "insurance"}
  detail      jsonb NOT NULL DEFAULT '{}'::jsonb,
  actor_id    text NOT NULL,                           -- logical ref → identity.users.id (staff)
  actor_role  text NOT NULL,                           -- the console roles acted with
  at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_oversight_actions_merchant ON merchants.oversight_actions (merchant_id, at DESC);
