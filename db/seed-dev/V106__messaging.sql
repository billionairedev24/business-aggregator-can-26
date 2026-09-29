-- Dev-only seed (local profile): messaging, help & reviews workstream. design/02 Provider Studio › messages
-- (threadDefs), help (helpCases, statusRows), reviews (ratingDist, reviewsList), dashboard (quality).
-- Times are relative to when the seed runs, so "now", "1 h", "2 d" read as in the design on a fresh database.

-- ─── Messages ────────────────────────────────────────────────────────────────────────────────────────────────────
INSERT INTO messaging.threads
  (id, merchant_id, kind, ref_type, ref_id, ref_code, counterpart_id, counterpart_name, subject, assignee_id,
   participant_ids, merchant_read_at, last_message_at, created_at) VALUES
  -- Prairie Wrench (provider) — design threadDefs; 3 unread (badge "3"), Jas sees only M. Tran (his 2:00 diagnostic)
  ('01J9ZD3VTH00000000000PWM01', '01J9ZD3V00000000000000PWM1', 'customer', 'booking', 'BK-7712', 'BK-7712', '01J9ZD3VCU0000000000AMARA', 'Amara Osei', 'brake inspection Tue 9:00', '01J9ZD3V00000000000000RAV1', '{01J9ZD3VCU0000000000AMARA}', now() - interval '50 minutes', now() - interval '1 minute', now() - interval '2 days'),
  ('01J9ZD3VTH00000000000PWM02', '01J9ZD3V00000000000000PWM1', 'customer', 'booking', 'BK-7715', 'BK-7715', '01J9ZD3VCU00000000000MTRAN', 'M. Tran', 'diagnostic scan today 2:00', '01J9ZD3V000000000000000JAS', '{01J9ZD3VCU00000000000MTRAN}', now() - interval '3 hours', now() - interval '1 hour', now() - interval '3 days'),
  ('01J9ZD3VTH00000000000PWM03', '01J9ZD3V00000000000000PWM1', 'customer', 'booking', 'BK-7690', 'BK-7690', '01J9ZD3VCU000000000DKOWAL', 'D. Kowalski', 'front brake pads', '01J9ZD3V00000000000000RAV1', '{01J9ZD3VCU000000000DKOWAL}', now() - interval '20 hours', now() - interval '1 day', now() - interval '6 days'),
  ('01J9ZD3VTH00000000000PWM04', '01J9ZD3V00000000000000PWM1', 'support',  'dispute', 'DS-1188', 'DS-1188', NULL, 'Northline support', 'dispute DS-1188', NULL, '{}', NULL, now() - interval '2 days', now() - interval '2 days'),
  -- Prairie Wrench Parts (seller)
  ('01J9ZD3VTH00000000000PWP01', '01J9ZD3V00000000000000PWP1', 'customer', 'order', 'NL-48213', 'NL-48213', '01J9ZD3VCU0000000000AMARA', 'Amara Osei', 'wiper blades ×2 · run R-611', NULL, '{01J9ZD3VCU0000000000AMARA}', NULL, now() - interval '5 minutes', now() - interval '1 day'),
  ('01J9ZD3VTH00000000000PWP02', '01J9ZD3V00000000000000PWP1', 'customer', 'order', 'NL-48224', 'NL-48224', '01J9ZD3VCU000000000SBOUCH', 'S. Bouchard', 'synthetic oil 5 L · run R-612', NULL, '{01J9ZD3VCU000000000SBOUCH}', NULL, now() - interval '2 hours', now() - interval '1 day'),
  ('01J9ZD3VTH00000000000PWP03', '01J9ZD3V00000000000000PWP1', 'customer', 'order', 'NL-48219', 'NL-48219', '01J9ZD3VCU000000000DKOWAL', 'D. Kowalski', 'brake pads (front) · run R-611', NULL, '{01J9ZD3VCU000000000DKOWAL}', now() - interval '1 day', now() - interval '3 hours', now() - interval '2 days'),
  -- Pho Dau Bo (kitchen) — badge "1"
  ('01J9ZD3VTH00000000000PDB01', '01J9ZD3V00000000000000PDB1', 'customer', 'order', 'NL-50102', 'NL-50102', '01J9ZD3VCU00000000000LINHP', 'Linh P.', 'pho tai ×2 · pickup 6:15', NULL, '{01J9ZD3VCU00000000000LINHP}', NULL, now() - interval '4 minutes', now() - interval '30 minutes'),
  ('01J9ZD3VTH00000000000PDB02', '01J9ZD3V00000000000000PDB1', 'customer', 'order', 'NL-50088', 'NL-50088', '01J9ZD3VCU00000000000JMORI', 'J. Morin', 'bun bo hue · delivered', NULL, '{01J9ZD3VCU00000000000JMORI}', now() - interval '1 day', now() - interval '1 day', now() - interval '1 day');

