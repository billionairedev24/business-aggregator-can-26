-- Backend foundation (range V018–V019). See docs/DECISIONS.md 2026-09-29.

-- Studio header / "Switch business" show "<name> · <city>". The spec has no city on merchants; the business
-- address only lives inside legal_details (free text). Onboarding fills this from the business address.
ALTER TABLE merchants.merchants ADD COLUMN city text;

-- Team roles (DATA_MODEL.md: "Staff with roles (owner, technician, bookkeeper, cook)"). Drives MerchantAccess.
ALTER TABLE merchants.merchant_members
  ALTER COLUMN role SET NOT NULL,
  ADD CONSTRAINT chk_member_role CHECK (role IN ('owner', 'technician', 'bookkeeper', 'cook'));
-- "businesses I belong to" lookup (GET /api/v1/me/businesses) and dev-auth membership loading.
CREATE INDEX ix_merchant_members_user ON merchants.merchant_members(user_id);

-- Spring Modulith 2.x JDBC registry indexes (from its schema-postgresql.sql; V017 only created the tables).
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx
  ON events.event_publication USING hash(serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx
  ON events.event_publication (completion_date);
CREATE INDEX IF NOT EXISTS event_publication_archive_serialized_event_hash_idx
  ON events.event_publication_archive USING hash(serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_archive_by_completion_date_idx
  ON events.event_publication_archive (completion_date);
