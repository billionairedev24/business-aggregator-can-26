-- Storefront workstream (V080–V089) · S-31 custom domain verification and certificates. Additive; see docs/DECISIONS.md.
--
-- One custom domain per storefront (V004 custom_domain, unique since V016). Its lifecycle:
--   pending → verified (TXT _northline-verify.<domain> = token, and a CNAME/A that points at pages.<zone>)
--           → issuing (the edge reconciler wrote a Gateway listener + cert-manager Certificate) → live
--   failed (the certificate could not be issued) · expired (not verified within 7 days); both can be re-checked.
-- A proven domain that stops pointing at us keeps serving for a grace period (dns_lost_at), then returns to pending.

-- V030's CHECK lists pending | verified | failed; widen it with the new states.
ALTER TABLE merchants.storefronts DROP CONSTRAINT IF EXISTS storefronts_custom_domain_status_check;
ALTER TABLE merchants.storefronts
  ADD CONSTRAINT storefronts_custom_domain_status_check
      CHECK (custom_domain_status IN ('pending', 'verified', 'issuing', 'live', 'failed', 'expired')),
  ADD COLUMN custom_domain_token text,                   -- value of the TXT record _northline-verify.<domain>
  ADD COLUMN custom_domain_status_at timestamptz,        -- when the current status was entered
  ADD COLUMN custom_domain_checked_at timestamptz,       -- last DNS check
  ADD COLUMN custom_domain_next_check_at timestamptz,    -- NULL = checked only on request (expired, failed too often)
  ADD COLUMN custom_domain_problem text CHECK (custom_domain_problem IN
      ('txt_missing', 'txt_mismatch', 'no_record', 'not_pointing', 'dns_error', 'certificate', 'capacity',
       'rate_limited')),
  ADD COLUMN custom_domain_dns_lost_at timestamptz,      -- a proven domain stopped pointing at us (grace runs from here)
  ADD COLUMN custom_domain_live_at timestamptz,
  ADD COLUMN custom_domain_requested_at timestamptz,     -- last certificate request (Let's Encrypt rate limits)
  ADD COLUMN custom_domain_failures integer NOT NULL DEFAULT 0;   -- certificate failures in a row

-- Domains saved before S-31 (dev databases): a fresh token and an immediate check.
UPDATE merchants.storefronts
   SET custom_domain_token = 'nl-' || replace(gen_random_uuid()::text, '-', ''),
       custom_domain_status = CASE custom_domain_status WHEN 'verified' THEN 'pending' ELSE custom_domain_status END,
       custom_domain_verified_at = NULL,
       custom_domain_status_at = updated_at,
       custom_domain_next_check_at = now()
 WHERE custom_domain IS NOT NULL;

ALTER TABLE merchants.storefronts
  ADD CONSTRAINT chk_domain_token CHECK ((custom_domain IS NULL) = (custom_domain_token IS NULL)),
  ADD CONSTRAINT chk_domain_status_at CHECK ((custom_domain IS NULL) = (custom_domain_status_at IS NULL));

-- The DNS check job claims due rows; the edge reconciler and the by-host lookup read proven ones.
CREATE INDEX ix_storefront_domain_due ON merchants.storefronts (custom_domain_next_check_at)
  WHERE custom_domain IS NOT NULL AND custom_domain_next_check_at IS NOT NULL;
CREATE INDEX ix_storefront_domain_proven ON merchants.storefronts (custom_domain_status)
  WHERE custom_domain_status IN ('verified', 'issuing', 'live');
