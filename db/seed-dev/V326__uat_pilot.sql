-- S-121 dev seed (local only): a fake pilot group for the UAT dry run — the three persona businesses, two customers, a
-- courier and the console's Priya Natarajan — with feedback at each triage stage and a few sign-offs. Fixed ids, so
-- docs/uat/dry-run.md can name them. Nothing here is real; the texts are invented.
INSERT INTO uat.participants (id, user_id, merchant_id, persona, label, added_by, created_at) VALUES
  ('01J9ZD3VUP000000000000PRV1', NULL, '01J9ZD3V00000000000000PWM1', 'provider', 'Pilot provider 1 (Prairie Wrench)', '01J9ZD3V00000000000000PNA1', now() - interval '13 days'),
  ('01J9ZD3VUP000000000000SEL1', NULL, '01J9ZD3V00000000000000PWP1', 'seller',   'Pilot seller 1 (Prairie Wrench Parts)', '01J9ZD3V00000000000000PNA1', now() - interval '13 days'),
  ('01J9ZD3VUP000000000000KIT1', NULL, '01J9ZD3V00000000000000PDB1', 'kitchen',  'Pilot kitchen 1 (Pho Dau Bo)', '01J9ZD3V00000000000000PNA1', now() - interval '13 days'),
  ('01J9ZD3VUP000000000000CUS1', '01J9ZD3V0000000000000C00K0', NULL, 'customer', 'Pilot customer 1', '01J9ZD3V00000000000000PNA1', now() - interval '12 days'),
  ('01J9ZD3VUP000000000000CUS2', '01J9ZD3V0000000000000C0002', NULL, 'customer', 'Pilot customer 2 (fr)', '01J9ZD3V00000000000000PNA1', now() - interval '12 days'),
  ('01J9ZD3VUP000000000000COU1', '01J9ZD3V00000000000000CP01', NULL, 'courier',  'Pilot courier 1', '01J9ZD3V00000000000000PNA1', now() - interval '12 days'),
  ('01J9ZD3VUP000000000000STF1', '01J9ZD3V00000000000000PNA1', NULL, 'staff',    'Pilot staff 1', '01J9ZD3V00000000000000PNA1', now() - interval '12 days')
ON CONFLICT DO NOTHING;

INSERT INTO uat.feedback (id, participant_id, user_id, merchant_id, app, category, severity, body, route, app_version,
                          locale, platform, state, blocking, owner_id, tracker_url, duplicate_of, created_at, updated_at) VALUES
  ('01J9ZD3VUF0000000000000001', '01J9ZD3VUP000000000000KIT1', '01J9ZD3V00000000000000KF01', '01J9ZD3V00000000000000PDB1', 'studio', 'bug', 'blocker',
   'The KDS doesn''t ring when a new order arrives after the tablet sleeps.', '/b/01J9ZD3V00000000000000PDB1/kitchen/live', 'dev',
   'en-CA', 'Chrome 129 · Android 14', 'accepted', true, '01J9ZD3V00000000000000PNA1', 'https://tracker.example.com/NL-901', NULL, now() - interval '9 days', now() - interval '8 days'),
  ('01J9ZD3VUF0000000000000002', '01J9ZD3VUP000000000000CUS1', '01J9ZD3V0000000000000C00K0', NULL, 'consumer', 'confusing', 'major',
   'I couldn''t tell whether the deposit had been charged after accepting the quote.', '/account/quotes', 'dev',
   'en-CA', 'Safari 18 · iOS 18', 'fixed', true, '01J9ZD3V00000000000000PNA1', 'https://tracker.example.com/NL-902', NULL, now() - interval '8 days', now() - interval '2 days'),
  ('01J9ZD3VUF0000000000000003', '01J9ZD3VUP000000000000CUS2', '01J9ZD3V0000000000000C0002', NULL, 'mobile', 'bug', 'blocker',
   'Le bouton Payer reste gris après avoir choisi l''heure de livraison.', '/checkout', 'dev',
   'fr-CA', 'Expo · Android 14', 'new', NULL, NULL, NULL, NULL, now() - interval '1 day', now() - interval '1 day'),
  ('01J9ZD3VUF0000000000000004', '01J9ZD3VUP000000000000PRV1', '01J9ZD3V00000000000000RAV1', '01J9ZD3V00000000000000PWM1', 'studio', 'idea', 'minor',
   'Let me copy last week''s availability to this week.', '/b/01J9ZD3V00000000000000PWM1/availability', 'dev',
   'en-CA', 'Firefox 131 · Windows 11', 'triaged', NULL, NULL, NULL, NULL, now() - interval '6 days', now() - interval '5 days'),
  ('01J9ZD3VUF0000000000000005', '01J9ZD3VUP000000000000COU1', '01J9ZD3V00000000000000CP01', NULL, 'courier', 'praise', 'cosmetic',
   'Proof of delivery photo is quick now.', '/stops', 'dev',
   'en-CA', 'Expo · iOS 18', 'closed', false, NULL, NULL, NULL, now() - interval '10 days', now() - interval '7 days'),
  ('01J9ZD3VUF0000000000000006', '01J9ZD3VUP000000000000KIT1', '01J9ZD3V00000000000000KM01', '01J9ZD3V00000000000000PDB1', 'studio', 'bug', 'major',
   'No sound for new orders on the kitchen tablet.', '/b/01J9ZD3V00000000000000PDB1/kitchen/live', 'dev',
   'en-CA', 'Chrome 129 · Android 14', 'duplicate', NULL, NULL, NULL, '01J9ZD3VUF0000000000000001', now() - interval '7 days', now() - interval '7 days')
