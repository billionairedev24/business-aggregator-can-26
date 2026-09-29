-- Dev-only seed (profile `local`): operations workstream — the Prairie Wrench jobs, quote requests and availability,
-- and the Prairie Wrench Parts packing list, from design/02 Provider Studio.dc.html. Dates are relative to the day the
-- seed runs (America/Edmonton) so "Today" and "Tonight's run" always have data. Customers are invented personas.

INSERT INTO identity.users (id, phone, email, display_name, locale, reliability_score, status) VALUES
  ('01J9ZD3V0000000000000C0001', '+14035550201', 'amara.osei@example.com',     'Amara Osei',      'en-CA', 4.90, 'active'),
  ('01J9ZD3V0000000000000C0002', '+14035550202', 'sophie.bouchard@example.com', 'Sophie Bouchard', 'fr-CA', 4.70, 'active'),
  ('01J9ZD3V0000000000000C0003', '+14035550203', 'minh.tran@example.com',      'Minh Tran',       'en-CA', 4.80, 'active'),
  ('01J9ZD3V0000000000000C0004', '+14035550204', 'jordan.whitford@example.com', 'Jordan Whitford', 'en-CA', 5.00, 'active'),
  ('01J9ZD3V0000000000000C0005', '+14035550205', 'dana.kowalski@example.com',  'Dana Kowalski',   'en-CA', 4.60, 'active'),
  ('01J9ZD3V0000000000000C0006', '+14035550206', 'kevin.ng@example.com',       'Kevin Ng',        'en-CA', 4.90, 'active'),
  ('01J9ZD3V0000000000000C0007', '+14035550207', 'rosa.diaz@example.com',      'Rosa Diaz',       'en-CA', 4.50, 'active'),
  ('01J9ZD3V0000000000000C0008', '+14035550208', 'luc.cardinal@example.com',   'Luc Cardinal',    'fr-CA', 4.80, 'active'),
  ('01J9ZD3V0000000000000C0009', '+14035550209', 'phuong.nguyen@example.com',  'Phuong Nguyen',   'en-CA', 4.40, 'active');

DO $$
DECLARE
  z constant text := 'America/Edmonton';
  pw constant text := '01J9ZD3V00000000000000PWM1';   -- Prairie Wrench (provider)
  pp constant text := '01J9ZD3V00000000000000PWP1';   -- Prairie Wrench Parts (seller)
  ravi constant text := '01J9ZD3V00000000000000RAV1';
  jas constant text := '01J9ZD3V000000000000000JAS';
  d0 date := (now() AT TIME ZONE 'America/Edmonton')::date;
  yr int := extract(year FROM d0)::int;
  thanks date;
  training date;
  xmas_eve date;
