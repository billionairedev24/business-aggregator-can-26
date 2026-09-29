-- Dev-only seed (profile `local`): settings & compliance workstream — Settings (business, API keys, webhook, audit
-- log) and Stripe & compliance (Connect accounts, tax by jurisdiction, licences / insurance / policies, obligations)
-- for the V100 personas, from design/02 Provider Studio.dc.html. Dates that drive states (WCB expired 8 days ago →
-- instant book pauses in 7 days; the AHS permit renewing in 20 days; API keys used minutes ago) are relative to the day
-- the seed runs. Runs after V102 (onboarding), whose verification rows it labels.

-- ── Settings › Business ─────────────────────────────────────────────────────────────────────────────────────────────
UPDATE merchants.merchants SET cancellation_policy = '12h', auto_accept_quote_cents = 15000, languages = '{en,pa}',
       stripe_account_id = 'acct_1Kx9PWM0000000Q2'
 WHERE id = '01J9ZD3V00000000000000PWM1';
UPDATE merchants.merchants SET cancellation_policy = '24h', stripe_account_id = 'acct_1Kx9PWP0000000R7'
 WHERE id = '01J9ZD3V00000000000000PWP1';
UPDATE merchants.merchants SET cancellation_policy = 'flexible', stripe_account_id = 'acct_1Kx9PDB0000000D4'
 WHERE id = '01J9ZD3V00000000000000PDB1';

UPDATE merchants.merchant_members SET joined_at = '2026-01-05T17:00:00Z' WHERE user_id = '01J9ZD3V00000000000000RAV1';
UPDATE merchants.merchant_members SET joined_at = '2026-01-12T17:00:00Z', invited_by = '01J9ZD3V00000000000000RAV1'
 WHERE user_id IN ('01J9ZD3V000000000000000JAS', '01J9ZD3V00000000000000PR1Y');

-- ── Stripe & compliance › Licences, insurance & policies ────────────────────────────────────────────────────────────
-- Prairie Wrench (design complianceDocs): onboarding's AMVIC + insurance rows get their display names; Red Seal, WCB,
-- GST and the privacy acknowledgement are post-onboarding rows (no checklist key).
UPDATE merchants.verifications SET label = 'AMVIC business licence 44812', expires_at = '2027-01-31T07:00:00Z',
       verified_at = '2026-01-07T18:00:00Z'
 WHERE id = '01J9ZD3V0000000000000VPW03';
UPDATE merchants.verifications SET label = 'Liability insurance $2M · Intact', verified_at = '2026-01-07T18:00:00Z'
 WHERE id = '01J9ZD3V0000000000000VPW04';

INSERT INTO merchants.verifications
  (id, merchant_id, check_type, registry, reference, label, status, expires_at, verified_at, verified_by, position, created_at, updated_at) VALUES
  ('01J9ZD3V0000000000000VSC01', '01J9ZD3V00000000000000PWM1', 'licence',     'Red Seal',    'Automotive Service Technician', 'Red Seal · Automotive Service Technician', 'verified', NULL, '2026-01-07T18:00:00Z', 'agent', 10, '2026-01-07T18:00:00Z', '2026-01-07T18:00:00Z'),
  ('01J9ZD3V0000000000000VSC02', '01J9ZD3V00000000000000PWM1', 'wcb',         'WCB Alberta', 'clearance',                     'WCB Alberta clearance',                    'verified', now() - interval '8 days', '2025-09-02T18:00:00Z', 'agent', 11, '2026-01-07T18:00:00Z', '2026-01-07T18:00:00Z'),
  ('01J9ZD3V0000000000000VSC03', '01J9ZD3V00000000000000PWM1', 'registry',    'CRA',         '784512369 RT0001',              'GST/HST registration RT0001',              'verified', NULL, '2026-01-07T18:00:00Z', 'registry', 12, '2026-01-07T18:00:00Z', '2026-01-07T18:00:00Z'),
  ('01J9ZD3V0000000000000VSC04', '01J9ZD3V00000000000000PWM1', 'attestation', 'PIPEDA',      'signed',                        'Privacy acknowledgement (PIPEDA / Alberta PIPA)', 'verified', NULL, '2026-03-14T17:00:00Z', 'owner', 13, '2026-03-14T17:00:00Z', '2026-03-14T17:00:00Z')