INSERT INTO messaging.messages (id, thread_id, sender_id, sender_role, sender_name, body, attachments, template_key, at, flagged) VALUES
  ('01J9ZD3VMS000000000PWM0101', '01J9ZD3VTH00000000000PWM01', '01J9ZD3VCU0000000000AMARA', 'customer', NULL, 'Hi Ravi — parkade level P2, stall 118. Gate code shared in the app for 2 h.', '{}', NULL, now() - interval '70 minutes', false),
  ('01J9ZD3VMS000000000PWM0102', '01J9ZD3VTH00000000000PWM01', '01J9ZD3V00000000000000RAV1', 'merchant', NULL, 'Perfect, see you at 9. I''ll send an ETA when I leave the previous job.', '{}', NULL, now() - interval '50 minutes', false),
  ('01J9ZD3VMS000000000PWM0103', '01J9ZD3VTH00000000000PWM01', '01J9ZD3VCU0000000000AMARA', 'customer', NULL, 'Thanks! The dash light came on again yesterday.', '{}', NULL, now() - interval '1 minute', false),
  ('01J9ZD3VMS000000000PWM0201', '01J9ZD3VTH00000000000PWM02', '01J9ZD3V000000000000000JAS', 'merchant', NULL, 'Diagnostic is booked for today at 2. If it''s the timing chain I can do the repair Saturday morning.', '{}', NULL, now() - interval '3 hours', false),
  ('01J9ZD3VMS000000000PWM0202', '01J9ZD3VTH00000000000PWM02', '01J9ZD3VCU00000000000MTRAN', 'customer', NULL, 'Saturday 10 works. Send the quote?', '{}', NULL, now() - interval '1 hour', false),
  ('01J9ZD3VMS000000000PWM0301', '01J9ZD3VTH00000000000PWM03', '01J9ZD3V00000000000000RAV1', 'merchant', NULL, 'All done — pads and rotors replaced. Completion photos are in the app.', '{}', 'provider.job_complete', now() - interval '26 hours', false),
  ('01J9ZD3VMS000000000PWM0302', '01J9ZD3VTH00000000000PWM03', '01J9ZD3VCU000000000DKOWAL', 'customer', NULL, 'Pads feel great, thanks Ravi.', '{}', NULL, now() - interval '1 day', false),
  ('01J9ZD3VMS000000000PWM0401', '01J9ZD3VTH00000000000PWM04', '01J9ZD3VAG00000000000DEVK1', 'agent', 'Dev K.', 'Dispute DS-1188: please respond by Thu.', '{}', NULL, now() - interval '2 days', false),
  ('01J9ZD3VMS000000000PWP0101', '01J9ZD3VTH00000000000PWP01', '01J9ZD3VCU0000000000AMARA', 'customer', NULL, 'Can I pick these up at the Beltline stop instead of delivery?', '{}', NULL, now() - interval '5 minutes', false),
  ('01J9ZD3VMS000000000PWP0201', '01J9ZD3VTH00000000000PWP02', '01J9ZD3VCU000000000SBOUCH', 'customer', NULL, 'Is this the 5W-30? My manual says 5W-30 only.', '{}', NULL, now() - interval '2 hours', false),
  ('01J9ZD3VMS000000000PWP0301', '01J9ZD3VTH00000000000PWP03', '01J9ZD3V00000000000000RAV1', 'merchant', NULL, 'Packed — it goes out on the next run', '{}', 'seller.packed', now() - interval '1 day', false),
  ('01J9ZD3VMS000000000PWP0302', '01J9ZD3VTH00000000000PWP03', '01J9ZD3VCU000000000DKOWAL', 'customer', NULL, 'Great. Will the driver call when he''s outside?', '{}', NULL, now() - interval '3 hours', false),
  ('01J9ZD3VMS000000000PDB0101', '01J9ZD3VTH00000000000PDB01', '01J9ZD3VCU00000000000LINHP', 'customer', NULL, 'Can you make one of them without cilantro?', '{}', NULL, now() - interval '4 minutes', false),
  ('01J9ZD3VMS000000000PDB0201', '01J9ZD3VTH00000000000PDB02', '01J9ZD3VCU00000000000JMORI', 'customer', NULL, 'Thanks, still hot when it arrived!', '{}', NULL, now() - interval '1 day', false);

