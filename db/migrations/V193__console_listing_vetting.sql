-- S-92 platform console listing vetting queue (range V190–V199, docs/CONSOLE_PLAN.md). Additive only.

-- Every reviewer decision on a listing (offer or service) in the console's vetting queue: approved (as if the
-- automated checks had passed) or rejected with reasons (emailed to the owners). The audit log has the same facts.
CREATE TABLE catalogue.vetting_decisions (
  id           text        PRIMARY KEY,
  listing_id   text        NOT NULL,
  kind         text        NOT NULL CHECK (kind IN ('product', 'service')),
  merchant_id  text        NOT NULL,
  decision     text        NOT NULL CHECK (decision IN ('approved', 'rejected')),
  -- why it was rejected: prohibited | misleading | pricing | licence | images | other
  reasons      text[]      NOT NULL DEFAULT '{}',
  -- the automated vetting flags the reviewer saw
  flags        text[]      NOT NULL DEFAULT '{}',
  note         text        CHECK (char_length(note) <= 500),
  decided_by   text        NOT NULL,
  role         text        NOT NULL,
  decided_at   timestamptz NOT NULL
);
CREATE INDEX ix_vetting_decisions_listing ON catalogue.vetting_decisions(listing_id, decided_at DESC);
CREATE INDEX ix_vetting_decisions_at ON catalogue.vetting_decisions(decided_at DESC);

-- The queue reads flagged pending listings oldest first.
CREATE INDEX IF NOT EXISTS ix_offers_vetting_pending ON catalogue.offers(submitted_at) WHERE vetting = 'pending';
CREATE INDEX IF NOT EXISTS ix_services_vetting_pending ON catalogue.services(submitted_at) WHERE vetting = 'pending';
