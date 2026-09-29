-- Dev-only seed (profile `local`): kitchen workstream — Pho Dau Bo's menus, modifier groups, combos, hours, prep &
-- capacity and tonight's live orders, from design/02 Provider Studio.dc.html (menuSections, modGroups, combos, kHours,
-- kds). Order times are relative to when the seed runs. Customers, the group order and the couriers are invented.
-- Photo keys point at no stored bytes: the Studio shows its placeholder tile (docs/DECISIONS.md › Kitchen).

-- ── people: group-order host + members, couriers ─────────────────────────────────────────────────────────────────
INSERT INTO identity.users (id, phone, email, display_name, locale, status) VALUES
  ('01J9ZD3V00000000000000KF01', '+14035550301', 'kofi.mensah@example.com',  'Kofi Mensah',  'en-CA', 'active'),
  ('01J9ZD3V00000000000000KM01', '+14035550302', 'ama.boateng@example.com',  'Ama Boateng',  'en-CA', 'active'),
  ('01J9ZD3V00000000000000KM02', '+14035550303', 'yaw.darko@example.com',    'Yaw Darko',    'en-CA', 'active'),
  ('01J9ZD3V00000000000000CP01', '+14035550311', 'priya.nair@example.com',   'Priya Nair',   'en-CA', 'active'),
  ('01J9ZD3V00000000000000CS01', '+14035550312', 'sam.okafor@example.com',   'Sam Okafor',   'en-CA', 'active');

INSERT INTO fulfilment.couriers (id, user_id, vehicle, status, rating) VALUES
  ('01J9ZD3V00000000000000CR01', '01J9ZD3V00000000000000CP01', 'ebike', 'on_run', 4.95),
  ('01J9ZD3V00000000000000CR02', '01J9ZD3V00000000000000CS01', 'car',   'on_run', 4.88),
  ('01J9ZD3V00000000000000CR03', '01J9ZD3V00000000000000CS01', 'bike',  'available', 4.90);

-- AHS permit renewal date shown on Hours, prep & capacity ("renews Mar 2027").
UPDATE merchants.verifications SET expires_at = '2027-03-31T06:00:00Z' WHERE id = '01J9ZD3V0000000000000VPD03';

DO $$
DECLARE
  pdb constant text := '01J9ZD3V00000000000000PDB1';
  ravi constant text := '01J9ZD3V00000000000000RAV1';
  d0 date := (now() AT TIME ZONE 'America/Edmonton')::date;
  t0 timestamptz := now();