-- ─── Help cases (design helpCases) ───────────────────────────────────────────────────────────────────────────────
INSERT INTO messaging.tickets
  (id, number, requester_type, requester_id, merchant_id, opened_by, topic, subject, priority, state, agent_id, agent_name,
   sla_due_at, ref_type, ref_id, ref_label, lang, channel, urgent, resolution_note, resolved_at, context, created_at, updated_at) VALUES
  ('01J9ZD3VTK000000000PWM4471', 4471, 'merchant', '01J9ZD3V00000000000000PWM1', '01J9ZD3V00000000000000PWM1', '01J9ZD3V00000000000000RAV1',
   'verification', 'WCB clearance letter uploaded — awaiting re-verification', 'priority', 'in_progress', '01J9ZD3VAG00000000000DEVK1', 'Dev K.',
   (date_trunc('day', now() AT TIME ZONE 'America/Edmonton') + interval '1 day 10 hours') AT TIME ZONE 'America/Edmonton',
   'document', 'DOC-WCB-2026', 'Document · WCB clearance', 'en', 'chat', false, NULL, NULL,
   '{"portal":"provider","tier":"master","role":"owner","recentEvents":[]}', now() - interval '3 hours', now() - interval '2 hours'),
  ('01J9ZD3VTK000000000PWM4402', 4402, 'merchant', '01J9ZD3V00000000000000PWM1', '01J9ZD3V00000000000000PWM1', '01J9ZD3V00000000000000PR1Y',
   'payouts', 'Payout of $1,912.40 arrived a day late', 'priority', 'resolved', '01J9ZD3VAG00000000000MIAR1', 'Mia R.',
   NULL, 'payout', 'po_9Kx2', 'Payout po_9Kx2', 'en', 'email', false, 'bank holiday', '2026-09-05T17:00:00Z',
   '{"portal":"provider","tier":"master","role":"bookkeeper","recentEvents":[]}', '2026-09-04T15:10:00Z', '2026-09-05T17:00:00Z'),
  ('01J9ZD3VTK000000000PWM4298', 4298, 'merchant', '01J9ZD3V00000000000000PWM1', '01J9ZD3V00000000000000PWM1', '01J9ZD3V00000000000000RAV1',
   'refunds', 'Customer disputed diagnostic (DS-1102)', 'priority', 'resolved', '01J9ZD3VAG00000000000DEVK1', 'Dev K.',
   NULL, 'dispute', 'DS-1102', 'Dispute DS-1102', 'en', 'chat', false, 'in your favour', '2026-08-20T18:30:00Z',
   '{"portal":"provider","tier":"master","role":"owner","recentEvents":[]}', '2026-08-18T16:00:00Z', '2026-08-20T18:30:00Z'),
  ('01J9ZD3VTK000000000PWP4466', 4466, 'merchant', '01J9ZD3V00000000000000PWP1', '01J9ZD3V00000000000000PWP1', '01J9ZD3V00000000000000RAV1',
   'listings', 'Bulk upload: 3 rows stuck in vetting', 'normal', 'in_progress', '01J9ZD3VAG00000000000MIAR1', 'Mia R.',
   (date_trunc('day', now() AT TIME ZONE 'America/Edmonton') + interval '1 day 10 hours') AT TIME ZONE 'America/Edmonton',
   NULL, NULL, NULL, 'en', 'chat', false, NULL, NULL,
   '{"portal":"seller","tier":"trusted","role":"owner","recentEvents":[]}', now() - interval '5 hours', now() - interval '4 hours'),
  ('01J9ZD3VTK000000000PDB4472', 4472, 'merchant', '01J9ZD3V00000000000000PDB1', '01J9ZD3V00000000000000PDB1', '01J9ZD3V00000000000000RAV1',
   'verification', 'AHS permit renewal uploaded — awaiting re-verification', 'normal', 'in_progress', '01J9ZD3VAG00000000000DEVK1', 'Dev K.',
   (date_trunc('day', now() AT TIME ZONE 'America/Edmonton') + interval '1 day 10 hours') AT TIME ZONE 'America/Edmonton',
   'document', 'DOC-AHS-2026', 'Document · AHS food handling permit', 'en', 'chat', false, NULL, NULL,
   '{"portal":"kitchen","tier":"trusted","role":"owner","recentEvents":[]}', now() - interval '3 hours', now() - interval '2 hours');

