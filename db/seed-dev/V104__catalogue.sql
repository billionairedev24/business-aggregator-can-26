-- Dev seed (profile `local` only) — catalogue: the nine listings of design/02 Provider Studio (`rawProducts`),
-- services on Prairie Wrench (provider) and products on Prairie Wrench Parts (seller), the shared catalogue record
-- NL-P-88120 the wiper-blade listing is matched to (14 sellers, brand-locked images), and the import history.
--
-- catalogue.categories is filled by `./gradlew :api:seedCategories` (after migrations), but offers/services have FKs
-- into it — so the leaves used here are inserted first (ON CONFLICT DO NOTHING; the seeder later upserts them).

INSERT INTO catalogue.categories (id, parent_id, root, name_i18n, regulated_registry) VALUES
  ('service.automotive', NULL, 'service', '{"en": "Automotive"}', NULL),
  ('service.automotive.mobile-mechanic', 'service.automotive', 'service', '{"en": "Mobile mechanic"}', 'AMVIC'),
  ('service.automotive.oil-change-and-fluids', 'service.automotive', 'service', '{"en": "Oil change & fluids"}', 'AMVIC'),
  ('service.automotive.brakes-and-suspension', 'service.automotive', 'service', '{"en": "Brakes & suspension"}', 'AMVIC'),
  ('service.automotive.tire-change-and-storage', 'service.automotive', 'service', '{"en": "Tire change & storage"}', NULL),
  ('service.automotive.pre-purchase-inspection', 'service.automotive', 'service', '{"en": "Pre-purchase inspection"}', 'AMVIC'),
  ('shop.hardware-and-auto', NULL, 'shop', '{"en": "Hardware & auto"}', NULL),
  ('shop.hardware-and-auto.auto-parts', 'shop.hardware-and-auto', 'shop', '{"en": "Auto parts"}', NULL)
ON CONFLICT (id) DO NOTHING;

-- ── Prairie Wrench · services ──────────────────────────────────────────────────────────────────────────────────────
INSERT INTO catalogue.services
  (id, merchant_id, category_id, name, name_i18n, sku, included, pricing_mode, price_cents, duration_min, buffer_min, instant_book, vetting, status, sales_30d, submitted_at, created_at, updated_at) VALUES
  ('01J9ZD3V0000000000000SVCBI', '01J9ZD3V00000000000000PWM1', 'service.automotive.brakes-and-suspension', 'Brake inspection', '{"en": "Brake inspection"}', 'SVC-BI',
   'Pads, rotors, calipers and lines checked on all four wheels; written report with photos. Parts quoted separately.', 'fixed', 8900, 60, 20, true, 'approved', 'live', 31, '2026-03-04T17:00:00Z', '2026-03-04T16:00:00Z', '2026-03-04T17:00:00Z'),
  ('01J9ZD3V0000000000000SVCOF', '01J9ZD3V00000000000000PWM1', 'service.automotive.oil-change-and-fluids', 'Oil & filter', '{"en": "Oil & filter"}', 'SVC-OF',
   'Up to 5 L synthetic oil, OEM-spec filter, fluid top-up and a 12-point check at your driveway.', 'fixed', 7900, 45, 20, true, 'approved', 'live', 28, '2026-03-04T17:05:00Z', '2026-03-04T16:05:00Z', '2026-03-04T17:05:00Z'),
  ('01J9ZD3V0000000000000SVCDG', '01J9ZD3V00000000000000PWM1', 'service.automotive.mobile-mechanic', 'Diagnostic scan', '{"en": "Diagnostic scan"}', 'SVC-DG',
   'OBD-II scan, live data review and a plain-language diagnosis. Credited against the repair if you book it.', 'fixed', 12000, 60, 20, true, 'approved', 'live', 14, '2026-03-04T17:10:00Z', '2026-03-04T16:10:00Z', '2026-03-04T17:10:00Z'),
  ('01J9ZD3V0000000000000SVCPP', '01J9ZD3V00000000000000PWM1', 'service.automotive.pre-purchase-inspection', 'Pre-purchase inspection', '{"en": "Pre-purchase inspection"}', 'SVC-PP',
   '150-point inspection at the seller''s location, road test, scan and a photo report within 24 h.', 'fixed', 16000, 90, 30, true, 'approved', 'live', 9, '2026-03-04T17:15:00Z', '2026-03-04T16:15:00Z', '2026-03-04T17:15:00Z'),
  ('01J9ZD3V0000000000000SVCTS', '01J9ZD3V00000000000000PWM1', 'service.automotive.tire-change-and-storage', 'Winter tire swap', '{"en": "Winter tire swap"}', 'SVC-TS',
   'Swap four mounted wheels, torque to spec and set pressures. Storage not included.', 'fixed', 9900, 60, 15, false, 'approved', 'hidden', 0, '2026-03-04T17:20:00Z', '2026-03-04T16:20:00Z', '2026-03-04T17:20:00Z');

