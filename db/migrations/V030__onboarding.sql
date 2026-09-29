-- Onboarding / merchants workstream (range V030–V039). Additive only; see docs/DECISIONS.md › Onboarding + page builder.

-- Business onboarding progress and answers that have no baseline column.
ALTER TABLE merchants.merchants
  -- province the business operates in (Account step): AB · BC (pilot) · ON / QC (waitlist)
  ADD COLUMN province text CHECK (province IN ('AB', 'BC', 'ON', 'QC')),
  -- optional work email (Account step, existing customers)
  ADD COLUMN work_email text,
  -- per-type public profile answers from the Business step (years operating, team size, service area, cuisines …)
  ADD COLUMN profile jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- furthest onboarding step reached (resume point)
  ADD COLUMN onboarding_step text CHECK (onboarding_step IN ('account', 'business', 'verification', 'review', 'page', 'listings', 'done')),
  ADD COLUMN business_terms_accepted_at timestamptz,
  ADD COLUMN submitted_at timestamptz,
  ADD COLUMN approved_at timestamptz,
  -- identity.users id of the owner who started the application (logical ref)
  ADD COLUMN created_by text;

-- Verification checklist rows: which design check a row is (licence:AMVIC, gst, site_visit …) and its order.
ALTER TABLE merchants.verifications
  ADD COLUMN check_key text,
  ADD COLUMN position integer,
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
CREATE UNIQUE INDEX ux_verifications_check ON merchants.verifications(merchant_id, check_key) WHERE check_key IS NOT NULL;
CREATE INDEX ix_verifications_merchant_status ON merchants.verifications(merchant_id, status);
CREATE INDEX ix_verifications_expires ON merchants.verifications(expires_at) WHERE expires_at IS NOT NULL;

CREATE INDEX ix_merchant_categories_category ON merchants.merchant_categories(category_id);

-- Uploaded documents (legal documents, verification evidence, storefront logos). The bytes live behind the
-- DocumentStorage port (S3 in production, local disk under the `local`/`test` profiles); ids are the "media id"
-- that legal_details *_doc fields, verifications.document_media_id and storefronts.logo_media_id point at.
CREATE TABLE merchants.documents (
  id text PRIMARY KEY,
  merchant_id text NOT NULL REFERENCES merchants.merchants(id),
  purpose text NOT NULL CHECK (purpose IN ('legal', 'verification', 'logo')),
  file_name text NOT NULL,
  content_type text NOT NULL,
  size_bytes bigint NOT NULL CHECK (size_bytes > 0),
  storage_key text NOT NULL,
  uploaded_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_documents_merchant ON merchants.documents(merchant_id);

-- Storefront: CNAME verification state, timestamps, optimistic version; the V004 TODO unique indexes.
ALTER TABLE merchants.storefronts
  ADD COLUMN custom_domain_status text CHECK (custom_domain_status IN ('pending', 'verified', 'failed')),
  ADD COLUMN custom_domain_verified_at timestamptz,
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE merchants.storefronts
  ADD CONSTRAINT chk_domain_status CHECK ((custom_domain IS NULL) = (custom_domain_status IS NULL));
CREATE UNIQUE INDEX ux_storefront_slug ON merchants.storefronts(slug);
CREATE UNIQUE INDEX ux_storefront_merchant ON merchants.storefronts(merchant_id);
CREATE INDEX ix_storefront_sections_storefront ON merchants.storefront_sections(storefront_id);
