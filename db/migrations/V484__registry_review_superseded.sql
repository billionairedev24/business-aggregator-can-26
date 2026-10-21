-- S-158: the console's registry check queue can re-run a lookup. The review it replaces is closed as 'superseded'
-- (the new lookup is the evidence now: matched → the row is verified, still unmatched → a new open review).
-- Widens the S-23 CHECK; no value is renamed.
ALTER TABLE merchants.registry_checks DROP CONSTRAINT IF EXISTS registry_checks_review_state_check;
ALTER TABLE merchants.registry_checks
  ADD CONSTRAINT registry_checks_review_state_check
  CHECK (review_state IN ('open', 'approved', 'rejected', 'superseded'));