-- ── Shared catalogue record NL-P-88120 (Bosch Canada, brand-verified, locked) ─────────────────────────────────────
INSERT INTO catalogue.catalog_products
  (id, ref, gtin, identifier_type, brand, title, title_i18n, mpn, category_id, attributes, description, bullets, image_set,
   brand_owner_merchant_id, created_by_merchant_id, locked, created_at, updated_at) VALUES
  ('01J9ZD3V00000000000NLP8812', 'NL-P-88120', '028851200226', 'gtin', 'Bosch', 'Bosch Icon 22" Beam Wiper Blade · all-season',
   '{"en": "Bosch Icon 22\" Beam Wiper Blade · all-season", "fr": "Balai d''essuie-glace plat Bosch Icon 22 po · toutes saisons"}', '22A',
   'shop.hardware-and-auto.auto-parts', '{"partType": "Wiper blades", "length": "22 in", "position": "Front"}',
   'Beam blade with dual-rubber technology, all-season performance in Alberta winters.',
   '{"Fits most 2010+ sedans and SUVs — check the fitment tool","Rated to −40 °C"}',
   '{01J9ZD3V0000000000000MED01,01J9ZD3V0000000000000MED02,01J9ZD3V0000000000000MED03}',
   '01J9ZD3V00000000000BOSCHCA', '01J9ZD3V00000000000BOSCHCA', true, '2025-11-02T16:00:00Z', '2025-11-02T16:00:00Z'),
  -- seller-owned records (no GTIN match) for Prairie Wrench Parts
  ('01J9ZD3V0000000000000PBPCF', 'NL-P-88121', NULL, 'none', 'Prairie Wrench', 'Brake pads · ceramic (front)', '{"en": "Brake pads · ceramic (front)"}', 'PW-BP-F',
   'shop.hardware-and-auto.auto-parts', '{"partType": "Brakes", "length": "n/a", "position": "Front"}',
   'Low-dust ceramic pads for most compact and mid-size cars.', '{"Low dust, quiet stops","Hardware kit included"}', '{}',
   NULL, '01J9ZD3V00000000000000PWP1', false, '2026-03-05T16:00:00Z', '2026-03-05T16:00:00Z'),
  ('01J9ZD3V0000000000000POIL5', 'NL-P-88122', NULL, 'none', 'Prairie Wrench', 'Synthetic oil 5W-30 · 5 L', '{"en": "Synthetic oil 5W-30 · 5 L"}', 'PW-5W30-5',
   'shop.hardware-and-auto.auto-parts', '{"partType": "Fluids & chemicals", "length": "n/a", "position": "n/a"}',
   'Full synthetic 5W-30, dexos1 Gen 3 approved.', '{"dexos1 Gen 3 approved"}', '{}',
   NULL, '01J9ZD3V00000000000000PWP1', false, '2026-03-05T16:05:00Z', '2026-03-05T16:05:00Z'),
  ('01J9ZD3V0000000000000PCAF1', 'NL-P-88123', NULL, 'none', NULL, 'Cabin air filter', '{"en": "Cabin air filter"}', NULL,
   'shop.hardware-and-auto.auto-parts', '{"partType": "Filters"}', NULL, '{}', '{}',
   NULL, '01J9ZD3V00000000000000PWP1', false, '2026-09-28T17:00:00Z', '2026-09-28T17:00:00Z');
UPDATE catalogue.catalog_products SET owner_merchant_id = '01J9ZD3V00000000000000PWP1'
 WHERE id IN ('01J9ZD3V0000000000000PBPCF', '01J9ZD3V0000000000000POIL5', '01J9ZD3V0000000000000PCAF1');

-- Catalogue images of NL-P-88120. No bytes behind these keys in the local media store: the editor shows placeholders.
INSERT INTO catalogue.media (id, owner_type, owner_id, url, kind, merchant_id, content_type, width, height, on_white, exif_ok) VALUES
  ('01J9ZD3V0000000000000MED01', 'catalog_product', '01J9ZD3V00000000000NLP8812', 'seed/nl-p-88120-1.jpg', 'main', '01J9ZD3V00000000000BOSCHCA', 'image/jpeg', 1600, 1600, true, true),
  ('01J9ZD3V0000000000000MED02', 'catalog_product', '01J9ZD3V00000000000NLP8812', 'seed/nl-p-88120-2.jpg', 'gallery', '01J9ZD3V00000000000BOSCHCA', 'image/jpeg', 1600, 1200, false, true),
  ('01J9ZD3V0000000000000MED03', 'catalog_product', '01J9ZD3V00000000000NLP8812', 'seed/nl-p-88120-3.jpg', 'gallery', '01J9ZD3V00000000000BOSCHCA', 'image/jpeg', 1600, 1200, false, true);

