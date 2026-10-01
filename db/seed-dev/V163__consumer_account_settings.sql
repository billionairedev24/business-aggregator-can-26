-- Dev-only seed (profile `local`, S-59): Amara Osei's address book and household (design 06 account › addresses:
-- "1204 17 Ave SW, Apt 804 · buzz 0804 · leave at door" and "Mum · 44 Varsity Estates Cir NW"; Kofi shares Plus with
-- his own login) and her dietary choices.
INSERT INTO identity.addresses (id, user_id, label, street, unit, city, province, postal, access_note, is_default, created_at) VALUES
  ('01J9ZD3VAD0000000000000001', '01J9ZD3V0000000000000C0001', NULL, '1204 17 Ave SW', 'Apt 804', 'Calgary', 'AB', 'T2T 0B8', 'Buzz 0804 · leave at door', true, now() - interval '200 days'),
  ('01J9ZD3VAD0000000000000002', '01J9ZD3V0000000000000C0001', 'Mum', '44 Varsity Estates Cir NW', NULL, 'Calgary', 'AB', 'T3B 3B8', NULL, false, now() - interval '100 days')
ON CONFLICT (id) DO NOTHING;

INSERT INTO identity.users (id, phone, email, display_name, first_name, last_name, locale, status) VALUES
  ('01J9ZD3V0000000000000C00K0', '+14035550211', 'kofi.osei@example.com', 'Kofi Osei', 'Kofi', 'Osei', 'en-CA', 'active')
ON CONFLICT (id) DO NOTHING;
INSERT INTO identity.households (id, name, plus_plan, renews_at, plus_since) VALUES
  ('01J9ZD3VHH0000000000000001', 'Osei household', 'none', NULL, NULL)
ON CONFLICT (id) DO NOTHING;
INSERT INTO identity.household_members (household_id, user_id, role) VALUES
  ('01J9ZD3VHH0000000000000001', '01J9ZD3V0000000000000C0001', 'owner'),
  ('01J9ZD3VHH0000000000000001', '01J9ZD3V0000000000000C00K0', 'member')
ON CONFLICT DO NOTHING;

INSERT INTO account.preferences (user_id, province, dietary, accessibility) VALUES
  ('01J9ZD3V0000000000000C0001', 'AB', '{halal}', '{step_free}')
ON CONFLICT (user_id) DO NOTHING;