BEGIN
  -- ── Jobs (booking.bookings) ────────────────────────────────────────────────────────────────────────────────────
  INSERT INTO booking.bookings (id, ref, customer_id, merchant_id, member_user_id, type, state, starts_at, ends_at, title,
                                address_line, area, details, escrow_id, price_cents) VALUES
    -- today: the design's "Tuesday"
    ('01J9ZD3V00000000000000BK01', 'BK-7712', '01J9ZD3V0000000000000C0001', pw, ravi, 'visit', 'confirmed',
     (d0 + time '09:00') AT TIME ZONE z, (d0 + time '09:45') AT TIME ZONE z, 'Brake inspection',
     '1204 17 Ave SW', 'Beltline',
     '{"vehicle":"2018 Honda Civic · BKT 4471","access":"P2 stall 118 · code shared 2 h","note":"Grinding on braking, worse when cold."}',
     '01J9ZD3V00000000000000ES01', 9345),
    ('01J9ZD3V00000000000000BK02', 'BK-7713', '01J9ZD3V0000000000000C0002', pw, ravi, 'visit', 'confirmed',
     (d0 + time '11:30') AT TIME ZONE z, (d0 + time '12:00') AT TIME ZONE z, 'Oil & filter',
     '1020 Kensington Rd NW', 'Kensington', '{"vehicle":"2020 Mazda CX-5"}', '01J9ZD3V00000000000000ES02', 7900),
    ('01J9ZD3V00000000000000BK03', 'BK-7714', '01J9ZD3V0000000000000C0003', pw, jas, 'visit', 'confirmed',
     (d0 + time '14:00') AT TIME ZONE z, (d0 + time '15:00') AT TIME ZONE z, 'Diagnostic scan',
     '1330 9 Ave SE', 'Inglewood', '{"vehicle":"2016 Honda Civic"}', '01J9ZD3V00000000000000ES03', 12000),
    -- yesterday (done)
    ('01J9ZD3V00000000000000BK04', 'BK-7708', '01J9ZD3V0000000000000C0006', pw, ravi, 'visit', 'signed_off',
     (d0 - 1 + time '08:00') AT TIME ZONE z, (d0 - 1 + time '09:00') AT TIME ZONE z, 'Tire swap',
     '220 12 Ave SW', 'Beltline', '{}', '01J9ZD3V00000000000000ES04', 9900),
    ('01J9ZD3V00000000000000BK05', 'BK-7709', '01J9ZD3V0000000000000C0007', pw, ravi, 'visit', 'completed',
     (d0 - 1 + time '13:00') AT TIME ZONE z, (d0 - 1 + time '13:30') AT TIME ZONE z, 'Oil & filter',
     '88 Kensington Cres NW', 'Kensington', '{}', '01J9ZD3V00000000000000ES05', 7900),
    -- coming up
    ('01J9ZD3V00000000000000BK06', 'BK-7715', '01J9ZD3V0000000000000C0004', pw, ravi, 'visit', 'confirmed',
     (d0 + 1 + time '07:00') AT TIME ZONE z, (d0 + 1 + time '08:30') AT TIME ZONE z, 'Pre-purchase inspection',
     'Airdrie Ford, 2 Market St', 'Airdrie', '{"vehicle":"2019 Ford F-150"}', '01J9ZD3V00000000000000ES06', 16000),
    ('01J9ZD3V00000000000000BK07', 'BK-7716', '01J9ZD3V0000000000000C0005', pw, ravi, 'visit', 'confirmed',
     (d0 + 2 + time '09:00') AT TIME ZONE z, (d0 + 2 + time '10:30') AT TIME ZONE z, 'Brake pads',
     '1510 14 St SW', 'Beltline', '{"vehicle":"2017 Toyota RAV4"}', '01J9ZD3V00000000000000ES07', 24700),
    -- Amara's two past jobs ("reliability 4.9 · 2 past jobs")
    ('01J9ZD3V00000000000000BK08', 'BK-7520', '01J9ZD3V0000000000000C0001', pw, ravi, 'visit', 'signed_off',
     (d0 - 35 + time '10:00') AT TIME ZONE z, (d0 - 35 + time '10:30') AT TIME ZONE z, 'Oil & filter',
     '1204 17 Ave SW', 'Beltline', '{}', '01J9ZD3V00000000000000ES08', 7900),
    ('01J9ZD3V00000000000000BK09', 'BK-7401', '01J9ZD3V0000000000000C0001', pw, ravi, 'visit', 'signed_off',
     (d0 - 70 + time '15:00') AT TIME ZONE z, (d0 - 70 + time '16:00') AT TIME ZONE z, 'Winter tire swap',
     '1204 17 Ave SW', 'Beltline', '{}', '01J9ZD3V00000000000000ES09', 9900);

  INSERT INTO booking.booking_events (id, booking_id, type, at, actor_id, media_id, note) VALUES
    ('01J9ZD3V00000000000000BE01', '01J9ZD3V00000000000000BK04', 'en_route',   (d0 - 1 + time '07:40') AT TIME ZONE z, ravi, NULL, NULL),
    ('01J9ZD3V00000000000000BE02', '01J9ZD3V00000000000000BK04', 'on_site',    (d0 - 1 + time '07:58') AT TIME ZONE z, ravi, NULL, NULL),
    ('01J9ZD3V00000000000000BE03', '01J9ZD3V00000000000000BK04', 'completed',  (d0 - 1 + time '08:55') AT TIME ZONE z, ravi, '01J9ZD3V00000000000000MD01', 'Winter tires on, torqued to spec.'),
    ('01J9ZD3V00000000000000BE04', '01J9ZD3V00000000000000BK04', 'signed_off', (d0 - 1 + time '10:12') AT TIME ZONE z, '01J9ZD3V0000000000000C0006', NULL, NULL),
    ('01J9ZD3V00000000000000BE05', '01J9ZD3V00000000000000BK05', 'en_route',   (d0 - 1 + time '12:35') AT TIME ZONE z, ravi, NULL, NULL),
    ('01J9ZD3V00000000000000BE06', '01J9ZD3V00000000000000BK05', 'on_site',    (d0 - 1 + time '12:58') AT TIME ZONE z, ravi, NULL, NULL),
    ('01J9ZD3V00000000000000BE07', '01J9ZD3V00000000000000BK05', 'completed',  (d0 - 1 + time '13:31') AT TIME ZONE z, ravi, NULL, 'Oil and filter changed; next service at 158,000 km.');
  INSERT INTO booking.media (id, merchant_id, file_name, content_type, size_bytes, storage_key, created_by, created_at) VALUES
    ('01J9ZD3V00000000000000MD01', pw, 'tires-done.jpg', 'image/jpeg', 184233, 'seed://tires-done.jpg', ravi, (d0 - 1 + time '08:54') AT TIME ZONE z);

  -- ── Quote requests + the drafts the composer opens with ────────────────────────────────────────────────────────
  INSERT INTO booking.quote_requests (id, number, customer_id, category_id, details, media, merchant_ids, expires_at, respond_by, created_at) VALUES
    ('01J9ZD3V00000000000000QR01', 3104, '01J9ZD3V0000000000000C0003', 'service.automotive.mobile-mechanic',
     jsonb_build_object('title', 'Alternator, 2016 Civic', 'area', 'Inglewood',
                        'description', 'Battery light on, whining noise. Can you come Saturday?',
                        'preferredAt', to_char(((d0 + ((6 - extract(isodow FROM d0)::int + 7) % 7)) + time '10:00') AT TIME ZONE z AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')),
     '{}', ARRAY[pw], now() + interval '3 days', now() + interval '1 hour 12 minutes', now() - interval '48 minutes'),
    ('01J9ZD3V00000000000000QR02', 3105, '01J9ZD3V0000000000000C0004', 'service.automotive.mobile-mechanic',
     jsonb_build_object('title', 'Pre-purchase inspection', 'area', 'Airdrie',
                        'description', '2019 F-150 at a dealer lot, need it checked before Friday.',
                        'preferredAt', to_char(((d0 + ((4 - extract(isodow FROM d0)::int + 7) % 7)) + time '15:00') AT TIME ZONE z AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')),
     '{}', ARRAY[pw], now() + interval '3 days', now() + interval '1 hour 50 minutes', now() - interval '10 minutes');

  INSERT INTO booking.quotes (id, request_id, merchant_id, ref, version, scope, exclusions, proposed_at, duration_min, warranty,
                              deposit_kind, deposit_bps, subtotal_cents, tax_cents, tax_bps, total_cents, deposit_cents,
                              attachments, valid_hours, state, created_at, created_by) VALUES
    ('01J9ZD3V00000000000000QT01', '01J9ZD3V00000000000000QR01', pw, 'QT-3104', 1,
     'Confirm charging fault, replace alternator with remanufactured unit, test output, check belt and tensioner. Old part returned to you or recycled.',
     NULL, NULL, 120, 'parts_labour_12m', 'none', NULL, 46750, 2338, 500, 49088, 0, '{}', 72, 'draft', now(), ravi),
    ('01J9ZD3V00000000000000QT02', '01J9ZD3V00000000000000QR02', pw, 'QT-3105', 1,
     'Full mechanical + body inspection at the dealer lot, OBD scan, test drive, written report with photos within 2 hours.',
     NULL, NULL, 120, 'parts_labour_12m', 'none', NULL, 16000, 800, 500, 16800, 0, '{}', 72, 'draft', now(), ravi);
  INSERT INTO booking.quote_lines (id, quote_id, position, kind, description, note, qty, unit_cents, amount_cents, taxable) VALUES
    ('01J9ZD3V00000000000000QL01', '01J9ZD3V00000000000000QT01', 0, 'labour', 'Diagnose charging system', NULL, 0.5, 6500, 3250, true),
    ('01J9ZD3V00000000000000QL02', '01J9ZD3V00000000000000QT01', 1, 'part', 'Alternator — remanufactured, 12-mo warranty', NULL, 1, 24000, 24000, true),
    ('01J9ZD3V00000000000000QL03', '01J9ZD3V00000000000000QT01', 2, 'labour', 'Replace alternator & serpentine belt check', NULL, 1.5, 13000, 19500, true),
    ('01J9ZD3V00000000000000QL04', '01J9ZD3V00000000000000QT02', 0, 'labour', 'Pre-purchase inspection — 120-point + OBD scan + written report', NULL, 1, 14000, 14000, true),
    ('01J9ZD3V00000000000000QL05', '01J9ZD3V00000000000000QT02', 1, 'travel', 'Travel · Airdrie', NULL, 1, 2000, 2000, true);

  -- ── Availability: Ravi and Jas ─────────────────────────────────────────────────────────────────────────────────
  INSERT INTO availability.availability_rules (id, merchant_id, member_user_id, weekday, ranges, effective_from, updated_at)
  SELECT 'AV' || lpad(m.n::text, 2, '0') || lpad(w.d::text, 22, '0'), pw, m.id, w.d, w.r::jsonb, d0 - 27, now() - interval '27 days'
    FROM (VALUES (1, ravi), (2, jas)) AS m(n, id)
    JOIN LATERAL (VALUES
      (1, CASE WHEN m.id = ravi THEN '[["07:00","18:00"]]' ELSE '[["08:00","16:00"]]' END),
      (2, CASE WHEN m.id = ravi THEN '[["07:00","18:00"]]' ELSE '[["08:00","16:00"]]' END),
      (3, CASE WHEN m.id = ravi THEN '[["07:00","18:00"]]' ELSE '[]' END),
      (4, CASE WHEN m.id = ravi THEN '[["07:00","18:00"]]' ELSE '[["08:00","16:00"]]' END),
      (5, CASE WHEN m.id = ravi THEN '[]' ELSE '[["08:00","16:00"]]' END),
      (6, CASE WHEN m.id = ravi THEN '[["09:00","14:00"]]' ELSE '[["10:00","15:00"],["17:00","20:00"]]' END),
      (7, '[]')) AS w(d, r) ON true;

  INSERT INTO availability.booking_rules (merchant_id, interval_min, buffer_min, min_notice_min, horizon_days, max_jobs_per_day,
                                          accept_mode, reschedule_free_min, late_cancel_fee_cents, holiday_premium_cents, updated_at)
  VALUES (pw, 30, 20, 60, 14, 5, 'instant', 180, 0, 5000, now() - interval '27 days');
  INSERT INTO availability.service_areas (merchant_id, zone)
  SELECT pw, z2 FROM unnest(ARRAY['Beltline', 'Kensington', 'Inglewood', 'Downtown', 'Airdrie']) AS z2;

  thanks := make_date(yr, 10, 1) + ((8 - extract(isodow FROM make_date(yr, 10, 1))::int) % 7) + 7;
  IF thanks < d0 THEN thanks := make_date(yr + 1, 10, 1) + ((8 - extract(isodow FROM make_date(yr + 1, 10, 1))::int) % 7) + 7; END IF;
  training := make_date(yr, 11, 3);
  IF training < d0 THEN training := make_date(yr + 1, 11, 3); END IF;
  xmas_eve := make_date(yr, 12, 24);
  IF xmas_eve < d0 THEN xmas_eve := make_date(yr + 1, 12, 24); END IF;
  INSERT INTO availability.time_off (id, merchant_id, member_user_id, starts_on, ends_on, kind, special_ranges, reason, created_at, created_by) VALUES
    ('01J9ZD3V00000000000000TO01', pw, NULL, thanks, thanks, 'closed', NULL, 'Thanksgiving', now() - interval '27 days', ravi),
    ('01J9ZD3V00000000000000TO02', pw, ravi, training, training + 4, 'closed', NULL, 'EV certification training', now() - interval '27 days', ravi),
    ('01J9ZD3V00000000000000TO03', pw, NULL, xmas_eve, xmas_eve, 'special', '[["08:00","12:00"]]', 'Half day', now() - interval '27 days', ravi);

  INSERT INTO availability.calendar_links (id, merchant_id, member_user_id, provider, account_label, mode, token_ref, connected_at, last_sync_at) VALUES
    ('01J9ZD3V00000000000000CL01', pw, ravi, 'google', 'ravi@prairiewrench.ca', 'two_way', 'fake:google:seed', now() - interval '90 days', now() - interval '2 minutes');

  -- ── Orders for Prairie Wrench Parts: tonight's run R-611 (6 pm, cut-off 5:45 pm) and tomorrow 8 am (R-612) ──────
  INSERT INTO orders.delivery_windows (id, zone_id, starts_at, ends_at, cutoff_at, capacity, run_label) VALUES
    ('01J9ZD3V00000000000000DW01', NULL, (d0 + time '18:00') AT TIME ZONE z, (d0 + time '20:00') AT TIME ZONE z, (d0 + time '17:45') AT TIME ZONE z, 40, 'R-611'),
    ('01J9ZD3V00000000000000DW02', NULL, (d0 + 1 + time '08:00') AT TIME ZONE z, (d0 + 1 + time '10:00') AT TIME ZONE z, (d0 + 1 + time '07:30') AT TIME ZONE z, 40, 'R-612'),
    ('01J9ZD3V00000000000000DW00', NULL, (d0 + time '06:00') AT TIME ZONE z, (d0 + time '08:00') AT TIME ZONE z, (d0 - 1 + time '21:00') AT TIME ZONE z, 40, 'R-609');

  INSERT INTO orders.orders (id, ref, customer_id, type, state, window_id, subtotal_cents, delivery_fee_cents, service_fee_cents,
                             tax_cents, tip_cents, placed_at, delivered_at, delivery_area) VALUES
    ('01J9ZD3V00000000000000OR01', 'NL-48213', '01J9ZD3V0000000000000C0001', 'goods', 'placed', '01J9ZD3V00000000000000DW01', 3990, 499, 99, 229, 0, now() - interval '3 hours', NULL, 'Beltline'),
    ('01J9ZD3V00000000000000OR02', 'NL-48219', '01J9ZD3V0000000000000C0005', 'goods', 'placed', '01J9ZD3V00000000000000DW01', 7140, 499, 99, 387, 0, now() - interval '2 hours', NULL, 'Beltline'),
    ('01J9ZD3V00000000000000OR03', 'NL-48224', '01J9ZD3V0000000000000C0002', 'goods', 'accepted', '01J9ZD3V00000000000000DW01', 4410, 499, 99, 250, 0, now() - interval '90 minutes', NULL, 'Kensington'),
    ('01J9ZD3V00000000000000OR04', 'NL-48230', '01J9ZD3V0000000000000C0008', 'goods', 'placed', '01J9ZD3V00000000000000DW02', 6405, 499, 99, 350, 0, now() - interval '40 minutes', NULL, 'Kensington'),
    ('01J9ZD3V00000000000000OR05', 'NL-48201', '01J9ZD3V0000000000000C0003', 'goods', 'ready', '01J9ZD3V00000000000000DW01', 7140, 499, 99, 387, 0, now() - interval '5 hours', NULL, 'Inglewood'),
    ('01J9ZD3V00000000000000OR06', 'NL-48188', '01J9ZD3V0000000000000C0009', 'goods', 'delivered', '01J9ZD3V00000000000000DW00', 1995, 499, 99, 125, 0, now() - interval '1 day', (d0 + time '06:40') AT TIME ZONE z, 'Downtown');
  INSERT INTO orders.orders (id, ref, customer_id, type, state, window_id, subtotal_cents, placed_at, delivered_at, delivery_area)
  SELECT '01J9ZD3V00000000000000OD' || lpad(g::text, 2, '0'), 'NL-4817' || g, c, 'goods', 'delivered', '01J9ZD3V00000000000000DW00',
         4200, now() - interval '1 day', (d0 + time '06:30') AT TIME ZONE z + g * interval '4 minutes', a
    FROM (VALUES (1, '01J9ZD3V0000000000000C0006', 'Beltline'), (2, '01J9ZD3V0000000000000C0007', 'Kensington'),
                 (3, '01J9ZD3V0000000000000C0002', 'Kensington'), (4, '01J9ZD3V0000000000000C0005', 'Beltline'),
                 (5, '01J9ZD3V0000000000000C0001', 'Beltline'), (6, '01J9ZD3V0000000000000C0004', 'Airdrie')) AS d(g, c, a);

  INSERT INTO orders.order_lines (id, order_id, merchant_id, qty, unit_cents, state, title, packed_at, packed_by, issue_note) VALUES
    ('01J9ZD3V00000000000000OL01', '01J9ZD3V00000000000000OR01', pp, 2, 1995, 'pending', 'Wiper blades', NULL, NULL, NULL),
    ('01J9ZD3V00000000000000OL02', '01J9ZD3V00000000000000OR02', pp, 1, 7140, 'pending', 'Brake pads (front)', NULL, NULL, NULL),
    ('01J9ZD3V00000000000000OL03', '01J9ZD3V00000000000000OR03', pp, 1, 4410, 'pending', 'Synthetic oil 5 L', NULL, NULL, NULL),
    ('01J9ZD3V00000000000000OL04', '01J9ZD3V00000000000000OR04', pp, 1, 1995, 'pending', 'Wiper blades', NULL, NULL, NULL),
    ('01J9ZD3V00000000000000OL05', '01J9ZD3V00000000000000OR04', pp, 1, 4410, 'pending', 'Synthetic oil 5 L', NULL, NULL, NULL),
    ('01J9ZD3V00000000000000OL06', '01J9ZD3V00000000000000OR05', pp, 1, 7140, 'packed', 'Brake pads (rear)', now() - interval '1 hour', ravi, NULL),
    ('01J9ZD3V00000000000000OL07', '01J9ZD3V00000000000000OR06', pp, 1, 1995, 'packed', 'Wiper blades', now() - interval '20 hours', ravi, 'wrong size');
  INSERT INTO orders.order_lines (id, order_id, merchant_id, qty, unit_cents, state, title, packed_at, packed_by)
  SELECT '01J9ZD3V00000000000000OM' || lpad(g::text, 2, '0'), '01J9ZD3V00000000000000OD' || lpad(g::text, 2, '0'), pp, 1, 4200,
         'packed', 'Synthetic oil 5 L', now() - interval '20 hours', ravi
    FROM generate_series(1, 6) AS g;
END $$;