ON CONFLICT (id) DO NOTHING;

-- Prairie Wrench Parts: onboarding rows get names; the privacy acknowledgement is added.
UPDATE merchants.verifications SET label = 'GST/HST registration RT0001', verified_at = '2026-01-07T18:05:00Z' WHERE id = '01J9ZD3V0000000000000VPP03';
UPDATE merchants.verifications SET label = 'Category permits · none required', verified_at = '2026-01-07T18:05:00Z' WHERE id = '01J9ZD3V0000000000000VPP04';
UPDATE merchants.verifications SET label = 'Product safety attestation', verified_at = '2026-01-07T18:05:00Z' WHERE id = '01J9ZD3V0000000000000VPP05';
UPDATE merchants.verifications SET label = 'Returns policy · standard', verified_at = '2026-01-07T18:05:00Z' WHERE id = '01J9ZD3V0000000000000VPP06';
INSERT INTO merchants.verifications
  (id, merchant_id, check_type, registry, reference, label, status, verified_at, verified_by, position, created_at, updated_at) VALUES
  ('01J9ZD3V0000000000000VSC05', '01J9ZD3V00000000000000PWP1', 'attestation', 'PIPEDA', 'signed', 'Privacy acknowledgement (PIPEDA / Alberta PIPA)', 'verified', '2026-03-14T17:00:00Z', 'owner', 10, '2026-03-14T17:00:00Z', '2026-03-14T17:00:00Z')
ON CONFLICT (id) DO NOTHING;

-- Pho Dau Bo (kitchen): the AHS food permit renews in 20 days → sidebar badge "AHS".
UPDATE merchants.verifications SET label = 'AHS food permit · #FS-2024-88120', expires_at = now() + interval '20 days',
       verified_at = '2026-01-09T18:00:00Z'
 WHERE id = '01J9ZD3V0000000000000VPD03';
UPDATE merchants.verifications SET label = 'Food handler certificates · 3 staff', verified_at = '2026-01-09T18:00:00Z' WHERE id = '01J9ZD3V0000000000000VPD04';
UPDATE merchants.verifications SET label = 'AHS kitchen inspection · Aug 2026', verified_at = '2026-08-20T18:00:00Z' WHERE id = '01J9ZD3V0000000000000VPD05';
UPDATE merchants.verifications SET label = 'Liability insurance $2M', verified_at = '2026-01-09T18:00:00Z' WHERE id = '01J9ZD3V0000000000000VPD06';
UPDATE merchants.verifications SET label = 'Allergen attestation', verified_at = '2026-01-09T18:00:00Z' WHERE id = '01J9ZD3V0000000000000VPD07';
UPDATE merchants.verifications SET label = 'AGLC liquor licence · not applicable', verified_at = '2026-01-09T18:00:00Z' WHERE id = '01J9ZD3V0000000000000VPD08';
UPDATE merchants.verifications SET label = 'GST/HST registration RT0001', verified_at = '2026-01-09T18:00:00Z' WHERE id = '01J9ZD3V0000000000000VPD09';

-- "Platform obligations you've accepted" — v2.3 on Mar 14 2026.
INSERT INTO merchants.obligation_acceptances (merchant_id, version, accepted_by, accepted_at) VALUES
  ('01J9ZD3V00000000000000PWM1', '2.3', '01J9ZD3V00000000000000RAV1', '2026-03-14T17:00:00Z'),
  ('01J9ZD3V00000000000000PWP1', '2.3', '01J9ZD3V00000000000000RAV1', '2026-03-14T17:05:00Z'),
  ('01J9ZD3V00000000000000PDB1', '2.3', '01J9ZD3V00000000000000RAV1', '2026-03-14T17:10:00Z')
ON CONFLICT DO NOTHING;

