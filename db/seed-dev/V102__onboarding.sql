-- Dev-only seed (profile `local`): onboarding answers, verification checklists and storefronts for the V100 personas,
-- so Studio → Business page / Store / Menu page shows the design's data (design 02 siteDefs / libs) and the
-- onboarding endpoints return a complete, approved application. Category ids are CategorySeeder slugs (run
-- `./gradlew :api:seedCategories` against the same DB for names).

UPDATE merchants.merchants SET
  province = 'AB', onboarding_step = 'done', created_by = '01J9ZD3V00000000000000RAV1',
  business_terms_accepted_at = '2026-01-05T17:00:00Z', submitted_at = '2026-01-06T17:14:00Z', approved_at = '2026-01-07T18:00:00Z',
  business_number = '784512369', registry_ref = '2021456789', registry_jurisdiction = 'AB',
  legal_details = '{"structure":"corp_ab","legal_corporate_name":"Prairie Wrench Mobile Mechanics Ltd.","alberta_corporate_access_number":"2021456789","business_number":"784512369","incorporation_date":"2019-04-02","registered_office":"1208 17 Ave SW, Calgary AB T2T 0B7","certificate_of_incorporation_doc":"01J9ZD3V0000000000000D0C01","operating_name":"Prairie Wrench"}',
  profile = '{"yearsOperating":"6-10","teamSize":"2-4","serviceArea":"Calgary + 40 km · Airdrie, Cochrane","workLocations":["customer"],"languages":["en","pa"],"licenceNumbers":"AMVIC 44812 · Red Seal","description":"Mobile mechanic since 2019. Red Seal, AMVIC-licensed. We come to your driveway or office lot in Calgary and Airdrie; most jobs done same day.","inventorySources":[],"perishables":[],"cuisines":[],"fulfilment":[],"dietary":[]}'
WHERE id = '01J9ZD3V00000000000000PWM1';

UPDATE merchants.merchants SET
  province = 'AB', onboarding_step = 'done', created_by = '01J9ZD3V00000000000000RAV1',
  business_terms_accepted_at = '2026-01-05T17:01:00Z', submitted_at = '2026-01-06T17:20:00Z', approved_at = '2026-01-07T18:05:00Z',
  registry_ref = '2021456789', registry_jurisdiction = 'AB',
  legal_details = '{"structure":"corp_ab","legal_corporate_name":"Prairie Wrench Mobile Mechanics Ltd.","alberta_corporate_access_number":"2021456789","business_number":"784512369","incorporation_date":"2019-04-02","registered_office":"1208 17 Ave SW, Calgary AB T2T 0B7","certificate_of_incorporation_doc":"01J9ZD3V0000000000000D0C01","operating_name":"Prairie Wrench Parts"}',
  profile = '{"yearsOperating":"3-5","productCount":"101-1000","pickupAddress":"4410 Manhattan Rd SE, Calgary","sameDayCutoff":"17:45","inventorySources":["manual","shopify"],"perishables":["none"],"languages":["en"],"description":"OEM and aftermarket parts, tires and fluids — same-day pooled delivery across Calgary before 5:45 pm.","workLocations":[],"cuisines":[],"fulfilment":[],"dietary":[]}'
WHERE id = '01J9ZD3V00000000000000PWP1';

UPDATE merchants.merchants SET
  province = 'AB', onboarding_step = 'done', created_by = '01J9ZD3V00000000000000RAV1',
  business_terms_accepted_at = '2026-01-05T17:02:00Z', submitted_at = '2026-01-06T17:30:00Z', approved_at = '2026-01-09T18:00:00Z',
  legal_details = '{"structure":"sole","owner_legal_name":"Ravi Sandhu","trade_name":"Pho Dau Bo","trade_name_registration":"TN-2004-118840","sin_collected_by_stripe":true,"address":"3715 17 Ave SE, Calgary AB T2A 0S1"}',
  profile = '{"cuisines":["vietnamese"],"kitchenAddress":"3715 17 Ave SE, Calgary AB T2A 0S1","ahsPermitNumber":"FS-2024-88120","cityLicenceNumber":"BL 22-118840","seats":"21-60","certifiedHandlers":"3-5","fulfilment":["hot_courier","pickup","catering"],"dietary":["halal","vegan"],"alcohol":"none","languages":["en","fr","vi"],"description":"Family-run Vietnamese kitchen in Forest Lawn since 2004. Broth simmered 18 hours. Halal beef, vegan pho available.","workLocations":[],"inventorySources":[],"perishables":[]}'
