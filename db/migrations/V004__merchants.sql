-- schema: merchants · owner: Java (java)
-- Generated from the Northline spec (docs/DATA_MODEL.md). Hand-edit freely; keep column names.
CREATE SCHEMA IF NOT EXISTS merchants;

-- Provider, seller or kitchen; one row per business.
CREATE TABLE merchants.merchants (
  -- PK
  id text PRIMARY KEY,
  -- provider | seller | kitchen | both
  type text CHECK (type IN ('provider', 'seller', 'kitchen', 'both')),
  -- not null · 2–80 chars
  display_name text NOT NULL,
  -- not null
  legal_name text NOT NULL,
  -- sole | partnership | corp_ab | corp_fed | corp_ex | coop | nonprofit
  structure text CHECK (structure IN ('sole', 'partnership', 'corp_ab', 'corp_fed', 'corp_ex', 'coop', 'nonprofit')),
  -- per-structure fields · JSON-schema validated by structure
  legal_details jsonb,
  -- CRA BN · 9 digits
  business_number text,
  -- check ^\d{9}RT\d{4}$ · required unless sole/partnership
  gst_number text,
  -- corporate access # / registration #
  registry_ref text,
  -- AB | CA | BC | …
  registry_jurisdiction text,
  -- registered | trusted | master
  tier text CHECK (tier IN ('registered', 'trusted', 'master')),
  -- 1500 → 900
  take_rate_bps integer,
  quality_score integer,
  -- acct_…
  stripe_account_id text,
  -- applicant | pending | active | paused | suspended
  status text CHECK (status IN ('applicant', 'pending', 'active', 'paused', 'suspended')),
  region_id text,
  languages text[],
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
-- TODO indexes/constraints: index(type,status) · index(tier) · unique(business_number) where not null
-- outbox events: merchant.approved · merchant.tier_changed · merchant.suspended
-- search projection: merchants index · denormalised onto listings

-- Owners, partners, directors, board members — whoever the structure requires (FINTRAC ≥ 25 % beneficial owners).
CREATE TABLE merchants.merchant_principals (
  -- PK
  id text PRIMARY KEY,
  -- FK
  merchant_id text,
  -- not null
  legal_name text NOT NULL,
  -- owner | partner | partner_signing | director | officer | shareholder | chair | president | treasurer | secretary
  role text CHECK (role IN ('owner', 'partner', 'partner_signing', 'director', 'officer', 'shareholder', 'chair', 'president', 'treasurer', 'secretary')),
  -- check 0–100 · sum ≤ 100 per merchant
  ownership_pct numeric(5,2),
  -- FK verifications
  kyc_verification_id text,
  -- FK identity.users · nullable
  user_id text
);
-- TODO indexes/constraints: index(merchant_id) · check(role in allowed set for merchants.structure)
-- outbox events: principal.added · principal.kyc_passed

-- Services / departments a merchant is approved to list in; limit per type enforced here, not just in UI.
CREATE TABLE merchants.merchant_categories (
  -- FK
  merchant_id text,
  -- FK catalogue.categories
  category_id text,
  -- requested | approved | rejected · regulated categories start requested
  status text CHECK (status IN ('requested', 'approved', 'rejected')),
  -- free text when no category matched
  suggested_name text,
  -- FK verifications · nullable
  registry_verification_id text,
  PRIMARY KEY (merchant_id,category_id)
);
-- TODO indexes/constraints: PK(merchant_id,category_id) · trigger: count ≤ limit(type) → provider 10 · seller 5 · both 10 · kitchen 3
-- outbox events: merchant.categories_changed → re-run registry checks
-- search projection: facets

-- Staff with roles (owner, technician, bookkeeper, cook).
CREATE TABLE merchants.merchant_members (
  -- FK
  merchant_id text,
  -- FK identity.users
  user_id text,
  role text,
  bookable boolean,
  mfa_ok boolean,
  PRIMARY KEY (merchant_id,user_id)
);
-- TODO indexes/constraints: PK(merchant_id,user_id)

-- Every check: KYC, registry, permit, insurance, inspection, attestation.
CREATE TABLE merchants.verifications (
  -- PK
  id text PRIMARY KEY,
  -- FK
  merchant_id text,
  -- kyc | registry | licence | ahs_permit | food_cert | inspection | insurance | wcb | attestation | bank | mfa | site_visit
  check_type text CHECK (check_type IN ('kyc', 'registry', 'licence', 'ahs_permit', 'food_cert', 'inspection', 'insurance', 'wcb', 'attestation', 'bank', 'mfa', 'site_visit')),
  -- AMVIC, RECA, AHS…
  registry text,
  reference text,
  -- todo | submitted | verified | expired | rejected
  status text CHECK (status IN ('todo', 'submitted', 'verified', 'expired', 'rejected')),
  document_media_id text,
  -- agent
  verified_by text,
  expires_at timestamptz,
  rechecked_at timestamptz
);
-- TODO indexes/constraints: index(merchant_id,status) · index(expires_at) for 30-day reminders
-- outbox events: verification.passed · verification.expired (pauses instant book / ordering)

-- Business page / store / menu page: brand, ordered sections, slug, custom domain.
CREATE TABLE merchants.storefronts (
  -- PK
  id text PRIMARY KEY,
  -- FK unique
  merchant_id text,
  -- unique · ^[a-z0-9-]{3,40}$
  slug text,
  -- business_page | store | menu_page · derived from merchants.type
  page_kind text CHECK (page_kind IN ('business_page', 'store', 'menu_page')),
  -- hex · must pass 4.5:1 with white
  brand_color text,
  logo_media_id text,
  -- ≤ 80 chars
  tagline_i18n jsonb,
  -- book_visit | request_quote | order_now | reserve
  cta_label text CHECK (cta_label IN ('book_visit', 'request_quote', 'order_now', 'reserve')),
  announcement_i18n jsonb,
  -- CNAME → pages.northline.ca · verified_at
  custom_domain text,
  published_at timestamptz
);
-- TODO indexes/constraints: unique(slug) · unique(custom_domain)
-- outbox events: storefront.published

-- Ordered, toggleable sections of a page. Content is derived from other tables, never duplicated here.
CREATE TABLE merchants.storefront_sections (
  -- PK
  id text PRIMARY KEY,
  -- FK
  storefront_id text,
  -- hero | cta | about | services | reviews | area | gallery | faq | featured | catalogue | delivery | policies | menu | hours | fulfil | permit
  kind text CHECK (kind IN ('hero', 'cta', 'about', 'services', 'reviews', 'area', 'gallery', 'faq', 'featured', 'catalogue', 'delivery', 'policies', 'menu', 'hours', 'fulfil', 'permit')),
  -- 0-based · unique per storefront
  position integer,
  -- hero and cta are always true (check)
  enabled boolean,
  -- kind-specific · e.g. featured offer_ids, faq pairs, gallery media_ids
  settings jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
-- TODO indexes/constraints: unique(storefront_id,position) · check(kind allowed for page_kind)
-- outbox events: storefront.sections_changed → CDN purge

-- Zones a merchant serves.
CREATE TABLE merchants.service_areas (
  merchant_id text,
  zone_id text,
  PRIMARY KEY (merchant_id,zone_id)
);
-- TODO indexes/constraints: PK(merchant_id,zone_id)
-- search projection: listings.zone_ids

-- Foreign keys (in-module only)
-- logical ref (cross-module, no FK): merchants.merchants.region_id → region.regions.id
ALTER TABLE merchants.merchant_principals ADD CONSTRAINT fk_merchant_principals_merchant_id FOREIGN KEY (merchant_id) REFERENCES merchants.merchants(id);
ALTER TABLE merchants.merchant_principals ADD CONSTRAINT fk_merchant_principals_kyc_verification_id FOREIGN KEY (kyc_verification_id) REFERENCES merchants.verifications(id);
ALTER TABLE merchants.merchant_categories ADD CONSTRAINT fk_merchant_categories_merchant_id FOREIGN KEY (merchant_id) REFERENCES merchants.merchants(id);
-- logical ref (cross-module, no FK): merchants.merchant_categories.category_id → catalogue.categories.id
ALTER TABLE merchants.merchant_members ADD CONSTRAINT fk_merchant_members_merchant_id FOREIGN KEY (merchant_id) REFERENCES merchants.merchants(id);
-- logical ref (cross-module, no FK): merchants.merchant_members.user_id → identity.users.id
ALTER TABLE merchants.verifications ADD CONSTRAINT fk_verifications_merchant_id FOREIGN KEY (merchant_id) REFERENCES merchants.merchants(id);
ALTER TABLE merchants.storefronts ADD CONSTRAINT fk_storefronts_merchant_id FOREIGN KEY (merchant_id) REFERENCES merchants.merchants(id);
ALTER TABLE merchants.storefront_sections ADD CONSTRAINT fk_storefront_sections_storefront_id FOREIGN KEY (storefront_id) REFERENCES merchants.storefronts(id);
ALTER TABLE merchants.service_areas ADD CONSTRAINT fk_service_areas_merchant_id FOREIGN KEY (merchant_id) REFERENCES merchants.merchants(id);
-- logical ref (cross-module, no FK): merchants.service_areas.zone_id → region.zones.id