BEGIN
  -- ── menus → sections ────────────────────────────────────────────────────────────────────────────────────────────
  INSERT INTO food.menus (id, merchant_id, name, name_i18n, schedule, status, sort, published_at) VALUES
    ('01J9ZD3V00000000000000MN01', pdb, 'Dinner menu',   '{"en":"Dinner menu"}',   '{"mode":"open_hours","days":[]}', 'live', 0, '2026-01-10T16:00:00Z'),
    ('01J9ZD3V00000000000000MN02', pdb, 'Lunch menu',    '{"en":"Lunch menu"}',    '{"mode":"window","days":[2,3,4,5],"from":"11:00","to":"14:00"}', 'live', 1, '2026-01-10T16:00:00Z'),
    ('01J9ZD3V00000000000000MN03', pdb, 'Catering menu', '{"en":"Catering menu"}', '{"mode":"quote","days":[],"noticeHours":48}', 'draft', 2, NULL);

  INSERT INTO food.menu_sections (id, menu_id, name, name_i18n, sort) VALUES
    ('01J9ZD3V00000000000000SC01', '01J9ZD3V00000000000000MN01', 'Starters', '{"en":"Starters"}', 0),
    ('01J9ZD3V00000000000000SC02', '01J9ZD3V00000000000000MN01', 'Mains',    '{"en":"Mains"}',    1),
    ('01J9ZD3V00000000000000SC03', '01J9ZD3V00000000000000MN01', 'Drinks',   '{"en":"Drinks"}',   2),
    ('01J9ZD3V00000000000000SC04', '01J9ZD3V00000000000000MN02', 'Lunch specials', '{"en":"Lunch specials"}', 0),
    ('01J9ZD3V00000000000000SC05', '01J9ZD3V00000000000000MN03', 'Platters', '{"en":"Platters"}', 0);

  -- ── items ───────────────────────────────────────────────────────────────────────────────────────────────────────
  INSERT INTO food.menu_items (id, section_id, merchant_id, name, name_i18n, description, desc_i18n, price_cents,
         allergens, dietary, prep_add_min, daily_limit, sold_today, available, vetting, status, availability,
         combo_eligible, sold_out_on, photo_key, photo_content_type, sort, published_at) VALUES
    ('01J9ZD3V00000000000000MT01', '01J9ZD3V00000000000000SC01', pdb, 'Spring rolls (4)', '{"en":"Spring rolls (4)"}',
       'Pork & shrimp, nuoc cham', '{"en":"Pork & shrimp, nuoc cham"}', 900, '{shellfish,wheat}', '{}', 0, NULL, 0,
       true, 'approved', 'published', 'always', true, NULL, 'seed/spring-rolls.jpg', 'image/jpeg', 0, '2026-01-10T16:00:00Z'),
    ('01J9ZD3V00000000000000MT02', '01J9ZD3V00000000000000SC01', pdb, 'Fresh salad rolls (2)', '{"en":"Fresh salad rolls (2)"}',
       'Shrimp, herbs, peanut sauce', '{"en":"Shrimp, herbs, peanut sauce"}', 800, '{peanuts,shellfish}', '{vegan_option}', 0, NULL, 0,
       true, 'approved', 'published', 'always', true, NULL, 'seed/salad-rolls.jpg', 'image/jpeg', 1, '2026-01-10T16:00:00Z'),
    ('01J9ZD3V00000000000000MT03', '01J9ZD3V00000000000000SC02', pdb, 'Pho dac biet', '{"en":"Pho dac biet"}',
       'Rare beef, brisket, tendon, meatball · 16-hour broth', '{"en":"Rare beef, brisket, tendon, meatball · 16-hour broth"}',
       1700, '{}', '{gluten_free,popular}', 0, 40, 18,
       true, 'approved', 'published', 'always', true, NULL, 'seed/pho-dac-biet.jpg', 'image/jpeg', 0, '2026-01-10T16:00:00Z'),
    ('01J9ZD3V00000000000000MT04', '01J9ZD3V00000000000000SC02', pdb, 'Bun bo Hue', '{"en":"Bun bo Hue"}',
       'Spicy lemongrass beef noodle', '{"en":"Spicy lemongrass beef noodle"}', 1800, '{shellfish}', '{spicy}', 5, NULL, 0,
       true, 'approved', 'published', 'always', true, NULL, 'seed/bun-bo-hue.jpg', 'image/jpeg', 1, '2026-01-10T16:00:00Z'),
    ('01J9ZD3V00000000000000MT05', '01J9ZD3V00000000000000SC02', pdb, 'Vegan pho', '{"en":"Vegan pho"}',
       'Mushroom broth, tofu, greens', '{"en":"Mushroom broth, tofu, greens"}', 1600, '{soy}', '{vegan,gluten_free}', 0, NULL, 0,
       true, 'approved', 'published', 'always', true, NULL, 'seed/vegan-pho.jpg', 'image/jpeg', 2, '2026-01-10T16:00:00Z'),
    ('01J9ZD3V00000000000000MT06', '01J9ZD3V00000000000000SC03', pdb, 'Vietnamese iced coffee', '{"en":"Vietnamese iced coffee"}',
       'Cà phê sữa đá', '{"en":"Cà phê sữa đá"}', 550, '{milk}', '{}', 0, NULL, 0,
       true, 'approved', 'published', 'always', true, NULL, 'seed/iced-coffee.jpg', 'image/jpeg', 0, '2026-01-10T16:00:00Z'),
    ('01J9ZD3V00000000000000MT07', '01J9ZD3V00000000000000SC03', pdb, 'Fresh lime soda', '{"en":"Fresh lime soda"}',
       NULL, NULL, 450, '{}', '{vegan}', 0, NULL, 0,
       false, 'approved', 'published', 'always', true, d0, 'seed/lime-soda.jpg', 'image/jpeg', 1, '2026-01-10T16:00:00Z'),
    ('01J9ZD3V00000000000000MT08', '01J9ZD3V00000000000000SC04', pdb, 'Small pho dac biet', '{"en":"Small pho dac biet"}',
       'Lunch size, same 16-hour broth', '{"en":"Lunch size, same 16-hour broth"}', 1550, '{}', '{gluten_free}', 0, NULL, 0,
       true, 'approved', 'published', 'lunch', true, NULL, 'seed/small-pho.jpg', 'image/jpeg', 0, '2026-01-10T16:00:00Z');

  -- ── modifier groups → options → items ───────────────────────────────────────────────────────────────────────────
  INSERT INTO food.modifier_groups (id, merchant_id, name, name_i18n, pick_rule, pick_count, required, min_select,
         max_select, show_for_option_ids, sort) VALUES
    ('01J9ZD3V00000000000000MG01', pdb, 'Size',      '{"en":"Size"}',      'exactly', 1, true,  1, 1, '{}', 0),
    ('01J9ZD3V00000000000000MG02', pdb, 'Noodles',   '{"en":"Noodles"}',   'exactly', 1, false, 0, 1, '{}', 1),
    ('01J9ZD3V00000000000000MG03', pdb, 'Extras',    '{"en":"Extras"}',    'up_to',   4, false, 0, 4, '{}', 2),
    ('01J9ZD3V00000000000000MG04', pdb, 'Sweetness', '{"en":"Sweetness"}', 'exactly', 1, false, 0, 1, '{}', 3);

  INSERT INTO food.modifier_options (id, group_id, name, name_i18n, price_delta_cents, is_default, sold_out, sort) VALUES
    ('01J9ZD3V00000000000000MP01', '01J9ZD3V00000000000000MG01', 'Regular',       '{"en":"Regular"}',       0,   true,  false, 0),
    ('01J9ZD3V00000000000000MP02', '01J9ZD3V00000000000000MG01', 'Large',         '{"en":"Large"}',         300, false, false, 1),
    ('01J9ZD3V00000000000000MP03', '01J9ZD3V00000000000000MG02', 'Rice noodles',  '{"en":"Rice noodles"}',  0,   true,  false, 0),
    ('01J9ZD3V00000000000000MP04', '01J9ZD3V00000000000000MG02', 'Extra noodles', '{"en":"Extra noodles"}', 200, false, false, 1),
    ('01J9ZD3V00000000000000MP05', '01J9ZD3V00000000000000MG03', 'Extra beef',    '{"en":"Extra beef"}',    400, false, false, 0),
    ('01J9ZD3V00000000000000MP06', '01J9ZD3V00000000000000MG03', 'Extra broth',   '{"en":"Extra broth"}',   200, false, false, 1),
    ('01J9ZD3V00000000000000MP07', '01J9ZD3V00000000000000MG03', 'No onion',      '{"en":"No onion"}',      0,   false, false, 2),
    ('01J9ZD3V00000000000000MP08', '01J9ZD3V00000000000000MG03', 'Extra chili',   '{"en":"Extra chili"}',   0,   false, false, 3),
    ('01J9ZD3V00000000000000MP09', '01J9ZD3V00000000000000MG04', 'Regular',       '{"en":"Regular"}',       0,   true,  false, 0),
    ('01J9ZD3V00000000000000MP10', '01J9ZD3V00000000000000MG04', 'Less sweet',    '{"en":"Less sweet"}',    0,   false, false, 1),
    ('01J9ZD3V00000000000000MP11', '01J9ZD3V00000000000000MG04', 'No sugar',      '{"en":"No sugar"}',      0,   false, false, 2);

  INSERT INTO food.item_modifiers (item_id, group_id, sort) VALUES
    ('01J9ZD3V00000000000000MT01', '01J9ZD3V00000000000000MG03', 0),
    ('01J9ZD3V00000000000000MT03', '01J9ZD3V00000000000000MG01', 0),
    ('01J9ZD3V00000000000000MT03', '01J9ZD3V00000000000000MG02', 1),
    ('01J9ZD3V00000000000000MT03', '01J9ZD3V00000000000000MG03', 2),
    ('01J9ZD3V00000000000000MT04', '01J9ZD3V00000000000000MG01', 0),
    ('01J9ZD3V00000000000000MT04', '01J9ZD3V00000000000000MG03', 1),
    ('01J9ZD3V00000000000000MT05', '01J9ZD3V00000000000000MG01', 0),
    ('01J9ZD3V00000000000000MT05', '01J9ZD3V00000000000000MG02', 1),
    ('01J9ZD3V00000000000000MT06', '01J9ZD3V00000000000000MG04', 0);

  -- ── combos & deals ──────────────────────────────────────────────────────────────────────────────────────────────
  INSERT INTO food.combos (id, merchant_id, name, name_i18n, rules, pricing, price_cents, discount_bps, schedule, status,
         swaps_allowed) VALUES
    ('01J9ZD3V00000000000000CB01', pdb, 'Pho for two', '{"en":"Pho for two"}',
       '{"slots":[{"label":"Any 2 mains","qty":2,"sectionId":"01J9ZD3V00000000000000SC02","itemIds":[]},{"label":"2 spring rolls","qty":2,"sectionId":null,"itemIds":["01J9ZD3V00000000000000MT01"]},{"label":"2 drinks","qty":2,"sectionId":"01J9ZD3V00000000000000SC03","itemIds":[]}]}',
       'fixed', 5200, NULL, NULL, 'live', true),
    ('01J9ZD3V00000000000000CB02', pdb, 'Lunch special · 11–2', '{"en":"Lunch special · 11–2"}',
       '{"slots":[{"label":"Small pho","qty":1,"sectionId":null,"itemIds":["01J9ZD3V00000000000000MT08"]},{"label":"iced coffee","qty":1,"sectionId":null,"itemIds":["01J9ZD3V00000000000000MT06"]}]}',
       'fixed', 1800, NULL, '{"days":[2,3,4,5],"from":"11:00","to":"14:00"}', 'scheduled', false),
    ('01J9ZD3V00000000000000CB03', pdb, 'Family feast', '{"en":"Family feast"}',
       '{"slots":[{"label":"4 mains","qty":4,"sectionId":"01J9ZD3V00000000000000SC02","itemIds":[]},{"label":"8 rolls","qty":2,"sectionId":null,"itemIds":["01J9ZD3V00000000000000MT01"]},{"label":"4 drinks","qty":4,"sectionId":"01J9ZD3V00000000000000SC03","itemIds":[]}]}',
       'fixed', 9800, NULL, '{"days":[7],"from":"17:00","to":"20:00"}', 'draft', true);

  INSERT INTO food.kitchen_promos (merchant_id, promo, enabled, updated_by) VALUES (pdb, 'points_3x', true, ravi);

  -- ── hours, prep & capacity ──────────────────────────────────────────────────────────────────────────────────────
  INSERT INTO food.kitchen_settings (merchant_id, default_prep_min, max_orders_per_15, paused_until, fulfilment, radius_km,
         group_orders, prep_bump_min, large_order_cents, large_order_add_min, auto_pause_late, group_max, scheduled_days,
         delivery_areas) VALUES
    (pdb, 25, 6, NULL, '{courier,pickup,scheduled}', 6, true, 0, 12000, 15, 3, 12, 7,
     '{Beltline,Kensington,Inglewood,Downtown}');

  INSERT INTO food.opening_hours (merchant_id, weekday, ranges, note) VALUES
    (pdb, 1, '[]', NULL),
    (pdb, 2, '[["11:00","21:00"]]', 'lunch special 11–2'),
    (pdb, 3, '[["11:00","21:00"]]', NULL),
    (pdb, 4, '[["11:00","21:00"]]', NULL),
    (pdb, 5, '[["11:00","22:00"]]', NULL),
    (pdb, 6, '[["12:00","22:00"]]', NULL),
    (pdb, 7, '[["12:00","20:00"]]', 'family feast only after 5');

  -- ── tonight's live orders (design kds) ──────────────────────────────────────────────────────────────────────────
  INSERT INTO orders.group_orders (id, host_user_id, merchant_id, link_code, member_user_ids, locks_at) VALUES
    ('01J9ZD3V00000000000000GR01', '01J9ZD3V00000000000000KF01', pdb, 'pho-kofi-7q2',
     ARRAY['01J9ZD3V00000000000000KF01', '01J9ZD3V00000000000000KM01', '01J9ZD3V00000000000000KM02'], t0 - interval '5 minutes');

  INSERT INTO orders.orders (id, ref, customer_id, type, state, fulfilment_mode, customer_eta, group_order_id, placed_at,
         subtotal_cents, delivery_fee_cents, service_fee_cents, tax_cents, tip_cents, delivery_area) VALUES
    ('01J9ZD3V00000000000000FD01', 'FD-9931', '01J9ZD3V0000000000000C0001', 'food', 'placed',   'delivery', NULL,
       NULL, t0 - interval '6 minutes', 3800, 399, 150, 190, 500, 'Beltline'),
    ('01J9ZD3V00000000000000FD02', 'FD-9932', '01J9ZD3V00000000000000KF01', 'food', 'placed',   'delivery', NULL,
       '01J9ZD3V00000000000000GR01', t0 - interval '4 minutes', 8650, 399, 150, 432, 800, 'Kensington'),
    ('01J9ZD3V00000000000000FD03', 'FD-9928', '01J9ZD3V0000000000000C0005', 'food', 'accepted', 'pickup', t0 + interval '5 minutes',
       NULL, t0 - interval '12 minutes', 1600, 0, 50, 80, 0, NULL),
    ('01J9ZD3V00000000000000FD04', 'FD-9925', '01J9ZD3V0000000000000C0002', 'food', 'ready',    'delivery', NULL,
       NULL, t0 - interval '19 minutes', 3400, 399, 150, 170, 400, 'Inglewood');

  INSERT INTO orders.order_lines (id, order_id, merchant_id, menu_item_id, qty, unit_cents, modifiers, title, state) VALUES
    ('01J9ZD3V00000000000000FN01', '01J9ZD3V00000000000000FD01', pdb, '01J9ZD3V00000000000000MT03', 1, 2400, '[{"name":"Large"},{"name":"extra beef"}]', 'Pho dac biet', 'pending'),
    ('01J9ZD3V00000000000000FN02', '01J9ZD3V00000000000000FD01', pdb, '01J9ZD3V00000000000000MT01', 2, 900,  '[]', 'Spring rolls', 'pending'),
    ('01J9ZD3V00000000000000FN03', '01J9ZD3V00000000000000FD02', pdb, '01J9ZD3V00000000000000MT04', 3, 1800, '[]', 'Bun bo Hue', 'pending'),
    ('01J9ZD3V00000000000000FN04', '01J9ZD3V00000000000000FD02', pdb, '01J9ZD3V00000000000000MT05', 1, 1600, '[{"name":"no onion"}]', 'Vegan pho', 'pending'),
    ('01J9ZD3V00000000000000FN05', '01J9ZD3V00000000000000FD02', pdb, '01J9ZD3V00000000000000MT06', 3, 550,  '[]', 'Iced coffee', 'pending'),
    ('01J9ZD3V00000000000000FN06', '01J9ZD3V00000000000000FD03', pdb, '01J9ZD3V00000000000000MT05', 1, 1600, '[]', 'Vegan pho', 'pending'),
    ('01J9ZD3V00000000000000FN07', '01J9ZD3V00000000000000FD04', pdb, '01J9ZD3V00000000000000MT03', 2, 1700, '[{"name":"Regular"}]', 'Pho dac biet', 'pending');

  INSERT INTO food.kitchen_tickets (order_id, merchant_id, stage, prep_min, accepted_at, accepted_by, ready_by, ready_at) VALUES
    ('01J9ZD3V00000000000000FD03', pdb, 'cooking', 25, t0 - interval '11 minutes', ravi, t0 + interval '14 minutes', NULL),
    ('01J9ZD3V00000000000000FD04', pdb, 'ready',   25, t0 - interval '18 minutes', ravi, t0 + interval '7 minutes', t0 - interval '1 minute');

  -- courier legs: Priya is on her way to FD-9931, a courier is assigned to FD-9932, Sam is waiting for FD-9925
  INSERT INTO fulfilment.runs (id, courier_id, kind, state, route) VALUES
    ('01J9ZD3V00000000000000RN01', '01J9ZD3V00000000000000CR01', 'direct', 'en_route', '[]'),
    ('01J9ZD3V00000000000000RN02', '01J9ZD3V00000000000000CR03', 'direct', 'planned',  '[]'),
    ('01J9ZD3V00000000000000RN03', '01J9ZD3V00000000000000CR02', 'direct', 'loading',  '[]');
  INSERT INTO fulfilment.stops (id, run_id, order_id, kind, seq, eta, arrived_at) VALUES
    ('01J9ZD3V00000000000000ST01', '01J9ZD3V00000000000000RN01', '01J9ZD3V00000000000000FD01', 'pickup', 0, t0 + interval '8 minutes', NULL),
    ('01J9ZD3V00000000000000ST02', '01J9ZD3V00000000000000RN02', '01J9ZD3V00000000000000FD02', 'pickup', 0, NULL, NULL),
    ('01J9ZD3V00000000000000ST03', '01J9ZD3V00000000000000RN03', '01J9ZD3V00000000000000FD04', 'pickup', 0, t0 - interval '2 minutes', t0 - interval '1 minute');
END $$;