ON CONFLICT DO NOTHING;

INSERT INTO uat.feedback_history (id, feedback_id, from_state, to_state, blocking, actor_id, note, at) VALUES
  ('01J9ZD3VUH0000000000000001', '01J9ZD3VUF0000000000000001', 'new', 'triaged', NULL, '01J9ZD3V00000000000000PNA1', NULL, now() - interval '9 days'),
  ('01J9ZD3VUH0000000000000002', '01J9ZD3VUF0000000000000001', 'triaged', 'accepted', true, '01J9ZD3V00000000000000PNA1', 'Kitchens miss orders: blocks launch.', now() - interval '8 days'),
  ('01J9ZD3VUH0000000000000003', '01J9ZD3VUF0000000000000002', 'new', 'triaged', NULL, '01J9ZD3V00000000000000PNA1', NULL, now() - interval '8 days'),
  ('01J9ZD3VUH0000000000000004', '01J9ZD3VUF0000000000000002', 'triaged', 'accepted', true, '01J9ZD3V00000000000000PNA1', NULL, now() - interval '7 days'),
  ('01J9ZD3VUH0000000000000005', '01J9ZD3VUF0000000000000002', 'accepted', 'fixed', true, '01J9ZD3V00000000000000PNA1', 'Deposit line added to the confirmation.', now() - interval '2 days'),
  ('01J9ZD3VUH0000000000000006', '01J9ZD3VUF0000000000000004', 'new', 'triaged', NULL, '01J9ZD3V00000000000000PNA1', NULL, now() - interval '5 days'),
  ('01J9ZD3VUH0000000000000007', '01J9ZD3VUF0000000000000005', 'new', 'triaged', NULL, '01J9ZD3V00000000000000PNA1', NULL, now() - interval '9 days'),
  ('01J9ZD3VUH0000000000000008', '01J9ZD3VUF0000000000000005', 'triaged', 'accepted', false, '01J9ZD3V00000000000000PNA1', NULL, now() - interval '9 days'),
  ('01J9ZD3VUH0000000000000009', '01J9ZD3VUF0000000000000005', 'accepted', 'fixed', false, '01J9ZD3V00000000000000PNA1', NULL, now() - interval '8 days'),
  ('01J9ZD3VUH0000000000000010', '01J9ZD3VUF0000000000000005', 'fixed', 'verified', false, '01J9ZD3V00000000000000PNA1', NULL, now() - interval '8 days'),
  ('01J9ZD3VUH0000000000000011', '01J9ZD3VUF0000000000000005', 'verified', 'closed', false, '01J9ZD3V00000000000000PNA1', NULL, now() - interval '7 days'),
  ('01J9ZD3VUH0000000000000012', '01J9ZD3VUF0000000000000006', 'new', 'duplicate', NULL, '01J9ZD3V00000000000000PNA1', NULL, now() - interval '7 days')
ON CONFLICT DO NOTHING;

INSERT INTO uat.signoffs (id, participant_id, script_code, script_version, outcome, comments, blocking_ids, recorded_by, recorded_at) VALUES
  ('01J9ZD3VUS0000000000000001', '01J9ZD3VUP000000000000PRV1', 'merchant-provider', '1.0', 'with_comments', 'Wants copy-week for availability (UAT-1004), not blocking.', '{}', '01J9ZD3V00000000000000PNA1', now() - interval '4 days'),
  ('01J9ZD3VUS0000000000000002', '01J9ZD3VUP000000000000SEL1', 'merchant-seller', '1.0', 'signed_off', NULL, '{}', '01J9ZD3V00000000000000PNA1', now() - interval '4 days'),
  ('01J9ZD3VUS0000000000000003', '01J9ZD3VUP000000000000KIT1', 'merchant-kitchen', '1.0', 'blocked', NULL, '{01J9ZD3VUF0000000000000001}', '01J9ZD3V00000000000000PNA1', now() - interval '3 days'),
  ('01J9ZD3VUS0000000000000004', '01J9ZD3VUP000000000000COU1', 'courier', '1.0', 'signed_off', NULL, '{}', '01J9ZD3V00000000000000PNA1', now() - interval '3 days'),
  ('01J9ZD3VUS0000000000000005', '01J9ZD3VUP000000000000STF1', 'console-staff', '1.0', 'signed_off', NULL, '{}', '01J9ZD3V00000000000000PNA1', now() - interval '2 days')
ON CONFLICT DO NOTHING;