WHERE id = '01J9ZD3V00000000000000PDB1';

INSERT INTO merchants.documents (id, merchant_id, purpose, file_name, content_type, size_bytes, storage_key, uploaded_by, created_at) VALUES
  ('01J9ZD3V0000000000000D0C01', '01J9ZD3V00000000000000PWM1', 'legal',        'certificate-of-incorporation.pdf', 'application/pdf', 48211, 'seed/certificate-of-incorporation.pdf', '01J9ZD3V00000000000000RAV1', '2026-01-05T17:05:00Z'),
  ('01J9ZD3V0000000000000D0C02', '01J9ZD3V00000000000000PWM1', 'verification', 'liability-insurance-2027.pdf',     'application/pdf', 91342, 'seed/liability-insurance-2027.pdf',     '01J9ZD3V00000000000000RAV1', '2026-01-05T17:06:00Z'),
  ('01J9ZD3V0000000000000D0C03', '01J9ZD3V00000000000000PDB1', 'verification', 'ahs-inspection-aug-2026.pdf',      'application/pdf', 73120, 'seed/ahs-inspection-aug-2026.pdf',      '01J9ZD3V00000000000000RAV1', '2026-01-05T17:07:00Z');

INSERT INTO merchants.merchant_principals (id, merchant_id, legal_name, role, ownership_pct) VALUES
  ('01J9ZD3V0000000000000PRN01', '01J9ZD3V00000000000000PWM1', 'Ravi Sandhu',  'director',    60),
  ('01J9ZD3V0000000000000PRN02', '01J9ZD3V00000000000000PWM1', 'Priya Sandhu', 'shareholder', 40),
  ('01J9ZD3V0000000000000PRN03', '01J9ZD3V00000000000000PWP1', 'Ravi Sandhu',  'director',    60),
  ('01J9ZD3V0000000000000PRN04', '01J9ZD3V00000000000000PWP1', 'Priya Sandhu', 'shareholder', 40),
  ('01J9ZD3V0000000000000PRN05', '01J9ZD3V00000000000000PDB1', 'Ravi Sandhu',  'owner',       100);

INSERT INTO merchants.merchant_categories (merchant_id, category_id, status, suggested_name) VALUES
  ('01J9ZD3V00000000000000PWM1', 'service.automotive.mobile-mechanic',          'approved', NULL),
  ('01J9ZD3V00000000000000PWM1', 'service.automotive.brakes-and-suspension',     'approved', NULL),
  ('01J9ZD3V00000000000000PWM1', 'service.automotive.oil-change-and-fluids',     'approved', NULL),
  ('01J9ZD3V00000000000000PWM1', 'service.automotive.tire-change-and-storage',   'approved', NULL),
  ('01J9ZD3V00000000000000PWP1', 'shop.hardware-and-auto.auto-parts',            'approved', NULL),
  ('01J9ZD3V00000000000000PWP1', 'shop.hardware-and-auto.tires',                 'approved', NULL),
  ('01J9ZD3V00000000000000PDB1', 'food.format.restaurant-dine-in-and-takeout',   'approved', NULL),
  ('01J9ZD3V00000000000000PDB1', 'food.service.caterer',                         'approved', NULL);

