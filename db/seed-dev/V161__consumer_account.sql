-- Dev-only seed (profile `local`, S-58): Amara Osei's favourites and points, so the account area shows design 06's
-- wallet ("1,240 pts") and favourites on a local run. Nothing earns points yet (DECISIONS S-58): these rows stand in
-- for the earning rules.
INSERT INTO account.favourites (user_id, merchant_id, created_at) VALUES
  ('01J9ZD3V0000000000000C0001', '01J9ZD3V00000000000000PWM1', now() - interval '60 days'),
  ('01J9ZD3V0000000000000C0001', '01J9ZD3V00000000000000PWP1', now() - interval '30 days'),
  ('01J9ZD3V0000000000000C0001', '01J9ZD3V00000000000000PDB1', now() - interval '10 days')
ON CONFLICT DO NOTHING;

INSERT INTO trust.points_ledger (id, user_id, delta, ref_type, ref_id, created_at, note) VALUES
  ('01J9ZD3VPT0000000000000001', '01J9ZD3V0000000000000C0001', 120, 'order', NULL, now() - interval '52 days', 'Grocery run'),
  ('01J9ZD3VPT0000000000000002', '01J9ZD3V0000000000000C0001', 340, 'booking', NULL, now() - interval '45 days', 'Winter tire swap'),
  ('01J9ZD3VPT0000000000000003', '01J9ZD3V0000000000000C0001', 90, 'order', NULL, now() - interval '38 days', 'Grocery run'),
  ('01J9ZD3VPT0000000000000004', '01J9ZD3V0000000000000C0001', 410, 'booking', NULL, now() - interval '31 days', 'Deep clean'),
  ('01J9ZD3VPT0000000000000005', '01J9ZD3V0000000000000C0001', 260, 'order', NULL, now() - interval '24 days', 'Grocery run'),
  ('01J9ZD3VPT0000000000000006', '01J9ZD3V0000000000000C0001', 180, 'order', NULL, now() - interval '17 days', 'Grocery run'),
  ('01J9ZD3VPT0000000000000007', '01J9ZD3V0000000000000C0001', 520, 'booking', NULL, now() - interval '10 days', 'Pre-purchase inspection'),
  ('01J9ZD3VPT0000000000000008', '01J9ZD3V0000000000000C0001', 300, 'order', NULL, now() - interval '3 days', 'Grocery run'),
  ('01J9ZD3VPT0000000000000009', '01J9ZD3V0000000000000C0001', -980, 'redemption', NULL, now() - interval '20 days', 'Redeemed at checkout')
ON CONFLICT (id) DO NOTHING;
