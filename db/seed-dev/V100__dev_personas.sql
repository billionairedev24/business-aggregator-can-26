-- Dev-only seed (Flyway location classpath:db/seed-dev, active only under the `local` profile).
-- Personas from design/02 Provider Studio.dc.html: owner Ravi Sandhu, technician Jas Gill, bookkeeper Priya Sandhu,
-- and the "Switch business" list (bizList): Prairie Wrench (Provider · Master), Prairie Wrench Parts (Seller · Trusted),
-- Pho Dau Bo (Kitchen · Trusted). Ids are fixed so developers can use them with the X-Dev-User header.
-- Feature teams: add your own seed rows as V101–V109 in this folder.

INSERT INTO identity.users (id, phone, email, display_name, locale, mfa_primary, status, created_at, updated_at) VALUES
  ('01J9ZD3V00000000000000RAV1', '+14035550148', 'ravi.sandhu@example.com',  'Ravi Sandhu',  'en-CA', 'passkey', 'active', '2026-01-05T16:00:00Z', '2026-01-05T16:00:00Z'),
  ('01J9ZD3V000000000000000JAS', '+14035550172', 'jas.gill@example.com',     'Jas Gill',     'en-CA', 'totp',    'active', '2026-01-06T16:00:00Z', '2026-01-06T16:00:00Z'),
  ('01J9ZD3V00000000000000PR1Y', '+14035550191', 'priya.sandhu@example.com', 'Priya Sandhu', 'en-CA', 'sms',     'active', '2026-01-07T16:00:00Z', '2026-01-07T16:00:00Z');

INSERT INTO merchants.merchants
  (id, type, display_name, legal_name, structure, gst_number, registry_jurisdiction, tier, take_rate_bps, status, city, languages, created_at, updated_at) VALUES
  ('01J9ZD3V00000000000000PWM1', 'provider', 'Prairie Wrench',       'Prairie Wrench Mobile Mechanics Ltd.', 'corp_ab', '784512369 RT0001', 'AB', 'master',  900,  'active', 'Calgary', '{en}',    '2026-01-05T17:00:00Z', '2026-01-05T17:00:00Z'),
  ('01J9ZD3V00000000000000PWP1', 'seller',   'Prairie Wrench Parts', 'Prairie Wrench Mobile Mechanics Ltd.', 'corp_ab', '784512369 RT0001', 'AB', 'trusted', 1200, 'active', 'Calgary', '{en}',    '2026-01-05T17:01:00Z', '2026-01-05T17:01:00Z'),
  ('01J9ZD3V00000000000000PDB1', 'kitchen',  'Pho Dau Bo',           'Ravi Sandhu',                          'sole',    NULL,               'AB', 'trusted', 1200, 'active', 'Calgary', '{en,fr}', '2026-01-05T17:02:00Z', '2026-01-05T17:02:00Z');

INSERT INTO merchants.merchant_members (merchant_id, user_id, role, bookable, mfa_ok) VALUES
  ('01J9ZD3V00000000000000PWM1', '01J9ZD3V00000000000000RAV1', 'owner',      true,  true),
  ('01J9ZD3V00000000000000PWM1', '01J9ZD3V000000000000000JAS', 'technician', true,  true),
  ('01J9ZD3V00000000000000PWM1', '01J9ZD3V00000000000000PR1Y', 'bookkeeper', false, false),
  ('01J9ZD3V00000000000000PWP1', '01J9ZD3V00000000000000RAV1', 'owner',      false, true),
  ('01J9ZD3V00000000000000PWP1', '01J9ZD3V00000000000000PR1Y', 'bookkeeper', false, false),
  ('01J9ZD3V00000000000000PDB1', '01J9ZD3V00000000000000RAV1', 'owner',      false, true);