-- Checklists (all passed), in design order.
INSERT INTO merchants.verifications (id, merchant_id, check_key, check_type, registry, status, reference, document_media_id, expires_at, position, verified_by) VALUES
  ('01J9ZD3V0000000000000VPW01', '01J9ZD3V00000000000000PWM1', 'kyc',           'kyc',        NULL,    'verified', 'passed',       NULL, NULL, 0, 'stripe'),
  ('01J9ZD3V0000000000000VPW02', '01J9ZD3V00000000000000PWM1', 'registry',      'registry',   NULL,    'verified', '2021456789',   NULL, NULL, 1, 'registry'),
  ('01J9ZD3V0000000000000VPW03', '01J9ZD3V00000000000000PWM1', 'licence:AMVIC', 'licence',    'AMVIC', 'verified', '44812',        NULL, NULL, 2, 'registry'),
  ('01J9ZD3V0000000000000VPW04', '01J9ZD3V00000000000000PWM1', 'insurance',     'insurance',  NULL,    'verified', NULL, '01J9ZD3V0000000000000D0C02', '2027-03-31T06:00:00Z', 3, 'agent'),
  ('01J9ZD3V0000000000000VPW05', '01J9ZD3V00000000000000PWM1', 'bank',          'bank',       NULL,    'verified', 'TD ··3391',    NULL, NULL, 4, 'stripe'),
  ('01J9ZD3V0000000000000VPW06', '01J9ZD3V00000000000000PWM1', 'mfa',           'mfa',        NULL,    'verified', 'second_factor', NULL, NULL, 5, 'system'),
  ('01J9ZD3V0000000000000VPP01', '01J9ZD3V00000000000000PWP1', 'kyc',              'kyc',         NULL,  'verified', 'passed',     NULL, NULL, 0, 'stripe'),
  ('01J9ZD3V0000000000000VPP02', '01J9ZD3V00000000000000PWP1', 'registry',         'registry',    NULL,  'verified', '2021456789', NULL, NULL, 1, 'registry'),
  ('01J9ZD3V0000000000000VPP03', '01J9ZD3V00000000000000PWP1', 'gst',              'registry',    'CRA', 'verified', '784512369 RT0001', NULL, NULL, 2, 'registry'),
  ('01J9ZD3V0000000000000VPP04', '01J9ZD3V00000000000000PWP1', 'category_permits', 'licence',     NULL,  'verified', 'none',       NULL, NULL, 3, 'agent'),
  ('01J9ZD3V0000000000000VPP05', '01J9ZD3V00000000000000PWP1', 'product_safety',   'attestation', NULL,  'verified', 'signed',     NULL, NULL, 4, 'owner'),
  ('01J9ZD3V0000000000000VPP06', '01J9ZD3V00000000000000PWP1', 'returns_policy',   'attestation', NULL,  'verified', 'standard',   NULL, NULL, 5, 'owner'),
  ('01J9ZD3V0000000000000VPP07', '01J9ZD3V00000000000000PWP1', 'bank',             'bank',        NULL,  'verified', 'TD ··3391',  NULL, NULL, 6, 'stripe'),
  ('01J9ZD3V0000000000000VPP08', '01J9ZD3V00000000000000PWP1', 'mfa',              'mfa',         NULL,  'verified', 'second_factor', NULL, NULL, 7, 'system'),
  ('01J9ZD3V0000000000000VPD01', '01J9ZD3V00000000000000PDB1', 'kyc',                  'kyc',         NULL,   'verified', 'passed',          NULL, NULL, 0, 'stripe'),
  ('01J9ZD3V0000000000000VPD02', '01J9ZD3V00000000000000PDB1', 'registry',             'registry',    NULL,   'verified', 'BL 22-118840',    NULL, NULL, 1, 'registry'),
  ('01J9ZD3V0000000000000VPD03', '01J9ZD3V00000000000000PDB1', 'ahs_permit',           'ahs_permit',  'AHS',  'verified', 'FS-2024-88120',   NULL, NULL, 2, 'registry'),
  ('01J9ZD3V0000000000000VPD04', '01J9ZD3V00000000000000PDB1', 'food_cert',            'food_cert',   NULL,   'verified', '3 staff',         NULL, NULL, 3, 'agent'),
  ('01J9ZD3V0000000000000VPD05', '01J9ZD3V00000000000000PDB1', 'inspection',           'inspection',  'AHS',  'verified', NULL, '01J9ZD3V0000000000000D0C03', NULL, 4, 'agent'),
  ('01J9ZD3V0000000000000VPD06', '01J9ZD3V00000000000000PDB1', 'insurance',            'insurance',   NULL,   'verified', NULL,              NULL, '2027-05-31T06:00:00Z', 5, 'agent'),
  ('01J9ZD3V0000000000000VPD07', '01J9ZD3V00000000000000PDB1', 'allergen_attestation', 'attestation', NULL,   'verified', 'signed',          NULL, NULL, 6, 'owner'),
  ('01J9ZD3V0000000000000VPD08', '01J9ZD3V00000000000000PDB1', 'aglc',                 'licence',     'AGLC', 'verified', 'not_applicable',  NULL, NULL, 7, 'owner'),
  ('01J9ZD3V0000000000000VPD09', '01J9ZD3V00000000000000PDB1', 'gst',                  'registry',    'CRA',  'verified', '811204455 RT0001', NULL, NULL, 8, 'registry'),
  ('01J9ZD3V0000000000000VPD10', '01J9ZD3V00000000000000PDB1', 'bank',                 'bank',        NULL,   'verified', 'TD ··3391',       NULL, NULL, 9, 'stripe'),
  ('01J9ZD3V0000000000000VPD11', '01J9ZD3V00000000000000PDB1', 'mfa',                  'mfa',         NULL,   'verified', 'second_factor',   NULL, NULL, 10, 'system'),
  ('01J9ZD3V0000000000000VPD12', '01J9ZD3V00000000000000PDB1', 'site_visit',           'site_visit',  NULL,   'verified', '2026-01-08T17:00:00Z', NULL, NULL, 11, 'agent');