-- ── Tax · Stripe Tax + marketplace facilitator (current quarter) ────────────────────────────────────────────────────
INSERT INTO payments.tax_jurisdiction_totals (merchant_id, period, jurisdiction, collected_cents, handling)
SELECT m, to_char(now() AT TIME ZONE 'America/Edmonton', 'YYYY-"Q"Q'), j, c, h
  FROM (VALUES
    ('01J9ZD3V00000000000000PWM1', 'ab_gst',           189240, 'remitted_by_northline'),
    ('01J9ZD3V00000000000000PWM1', 'bc_gst_pst',            0, 'not_selling'),
    ('01J9ZD3V00000000000000PWM1', 'platform_fee_gst',   6140, 'charged_on_invoice'),
    ('01J9ZD3V00000000000000PWP1', 'ab_gst',            84310, 'remitted_by_northline'),
    ('01J9ZD3V00000000000000PWP1', 'bc_gst_pst',            0, 'not_selling'),
    ('01J9ZD3V00000000000000PWP1', 'platform_fee_gst',   3920, 'charged_on_invoice'),
    ('01J9ZD3V00000000000000PDB1', 'ab_gst',            61280, 'remitted_by_northline'),
    ('01J9ZD3V00000000000000PDB1', 'platform_fee_gst',   2690, 'charged_on_invoice')
  ) AS t(m, j, c, h)
ON CONFLICT DO NOTHING;

-- ── Settings › API & integrations (design apiKeys + webhook) ────────────────────────────────────────────────────────
-- The secrets are unknown (only hashes are stored); issue a new key to try one. The webhook has no signing secret
-- in the seed — "Rotate secret" creates one.
INSERT INTO developer.api_keys (id, merchant_id, name, scopes, key_hash, prefix, last_used_at, rate_limit, created_by, created_at) VALUES
  ('01J9ZD3V0000000000000KEY01', '01J9ZD3V00000000000000PWM1', 'Website embed',   '{storefront:read,booking:write}',
   sha256('nl_live_seed-website-embed'::bytea), 'nl_live_Wb7q', now() - interval '2 minutes', 600, '01J9ZD3V00000000000000RAV1', '2026-02-02T17:00:00Z'),
  ('01J9ZD3V0000000000000KEY02', '01J9ZD3V00000000000000PWM1', 'QuickBooks sync', '{payouts:read,orders:read}',
   sha256('nl_live_seed-quickbooks-sync'::bytea), 'nl_live_Qb3m', now() - interval '1 hour', 600, '01J9ZD3V00000000000000RAV1', '2026-02-10T17:00:00Z')
ON CONFLICT (id) DO NOTHING;

INSERT INTO developer.webhook_endpoints (id, merchant_id, url, secret_ref, events, active, created_by, created_at) VALUES
  ('01J9ZD3V0000000000000WHK01', '01J9ZD3V00000000000000PWM1', 'https://prairiewrench.ca/hooks/northline', NULL,
   '{booking.confirmed,booking.completed,payment.released,review.created}', true, '01J9ZD3V00000000000000RAV1', '2026-02-10T17:10:00Z')
ON CONFLICT (id) DO NOTHING;
INSERT INTO developer.webhook_deliveries (id, endpoint_id, event_id, attempt, status_code, at) VALUES
  ('01J9ZD3V0000000000000WHD01', '01J9ZD3V0000000000000WHK01', '01J9ZD3V0000000000000EVT01', 1, 200, now() - interval '25 minutes')
ON CONFLICT (id) DO NOTHING;

-- ── Settings › Security › Audit log ─────────────────────────────────────────────────────────────────────────────────
INSERT INTO developer.audit_log (id, merchant_id, actor_id, role, action, target_type, target_id, after, at) VALUES
  ('01J9ZD3V0000000000000AUD01', '01J9ZD3V00000000000000PWM1', '01J9ZD3V00000000000000RAV1', 'owner', 'api_key.issued', 'api_key', '01J9ZD3V0000000000000KEY02', '{"scopes":["payouts:read","orders:read"]}', now() - interval '12 days'),
  ('01J9ZD3V0000000000000AUD02', '01J9ZD3V00000000000000PWM1', '01J9ZD3V00000000000000RAV1', 'owner', 'webhook.created', 'webhook_endpoint', '01J9ZD3V0000000000000WHK01', NULL, now() - interval '12 days'),
  ('01J9ZD3V0000000000000AUD03', '01J9ZD3V00000000000000PWM1', '01J9ZD3V00000000000000RAV1', 'owner', 'business.updated', 'merchant', '01J9ZD3V00000000000000PWM1', '{"fields":["serviceArea"]}', now() - interval '3 days')
ON CONFLICT (id) DO NOTHING;