INSERT INTO messaging.threads (id, merchant_id, kind, ref_type, ref_id, ref_code, counterpart_name, subject, participant_ids, last_message_at, created_at) VALUES
  ('01J9ZD3VTH0000000000CASE71', '01J9ZD3V00000000000000PWM1', 'case', 'ticket', '01J9ZD3VTK000000000PWM4471', 'HD-4471', 'Northline support', 'WCB clearance letter uploaded — awaiting re-verification', '{}', now() - interval '2 hours', now() - interval '3 hours'),
  ('01J9ZD3VTH0000000000CASE02', '01J9ZD3V00000000000000PWM1', 'case', 'ticket', '01J9ZD3VTK000000000PWM4402', 'HD-4402', 'Northline support', 'Payout of $1,912.40 arrived a day late', '{}', '2026-09-05T17:00:00Z', '2026-09-04T15:10:00Z'),
  ('01J9ZD3VTH0000000000CASE98', '01J9ZD3V00000000000000PWM1', 'case', 'ticket', '01J9ZD3VTK000000000PWM4298', 'HD-4298', 'Northline support', 'Customer disputed diagnostic (DS-1102)', '{}', '2026-08-20T18:30:00Z', '2026-08-18T16:00:00Z'),
  ('01J9ZD3VTH0000000000CASE66', '01J9ZD3V00000000000000PWP1', 'case', 'ticket', '01J9ZD3VTK000000000PWP4466', 'HD-4466', 'Northline support', 'Bulk upload: 3 rows stuck in vetting', '{}', now() - interval '4 hours', now() - interval '5 hours'),
  ('01J9ZD3VTH0000000000CASE72', '01J9ZD3V00000000000000PDB1', 'case', 'ticket', '01J9ZD3VTK000000000PDB4472', 'HD-4472', 'Northline support', 'AHS permit renewal uploaded — awaiting re-verification', '{}', now() - interval '2 hours', now() - interval '3 hours');

