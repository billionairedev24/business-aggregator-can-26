-- Dev-only seed (profile `local`, S-83): Priya Natarajan (V191) also holds the new support lead role, so the support
-- desk's macro editing can be tried locally.
INSERT INTO identity.platform_roles (user_id, role, granted_at) VALUES
  ('01J9ZD3V00000000000000PNA1', 'support_lead', '2026-10-01T16:00:00Z')
ON CONFLICT DO NOTHING;
