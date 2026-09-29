-- Settings & compliance workstream (range V080–V089). Stripe & compliance › Licences, insurance & policies.
-- merchants.verifications is shared with onboarding: onboarding creates rows during onboarding, this workstream owns
-- renewals afterwards (upload → submitted → verified by an agent in the console).

ALTER TABLE merchants.verifications
  ADD COLUMN label        text,                               -- as shown: "Liability insurance $2M · Intact"
  ADD COLUMN verified_at  timestamptz,                        -- "Signed Mar 2026"
  ADD COLUMN submitted_at timestamptz,                        -- renewal handed in (awaiting an agent)
  ADD COLUMN submitted_by text;
-- Uploaded renewal documents are merchants.documents rows (purpose 'verification', onboarding's V030 table);
-- verifications.document_media_id points at the latest one.

-- "Platform obligations you've accepted" — versioned agreements, one row per accepted version.
CREATE TABLE merchants.obligation_acceptances (
  merchant_id  text        NOT NULL REFERENCES merchants.merchants(id),
  version      text        NOT NULL CHECK (version ~ '^\d+\.\d+$'),
  accepted_by  text        NOT NULL,
  accepted_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (merchant_id, version)
);