INSERT INTO messaging.messages (id, thread_id, sender_id, sender_role, sender_name, body, attachments, at, flagged) VALUES
  ('01J9ZD3VMS00000000CASE7101', '01J9ZD3VTH0000000000CASE71', '01J9ZD3V00000000000000RAV1', 'merchant', NULL, 'Uploaded the renewed letter — can instant book be turned back on today?', '{}', now() - interval '3 hours', false),
  ('01J9ZD3VMS00000000CASE7102', '01J9ZD3VTH0000000000CASE71', '01J9ZD3VAG00000000000DEVK1', 'agent', 'Dev K.', 'Thanks Ravi — document received and readable. Compliance re-verifies within 4 business hours; I''ve flagged it as blocking so it''s next in queue. — Dev K.', '{}', now() - interval '2 hours', false),
  ('01J9ZD3VMS00000000CASE0201', '01J9ZD3VTH0000000000CASE02', '01J9ZD3V00000000000000PR1Y', 'merchant', NULL, 'Friday''s payout of $1,912.40 isn''t in our account yet. Stripe shows it as paid.', '{}', '2026-09-04T15:10:00Z', false),
  ('01J9ZD3VMS00000000CASE0202', '01J9ZD3VTH0000000000CASE02', '01J9ZD3VAG00000000000MIAR1', 'agent', 'Mia R.', 'It was sent on time; banks held it over the Labour Day holiday. It landed this morning. — Mia R.', '{}', '2026-09-05T17:00:00Z', false),
  ('01J9ZD3VMS00000000CASE9801', '01J9ZD3VTH0000000000CASE98', '01J9ZD3V00000000000000RAV1', 'merchant', NULL, 'The customer disputed the diagnostic fee, but the scan report and photos are on the job.', '{}', '2026-08-18T16:00:00Z', false),
  ('01J9ZD3VMS00000000CASE9802', '01J9ZD3VTH0000000000CASE98', '01J9ZD3VAG00000000000DEVK1', 'agent', 'Dev K.', 'Resolved in your favour — the scan report showed the work was done. The funds are released. — Dev K.', '{}', '2026-08-20T18:30:00Z', false),
  ('01J9ZD3VMS00000000CASE6601', '01J9ZD3VTH0000000000CASE66', '01J9ZD3V00000000000000RAV1', 'merchant', NULL, 'Three rows from yesterday''s bulk upload are still pending vetting (WB-26, OIL-0W20, CAF-02).', '{}', now() - interval '5 hours', false),
  ('01J9ZD3VMS00000000CASE6602', '01J9ZD3VTH0000000000CASE66', '01J9ZD3VAG00000000000MIAR1', 'agent', 'Mia R.', 'They''re in the manual review queue because of the price check. I''ve asked the vetting team to look today. — Mia R.', '{}', now() - interval '4 hours', false),
  ('01J9ZD3VMS00000000CASE7201', '01J9ZD3VTH0000000000CASE72', '01J9ZD3V00000000000000RAV1', 'merchant', NULL, 'Uploaded the renewed AHS permit — can the menu stay live past the expiry date?', '{}', now() - interval '3 hours', false),
  ('01J9ZD3VMS00000000CASE7202', '01J9ZD3VTH0000000000CASE72', '01J9ZD3VAG00000000000DEVK1', 'agent', 'Dev K.', 'Permit received and readable. Compliance re-verifies within 4 business hours; your menu stays live meanwhile. — Dev K.', '{}', now() - interval '2 hours', false);

-- Platform status (design statusRows): SMS degraded since 12:40 today.
UPDATE messaging.status_components
   SET state = 'degraded',
       note_i18n = '{"en":"carrier delays in AB","fr":"retards des opérateurs en Alberta"}',
       since = (date_trunc('day', now() AT TIME ZONE 'America/Edmonton') + interval '12 hours 40 minutes') AT TIME ZONE 'America/Edmonton'
 WHERE key = 'notifications';

-- ─── Reviews (design ratingDist / reviewsList) ───────────────────────────────────────────────────────────────────
-- Prairie Wrench: 312 verified reviews averaging 4.9 (5★ 279 · 4★ 24 · 3★ 6 · 2★ 2 · 1★ 1 — see DECISIONS.md),
-- praise tags ≈ On time 88 % · Clear explanation 81 % · Fair price 76 %. The design's three are the most recent.
INSERT INTO trust.reviews (id, ref_type, ref_id, author_id, author_name, target_type, target_id, rating, tags, text, reply, reply_at, reply_by, job_label, lang, created_at) VALUES
  ('01J9ZD3VRV00000000PWM00001', 'booking', 'BK-7650', '01J9ZD3VCU00000000000DANAK', 'Dana K.', 'merchant', '01J9ZD3V00000000000000PWM1', 5, '{on_time,clear_explanation,fair_price}',
   'Showed up at 7 am in −22°, fixed the alternator in the parkade, receipt in the app before he left.',
   'Thanks Dana — see you for the pads in spring.', now() - interval '2 days', '01J9ZD3V00000000000000RAV1', 'alternator', 'en', now() - interval '3 days'),
  ('01J9ZD3VRV00000000PWM00002', 'booking', 'BK-7601', '01J9ZD3VCU00000000000MTRAN', 'M. Tran', 'merchant', '01J9ZD3V00000000000000PWM1', 4, '{clear_explanation}',
   'Thorough, but arrived 20 minutes late. Explained everything clearly.', NULL, NULL, NULL, 'diagnostic', 'en', now() - interval '7 days'),
  ('01J9ZD3VRV00000000PWM00003', 'booking', 'BK-7555', '01J9ZD3VCU000000000SBOUCH', 'S. Bouchard', 'merchant', '01J9ZD3V00000000000000PWM1', 5, '{fair_price,on_time}',
   'Fair price, parts at cost as promised. Booked the winter tire swap on the spot.', NULL, NULL, NULL, 'oil & filter', 'en', now() - interval '14 days');

