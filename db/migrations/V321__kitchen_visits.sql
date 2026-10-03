-- S-120: kitchen visits. Onboarding's `site_visit` check (the owner books a slot, V030) gets the visit itself: who goes,
-- when, the checklist and photos, and the outcome. A passed visit verifies the check; approval of a kitchen waits for it
-- where the region or one of the kitchen's categories requires a visit. See docs/DECISIONS.md "S-120".
CREATE TABLE merchants.kitchen_visits (
  id              text PRIMARY KEY,
  merchant_id     text NOT NULL REFERENCES merchants.merchants (id),
  scheduled_at    timestamptz NOT NULL,
  inspector_id    text,                                          -- a Northline staff member (identity.users)
  inspector_name  text CHECK (char_length(btrim(inspector_name)) BETWEEN 1 AND 80), -- or someone outside Northline
  status          text NOT NULL DEFAULT 'scheduled' CHECK (status IN ('scheduled', 'passed', 'failed', 'cancelled')),
  -- item code → pass | fail | na (codes in merchants.domain.KitchenVisit.Item)
  checklist       jsonb NOT NULL DEFAULT '{}'::jsonb,
  note            text CHECK (char_length(note) <= 1000),
  -- merchants.documents ids (purpose verification): photos taken on the visit, stored through the storage port
  photo_ids       text[] NOT NULL DEFAULT '{}',
  scheduled_by    text NOT NULL,
  recorded_by     text,
  recorded_at     timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT chk_kitchen_visit_inspector CHECK (inspector_id IS NOT NULL OR inspector_name IS NOT NULL),
  CONSTRAINT chk_kitchen_visit_outcome CHECK ((status IN ('passed', 'failed')) = (recorded_at IS NOT NULL)
                                              AND (recorded_at IS NULL) = (recorded_by IS NULL))
);
CREATE INDEX ix_kitchen_visits_merchant ON merchants.kitchen_visits (merchant_id, scheduled_at DESC);
CREATE INDEX ix_kitchen_visits_open ON merchants.kitchen_visits (scheduled_at) WHERE status = 'scheduled';

-- The rule, as region data: 'required' = a kitchen there is approved only after a passed visit; 'optional' = the booked
-- slot is confirmed with the approval (the behaviour before S-120). A market's NULL = its province's; a province's NULL =
-- optional. Nothing is required by default: turning it on is a decision per place (docs/runbooks/pilot-onboarding.md).
ALTER TABLE region.regions
  ADD COLUMN kitchen_visit text CHECK (kitchen_visit IN ('required', 'optional'));

-- ... and per category: a kitchen holding a category that needs a visit is visited wherever it is.
ALTER TABLE catalogue.categories
  ADD COLUMN site_visit_required boolean NOT NULL DEFAULT false;
