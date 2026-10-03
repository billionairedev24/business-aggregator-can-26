-- Dev-only seed (profile `local`, S-120): Priya Natarajan (V191) also holds the merchant success role, so the console's
-- Pilot onboarding screen can be tried with that role view locally.
INSERT INTO identity.platform_roles (user_id, role, granted_at) VALUES
  ('01J9ZD3V00000000000000PNA1', 'merchant_success', '2026-10-03T16:00:00Z')
ON CONFLICT DO NOTHING;