INSERT INTO trust.reviews (id, ref_type, ref_id, author_id, author_name, target_type, target_id, rating, tags, text, job_label, lang, created_at)
SELECT 'PWMSEED' || lpad(i::text, 5, '0'), 'booking', 'BK-SEED-' || i, 'CUSTSEED' || lpad(i::text, 5, '0'),
       (ARRAY['Kevin L.','Priya N.','Olivia W.','Marc B.','Hannah S.','Tomás R.','Grace H.','Ahmed F.','Julie C.','Sam O.'])[1 + i % 10],
       'merchant', '01J9ZD3V00000000000000PWM1',
       CASE WHEN j <= 277 THEN 5 WHEN j <= 300 THEN 4 WHEN j <= 306 THEN 3 WHEN j <= 308 THEN 2 ELSE 1 END,
       array_remove(ARRAY[CASE WHEN i % 100 < 88 THEN 'on_time' END,
                          CASE WHEN (i * 7) % 100 < 81 THEN 'clear_explanation' END,
                          CASE WHEN (i * 13) % 100 < 76 THEN 'fair_price' END], NULL),
       CASE WHEN i % 5 = 0 THEN (ARRAY['Quick and tidy, texted an ETA and was right on time.',
                                       'Explained what the scan found before doing anything.',
                                       'Came to my work parking lot — saved me a day off.',
                                       'Fair quote, no surprises on the bill.',
                                       'Had the part on the truck, done in under an hour.'])[1 + (i / 5) % 5] END,
       (ARRAY['brake inspection','oil & filter','diagnostic','battery swap','winter tire swap','alternator'])[1 + i % 6],
       'en', now() - interval '15 days' - (i * interval '19 hours')
  FROM generate_series(1, 309) AS i, LATERAL (SELECT (i * 37) % 309 + 1 AS j) p;

-- Prairie Wrench Parts: 96 verified orders averaging 4.8.
INSERT INTO trust.reviews (id, ref_type, ref_id, author_id, author_name, target_type, target_id, rating, tags, text, job_label, lang, created_at) VALUES
  ('01J9ZD3VRV00000000PWP00001', 'order', 'NL-48150', '01J9ZD3VCU0000000000AMARA', 'Amara Osei', 'merchant', '01J9ZD3V00000000000000PWP1', 5, '{on_time,well_packed}',
   'Wiper blades were on the evening run like promised. Right size first time.', 'wiper blades', 'en', now() - interval '2 days'),
  ('01J9ZD3VRV00000000PWP00002', 'order', 'NL-48120', '01J9ZD3VCU000000000DKOWAL', 'D. Kowalski', 'merchant', '01J9ZD3V00000000000000PWP1', 4, '{well_packed}',
   'Good pads, but the box was missing the clip hardware. Sorted in a day.', 'brake pads', 'en', now() - interval '9 days');
INSERT INTO trust.reviews (id, ref_type, ref_id, author_id, author_name, target_type, target_id, rating, tags, text, job_label, lang, created_at)
SELECT 'PWPSEED' || lpad(i::text, 5, '0'), 'order', 'NL-SEED-P' || i, 'CUSTSEEDP' || lpad(i::text, 4, '0'),
       (ARRAY['Kevin L.','Priya N.','Olivia W.','Marc B.','Hannah S.','Tomás R.'])[1 + i % 6],
       'merchant', '01J9ZD3V00000000000000PWP1',
       CASE WHEN j <= 79 THEN 5 WHEN j <= 91 THEN 4 WHEN j <= 93 THEN 3 ELSE 2 END,
       array_remove(ARRAY[CASE WHEN i % 100 < 84 THEN 'on_time' END, CASE WHEN (i * 7) % 100 < 72 THEN 'well_packed' END,
                          CASE WHEN (i * 13) % 100 < 65 THEN 'fair_price' END], NULL),
       NULL, (ARRAY['wiper blades','brake pads','synthetic oil 5 L','cabin air filter'])[1 + i % 4],
       'en', now() - interval '10 days' - (i * interval '2 days')
  FROM generate_series(1, 94) AS i, LATERAL (SELECT (i * 31) % 94 + 1 AS j) p;

