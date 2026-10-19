-- Real photos (owner 2026-10-19: "use real pictures"): a service's photos of the work, uploaded in the Studio's service
-- editor like a product's images (catalogue.media ids, main first). Additive. Approved services' photos are served
-- publicly (MediaAdapter.approved, S-123) — this partial GIN index serves that check (`own_images @> array[id]`).
ALTER TABLE catalogue.services
  ADD COLUMN own_images text[] NOT NULL DEFAULT '{}';
CREATE INDEX ix_services_approved_own_images ON catalogue.services USING gin (own_images) WHERE vetting = 'approved';