UPDATE merchants.merchant_principals SET kyc_verification_id = '01J9ZD3V0000000000000VPW01' WHERE merchant_id = '01J9ZD3V00000000000000PWM1';
UPDATE merchants.merchant_principals SET kyc_verification_id = '01J9ZD3V0000000000000VPP01' WHERE merchant_id = '01J9ZD3V00000000000000PWP1';
UPDATE merchants.merchant_principals SET kyc_verification_id = '01J9ZD3V0000000000000VPD01' WHERE merchant_id = '01J9ZD3V00000000000000PDB1';

-- Storefronts (design 02 siteDefs): Prairie Wrench business page (published), the parts store and the menu page.
INSERT INTO merchants.storefronts (id, merchant_id, slug, page_kind, brand_color, tagline_i18n, cta_label, announcement_i18n, published_at, created_at, updated_at) VALUES
  ('01J9ZD3V0000000000000SFPW1', '01J9ZD3V00000000000000PWM1', 'prairie-wrench',       'business_page', '#2f5d3a', '{"en":"Mobile mechanic · Calgary & Airdrie"}',             'book_visit', '{"en":"Winter tire swaps: book before Oct 15 for $99"}', '2026-01-08T16:00:00Z', '2026-01-06T18:00:00Z', '2026-01-08T16:00:00Z'),
  ('01J9ZD3V0000000000000SFPP1', '01J9ZD3V00000000000000PWP1', 'prairie-wrench-parts', 'store',         '#3b4a5a', '{"en":"Parts & tires · same-day delivery in Calgary"}',     'order_now',  NULL, '2026-01-08T16:05:00Z', '2026-01-06T18:05:00Z', '2026-01-08T16:05:00Z'),
  ('01J9ZD3V0000000000000SFPD1', '01J9ZD3V00000000000000PDB1', 'pho-dau-bo',           'menu_page',     '#9a4a1f', '{"en":"Vietnamese · Forest Lawn · since 2004"}',           'order_now',  NULL, '2026-01-10T16:00:00Z', '2026-01-06T18:10:00Z', '2026-01-10T16:00:00Z');

