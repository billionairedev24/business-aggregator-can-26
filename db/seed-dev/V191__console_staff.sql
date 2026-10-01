-- Dev-only seed (profile `local`, S-90): a Northline staff member for the platform console — design 03's
-- "Priya N." — holding every console role, so the role switch and every screen can be tried locally. She signs in at
-- the console (http://localhost:3200/sign-in) with a backup code (priya-n-00001 … priya-n-00010, single use), or
-- without auth/bff with NL_DEV_USER=01J9ZD3V00000000000000PNA1 (web/apps/console/.env.example). README.md "Local sign-in".
INSERT INTO identity.users (id, phone, email, display_name, locale, mfa_primary, status, created_at, updated_at,
                            first_name, last_name, phone_verified_at, terms_version, terms_accepted_at) VALUES
  ('01J9ZD3V00000000000000PNA1', '+14035550123', 'priya.natarajan@example.com', 'Priya Natarajan', 'en-CA', 'sms', 'active',
   '2026-01-08T16:00:00Z', '2026-01-08T16:00:00Z', 'Priya', 'Natarajan', '2026-01-08T16:00:00Z', '3.0', '2026-01-08T16:00:00Z')
ON CONFLICT (id) DO NOTHING;

INSERT INTO identity.platform_roles (user_id, role, granted_at) VALUES
  ('01J9ZD3V00000000000000PNA1', 'staff', '2026-01-08T16:00:00Z'),
  ('01J9ZD3V00000000000000PNA1', 'admin', '2026-01-08T16:00:00Z'),
  ('01J9ZD3V00000000000000PNA1', 'trust_safety', '2026-01-08T16:00:00Z'),
  ('01J9ZD3V00000000000000PNA1', 'dispatch', '2026-01-08T16:00:00Z'),
  ('01J9ZD3V00000000000000PNA1', 'finance', '2026-01-08T16:00:00Z'),
  ('01J9ZD3V00000000000000PNA1', 'support', '2026-01-08T16:00:00Z'),
  ('01J9ZD3V00000000000000PNA1', 'analyst', '2026-01-08T16:00:00Z')
ON CONFLICT DO NOTHING;

-- Printed backup codes, stored as sha256('backup:' || lower(code without dashes)) like V101's.
INSERT INTO auth.backup_codes (id, user_id, code_hash, created_at) VALUES
  ('01J9ZD3V000000000000BPNA01', '01J9ZD3V00000000000000PNA1', 'de915892bc654dbb5279d3105ef55fa1c6543f554fb4a0ecb5bc12569cc52688', '2026-01-08T16:00:00Z'),
  ('01J9ZD3V000000000000BPNA02', '01J9ZD3V00000000000000PNA1', 'e128d14e198fc56cdb83209dacd7a7b00bb825185849906a1e8f98385b2a4eaf', '2026-01-08T16:00:00Z'),
  ('01J9ZD3V000000000000BPNA03', '01J9ZD3V00000000000000PNA1', 'fcffacc772c57e01a24fc8d69db0d48ded28988605d98ef7d1df29cd11328ec6', '2026-01-08T16:00:00Z'),
  ('01J9ZD3V000000000000BPNA04', '01J9ZD3V00000000000000PNA1', '5dc2de8031e97de8e9c55a51b43719d2ac54cf5210bf725844b97ce7a0b39444', '2026-01-08T16:00:00Z'),
  ('01J9ZD3V000000000000BPNA05', '01J9ZD3V00000000000000PNA1', '0fbb10cbe0ccce1a4b45c8e029c2e6abf94ca6489a4652317e79e38051bd2aed', '2026-01-08T16:00:00Z'),
  ('01J9ZD3V000000000000BPNA06', '01J9ZD3V00000000000000PNA1', 'ee9aea1bccb3f9155c18616671a73d9d0446c6b4ce2e41ae60bd8a0704b127b7', '2026-01-08T16:00:00Z'),
  ('01J9ZD3V000000000000BPNA07', '01J9ZD3V00000000000000PNA1', 'ce62cba95e038ebb7f968cdb4211c3ae4c4ad9f2968685efe95ec3bae3bfebb6', '2026-01-08T16:00:00Z'),
  ('01J9ZD3V000000000000BPNA08', '01J9ZD3V00000000000000PNA1', '8d46fa10c94ce6ef73d78bf52ae787f8072af9e8743a395322b42991712b5674', '2026-01-08T16:00:00Z'),
  ('01J9ZD3V000000000000BPNA09', '01J9ZD3V00000000000000PNA1', '2a169447f214a3e8208111de03ff658f0aa483ae383b0de32deef26ea5b1038f', '2026-01-08T16:00:00Z'),
  ('01J9ZD3V000000000000BPNA10', '01J9ZD3V00000000000000PNA1', '75ad7417f4cd98a39ef6228d2d52b136dfe0b495e4c43b94d90c8a208682f2e6', '2026-01-08T16:00:00Z')
ON CONFLICT (id) DO NOTHING;
