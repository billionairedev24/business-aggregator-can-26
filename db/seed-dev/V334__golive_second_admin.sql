-- Dev-only seed (profile `local`, S-118): a second console admin, so the go-live screen's two-person launch (one admin
-- requests, another approves) can be tried locally. Without auth/bff: NL_DEV_USER=01J9ZD3V00000000000000MRC1, or the
-- X-Dev-User header. Fake person, fake contact details.
INSERT INTO identity.users (id, phone, email, display_name, locale, mfa_primary, status, created_at, updated_at,
                            first_name, last_name, phone_verified_at, terms_version, terms_accepted_at) VALUES
  ('01J9ZD3V00000000000000MRC1', '+15555550188', 'marc.belanger@example.com', 'Marc Bélanger', 'fr-CA', 'sms', 'active',
   '2026-10-03T16:00:00Z', '2026-10-03T16:00:00Z', 'Marc', 'Bélanger', '2026-10-03T16:00:00Z', '3.0', '2026-10-03T16:00:00Z')
ON CONFLICT (id) DO NOTHING;

INSERT INTO identity.platform_roles (user_id, role, granted_at) VALUES
  ('01J9ZD3V00000000000000MRC1', 'staff', '2026-10-03T16:00:00Z'),
  ('01J9ZD3V00000000000000MRC1', 'admin', '2026-10-03T16:00:00Z')
ON CONFLICT DO NOTHING;