INSERT INTO merchants.storefront_sections (id, storefront_id, kind, position, enabled, settings) VALUES
  ('01J9ZD3V000000000000SPW001', '01J9ZD3V0000000000000SFPW1', 'hero',     0, true,  '{}'),
  ('01J9ZD3V000000000000SPW002', '01J9ZD3V0000000000000SFPW1', 'about',    1, true,  '{}'),
  ('01J9ZD3V000000000000SPW003', '01J9ZD3V0000000000000SFPW1', 'services', 2, true,  '{}'),
  ('01J9ZD3V000000000000SPW004', '01J9ZD3V0000000000000SFPW1', 'reviews',  3, true,  '{}'),
  ('01J9ZD3V000000000000SPW005', '01J9ZD3V0000000000000SFPW1', 'area',     4, true,  '{}'),
  ('01J9ZD3V000000000000SPW006', '01J9ZD3V0000000000000SFPW1', 'gallery',  5, false, '{}'),
  ('01J9ZD3V000000000000SPW007', '01J9ZD3V0000000000000SFPW1', 'faq',      6, true,  '{"pairs":[{"q":"Do you come to my workplace?","a":"Yes — any lot where we can park safely."},{"q":"What if you find something else?","a":"We send an itemized quote in the app; nothing extra happens without your approval."}]}'),
  ('01J9ZD3V000000000000SPW008', '01J9ZD3V0000000000000SFPW1', 'cta',      7, true,  '{}'),
  ('01J9ZD3V000000000000SPP001', '01J9ZD3V0000000000000SFPP1', 'hero',      0, true, '{}'),
  ('01J9ZD3V000000000000SPP002', '01J9ZD3V0000000000000SFPP1', 'about',     1, true, '{}'),
  ('01J9ZD3V000000000000SPP003', '01J9ZD3V0000000000000SFPP1', 'featured',  2, true, '{}'),
  ('01J9ZD3V000000000000SPP004', '01J9ZD3V0000000000000SFPP1', 'catalogue', 3, true, '{}'),
  ('01J9ZD3V000000000000SPP005', '01J9ZD3V0000000000000SFPP1', 'delivery',  4, true, '{}'),
  ('01J9ZD3V000000000000SPP006', '01J9ZD3V0000000000000SFPP1', 'reviews',   5, true, '{}'),
  ('01J9ZD3V000000000000SPP007', '01J9ZD3V0000000000000SFPP1', 'policies',  6, true, '{"returns_text":"Returns within 14 days for unused parts in original packaging. Electrical parts are final sale once installed."}'),
  ('01J9ZD3V000000000000SPP008', '01J9ZD3V0000000000000SFPP1', 'cta',       7, true, '{}'),
  ('01J9ZD3V000000000000SPD001', '01J9ZD3V0000000000000SFPD1', 'hero',    0, true, '{}'),
  ('01J9ZD3V000000000000SPD002', '01J9ZD3V0000000000000SFPD1', 'about',   1, true, '{}'),
  ('01J9ZD3V000000000000SPD003', '01J9ZD3V0000000000000SFPD1', 'menu',    2, true, '{}'),
  ('01J9ZD3V000000000000SPD004', '01J9ZD3V0000000000000SFPD1', 'hours',   3, true, '{}'),
  ('01J9ZD3V000000000000SPD005', '01J9ZD3V0000000000000SFPD1', 'fulfil',  4, true, '{}'),
  ('01J9ZD3V000000000000SPD006', '01J9ZD3V0000000000000SFPD1', 'reviews', 5, true, '{}'),
  ('01J9ZD3V000000000000SPD007', '01J9ZD3V0000000000000SFPD1', 'permit',  6, true, '{}'),
  ('01J9ZD3V000000000000SPD008', '01J9ZD3V0000000000000SFPD1', 'cta',     7, true, '{}');