-- Pho Dau Bo: 140 verified orders averaging 4.8.
INSERT INTO trust.reviews (id, ref_type, ref_id, author_id, author_name, target_type, target_id, rating, tags, text, reply, reply_at, reply_by, job_label, lang, created_at) VALUES
  ('01J9ZD3VRV00000000PDB00001', 'order', 'NL-50088', '01J9ZD3VCU00000000000JMORI', 'J. Morin', 'merchant', '01J9ZD3V00000000000000PDB1', 5, '{hot_on_arrival,tasty}',
   'Bun bo hue was still steaming when it arrived. Broth tastes like my aunt''s.', NULL, NULL, NULL, 'bun bo hue', 'en', now() - interval '1 day'),
  ('01J9ZD3VRV00000000PDB00002', 'order', 'NL-49990', '01J9ZD3VCU00000000000LINHP', 'Linh P.', 'merchant', '01J9ZD3V00000000000000PDB1', 4, '{tasty}',
   'Great pho, but pickup was 10 minutes past the time shown.', 'Sorry for the wait, Linh — Friday rush. The next one is on us.', now() - interval '5 days', '01J9ZD3V00000000000000RAV1', 'pho tai', 'en', now() - interval '6 days');
INSERT INTO trust.reviews (id, ref_type, ref_id, author_id, author_name, target_type, target_id, rating, tags, text, job_label, lang, created_at)
SELECT 'PDBSEED' || lpad(i::text, 5, '0'), 'order', 'NL-SEED-K' || i, 'CUSTSEEDK' || lpad(i::text, 4, '0'),
       (ARRAY['Kevin L.','Priya N.','Olivia W.','Marc B.','Hannah S.','Tomás R.','Grace H.'])[1 + i % 7],
       'merchant', '01J9ZD3V00000000000000PDB1',
       CASE WHEN j <= 117 THEN 5 WHEN j <= 133 THEN 4 WHEN j <= 136 THEN 3 WHEN j <= 137 THEN 2 ELSE 1 END,
       array_remove(ARRAY[CASE WHEN i % 100 < 86 THEN 'tasty' END, CASE WHEN (i * 7) % 100 < 70 THEN 'hot_on_arrival' END,
                          CASE WHEN (i * 13) % 100 < 58 THEN 'generous_portions' END], NULL),
       NULL, (ARRAY['pho tai','bun bo hue','spring rolls','banh mi'])[1 + i % 4],
       'en', now() - interval '8 days' - (i * interval '30 hours')
  FROM generate_series(1, 138) AS i, LATERAL (SELECT (i * 29) % 138 + 1 AS j) p;

-- ─── Quality score (dashboard "Quality score · 91") ──────────────────────────────────────────────────────────────
INSERT INTO trust.quality_scores (merchant_id, date, score, components) VALUES
  ('01J9ZD3V00000000000000PWM1', current_date - 1, 91, '{"on_time":{"value":98,"floor":95},"photos":{"value":85,"floor":90},"response":{"value":94,"floor":90},"rebook":{"value":71,"floor":40},"disputes":{"value":0.3,"floor":1}}'),
  ('01J9ZD3V00000000000000PWP1', current_date - 1, 88, '{"on_time":{"value":97,"floor":90},"photos":{"value":92,"floor":85},"response":{"value":96,"floor":85},"rebook":{"value":44,"floor":30},"disputes":{"value":0.5,"floor":1.5}}'),
  ('01J9ZD3V00000000000000PDB1', current_date - 1, 86, '{"on_time":{"value":93,"floor":90},"photos":{"value":88,"floor":80},"response":{"value":97,"floor":85},"rebook":{"value":58,"floor":30},"disputes":{"value":0.6,"floor":1.5}}');