-- ── Prairie Wrench Parts · products ────────────────────────────────────────────────────────────────────────────────
INSERT INTO catalogue.offers
  (id, product_id, merchant_id, title, sku, price_cents, compare_at_cents, cost_cents, stock, low_stock_at, condition, fulfilment,
   vetting, status, variant_theme, image_source, handling_time, returns_policy, country_of_origin, restricted_ok, bilingual_ok,
   search_keywords, sales_30d, submitted_at, created_at, updated_at) VALUES
  ('01J9ZD3V0000000000000OBPCF', '01J9ZD3V0000000000000PBPCF', '01J9ZD3V00000000000000PWP1', 'Brake pads · ceramic (front)', 'BP-CER-F', 6800, NULL, 3900, 14, 5, 'new', '{pooled,install,pickup}',
   'approved', 'live', 'none', 'own', 'same_day', 'standard_14', 'CA', true, true, 'brake pads, plaquettes de frein', 12, '2026-03-05T17:00:00Z', '2026-03-05T16:00:00Z', '2026-03-05T17:00:00Z'),
  ('01J9ZD3V0000000000000OOIL5', '01J9ZD3V0000000000000POIL5', '01J9ZD3V00000000000000PWP1', 'Synthetic oil 5W-30 · 5 L', 'OIL-5W30', 4200, 4899, 2650, 3, 5, 'new', '{pooled,install,pickup}',
   'approved', 'live', 'none', 'own', 'same_day', 'standard_14', 'CA', true, true, 'motor oil, huile moteur', 19, '2026-03-05T17:05:00Z', '2026-03-05T16:05:00Z', '2026-03-05T17:05:00Z'),
  ('01J9ZD3V0000000000000OWB22', '01J9ZD3V00000000000NLP8812', '01J9ZD3V00000000000000PWP1', 'Wiper blades · 22"', 'WB-22', 1900, 2499, 1120, 22, 5, 'new', '{pooled,install}',
   'pending', 'hidden', 'length', 'shared', 'same_day', 'standard_14', 'DE', true, true, 'windshield wiper, essuie-glace, blade replacement, winter wiper', 7,
   now() - interval '2 minutes', '2026-09-28T16:00:00Z', now() - interval '2 minutes'),
  ('01J9ZD3V0000000000000OCAF1', '01J9ZD3V0000000000000PCAF1', '01J9ZD3V00000000000000PWP1', 'Cabin air filter', 'CAF-01', 2400, NULL, NULL, 0, 5, 'new', '{pooled}',
   'draft', 'hidden', 'none', 'own', NULL, NULL, NULL, false, false, NULL, 4, NULL, '2026-09-28T17:00:00Z', '2026-09-28T17:00:00Z');

INSERT INTO catalogue.variants (id, offer_id, sku, gtin, attrs, price_cents, stock, image_set, position) VALUES
  ('01J9ZD3V0000000000000VWB20', '01J9ZD3V0000000000000OWB22', 'WB-20', '028851200220', '{"value": "20 in"}', 1700, 22, '{}', 0),
  ('01J9ZD3V0000000000000VWB22', '01J9ZD3V0000000000000OWB22', 'WB-22', '028851200226', '{"value": "22 in"}', 1900, 18, '{}', 1),
  ('01J9ZD3V0000000000000VWB24', '01J9ZD3V0000000000000OWB22', 'WB-24', '028851200222', '{"value": "24 in"}', 2100, 14, '{}', 2);

-- The 13 other sellers of NL-P-88120 ("14 sellers from $17.50"; category median ≈ $21). Merchant ids are synthetic.
INSERT INTO catalogue.offers (id, product_id, merchant_id, title, sku, price_cents, stock, condition, fulfilment, vetting, status, created_at, updated_at)
SELECT '01J9ZD3V00000000000OFS' || lpad(n::text, 4, '0'), '01J9ZD3V00000000000NLP8812', '01J9ZD3V0000000000SELL' || lpad(n::text, 4, '0'),
       'Bosch Icon 22" beam blade', 'BOSCH-22A', 1700 + n * 50, 10, 'new', '{pooled}', 'approved', 'live', '2026-01-01T16:00:00Z', '2026-01-01T16:00:00Z'
  FROM generate_series(1, 13) AS n;

-- ── Import history (Prairie Wrench Parts) ──────────────────────────────────────────────────────────────────────────
INSERT INTO catalogue.imports (id, merchant_id, file_name, template, row_count, create_count, update_count, error_count, status, created_by, created_at, imported_at) VALUES
  ('01J9ZD3V0000000000000IMP01', '01J9ZD3V00000000000000PWP1', 'parts-aug.xlsx', 'auto_parts', 180, 180, 0, 0, 'imported', '01J9ZD3V00000000000000RAV1', '2026-08-14T17:00:00Z', '2026-08-14T17:02:00Z'),
  ('01J9ZD3V0000000000000IMP02', '01J9ZD3V00000000000000PWP1', 'price-update.csv', 'price_stock', 64, 0, 64, 0, 'imported', '01J9ZD3V00000000000000RAV1', '2026-08-02T17:00:00Z', '2026-08-02T17:01:00Z');

-- Prairie Wrench's AMVIC licence (design: "AMVIC licence · matched registry record 44812"), so newly submitted
-- automotive services pass the licence check in dev. The onboarding seed may record the same fact under its own id.
INSERT INTO merchants.verifications (id, merchant_id, check_type, registry, reference, status, expires_at) VALUES
  ('01J9ZD3V0000000000000VAMV1', '01J9ZD3V00000000000000PWM1', 'licence', 'AMVIC', '44812', 'verified', '2027-01-31T07:00:00Z')
ON CONFLICT (id) DO NOTHING;
