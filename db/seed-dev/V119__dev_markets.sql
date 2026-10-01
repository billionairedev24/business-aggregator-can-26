-- Dev-only seed (profile `local`, S-47): markets and delivery zones for the Location screen, so local runs show design
-- 06's Location screen (its live / pilot / waitlist examples). Production markets are region data operations add; the
-- provinces served come from SEARCH_MARKETS. Coordinates and boxes are approximate.
UPDATE region.regions SET stage = 'pilot', courier_model = 'contracted' WHERE id = 'prov-bc';
UPDATE region.regions SET stage = 'waitlist' WHERE id IN ('prov-on', 'prov-qc');
UPDATE region.regions SET courier_model = 'hybrid' WHERE id = 'prov-ab';

INSERT INTO region.regions (id, kind, parent_id, province, city, name_i18n, stage, center, radius_km, languages, courier_model, sort) VALUES
  ('mkt-calgary',      'market', 'prov-ab', 'AB', 'Calgary',      '{"en":"Calgary","fr":"Calgary"}',           'live',     'SRID=4326;POINT(-114.0719 51.0447)', 25, '{en,fr}', 'hybrid', 1),
  ('mkt-edmonton',     'market', 'prov-ab', 'AB', 'Edmonton',     '{"en":"Edmonton","fr":"Edmonton"}',         'live',     'SRID=4326;POINT(-113.4938 53.5461)', 25, '{en,fr}', 'hybrid', 2),
  ('mkt-airdrie',      'market', 'prov-ab', 'AB', 'Airdrie',      '{"en":"Airdrie","fr":"Airdrie"}',           'live',     'SRID=4326;POINT(-114.0144 51.2917)', 10, '{en,fr}', 'contracted', 3),
  ('mkt-red-deer',     'market', 'prov-ab', 'AB', 'Red Deer',     '{"en":"Red Deer","fr":"Red Deer"}',         'pilot',    'SRID=4326;POINT(-113.8116 52.2690)', 15, '{en,fr}', 'contracted', 4),
  ('mkt-lethbridge',   'market', 'prov-ab', 'AB', 'Lethbridge',   '{"en":"Lethbridge","fr":"Lethbridge"}',     'waitlist', 'SRID=4326;POINT(-112.8418 49.6956)', 15, '{en,fr}', NULL, 5),
  ('mkt-medicine-hat', 'market', 'prov-ab', 'AB', 'Medicine Hat', '{"en":"Medicine Hat","fr":"Medicine Hat"}', 'waitlist', 'SRID=4326;POINT(-110.6773 50.0405)', 12, '{en,fr}', NULL, 6),
  ('mkt-vancouver',    'market', 'prov-bc', 'BC', 'Vancouver',    '{"en":"Vancouver","fr":"Vancouver"}',       'pilot',    'SRID=4326;POINT(-123.1207 49.2827)', 20, '{en,fr}', 'contracted', 1)
ON CONFLICT (id) DO NOTHING;

-- Delivery zones (approximate neighbourhood boxes; Operations redraws them in the console). Pricing = design 06:
-- "3 pooled runs / day", pooled delivery $4.99 (free with Plus), $35 minimum basket.
INSERT INTO region.zones (id, region_id, name, polygon, runs_per_day, fee_std_cents, fee_plus_cents, min_basket_cents, sort) VALUES
  ('zone-yyc-downtown',     'mkt-calgary',  'Downtown',     'SRID=4326;POLYGON((-114.0850 51.0440,-114.0560 51.0440,-114.0560 51.0530,-114.0850 51.0530,-114.0850 51.0440))', 3, 499, 0, 3500, 1),
  ('zone-yyc-beltline',     'mkt-calgary',  'Beltline',     'SRID=4326;POLYGON((-114.0950 51.0330,-114.0560 51.0330,-114.0560 51.0440,-114.0950 51.0440,-114.0950 51.0330))', 3, 499, 0, 3500, 2),
  ('zone-yyc-sunalta',      'mkt-calgary',  'Sunalta',      'SRID=4326;POLYGON((-114.1150 51.0350,-114.0950 51.0350,-114.0950 51.0450,-114.1150 51.0450,-114.1150 51.0350))', 3, 499, 0, 3500, 3),
  ('zone-yyc-mission',      'mkt-calgary',  'Mission',      'SRID=4326;POLYGON((-114.0800 51.0230,-114.0560 51.0230,-114.0560 51.0330,-114.0800 51.0330,-114.0800 51.0230))', 3, 499, 0, 3500, 4),
  ('zone-yyc-inglewood',    'mkt-calgary',  'Inglewood',    'SRID=4326;POLYGON((-114.0560 51.0280,-114.0100 51.0280,-114.0100 51.0450,-114.0560 51.0450,-114.0560 51.0280))', 3, 499, 0, 3500, 5),
  ('zone-yyc-kensington',   'mkt-calgary',  'Kensington',   'SRID=4326;POLYGON((-114.1100 51.0530,-114.0700 51.0530,-114.0700 51.0650,-114.1100 51.0650,-114.1100 51.0530))', 3, 499, 0, 3500, 6),
  ('zone-yyc-capitol-hill', 'mkt-calgary',  'Capitol Hill', 'SRID=4326;POLYGON((-114.1100 51.0650,-114.0800 51.0650,-114.0800 51.0780,-114.1100 51.0780,-114.1100 51.0650))', 3, 499, 0, 3500, 7),
  ('zone-yyc-forest-lawn',  'mkt-calgary',  'Forest Lawn',  'SRID=4326;POLYGON((-113.9950 51.0300,-113.9450 51.0300,-113.9450 51.0500,-113.9950 51.0500,-113.9950 51.0300))', 3, 499, 0, 3500, 8),
  ('zone-yeg-downtown',     'mkt-edmonton', 'Downtown',     'SRID=4326;POLYGON((-113.5200 53.5380,-113.4800 53.5380,-113.4800 53.5500,-113.5200 53.5500,-113.5200 53.5380))', 3, 499, 0, 3500, 1),
  ('zone-yeg-old-strathcona','mkt-edmonton','Old Strathcona','SRID=4326;POLYGON((-113.5150 53.5140,-113.4850 53.5140,-113.4850 53.5250,-113.5150 53.5250,-113.5150 53.5140))', 3, 499, 0, 3500, 2),
  ('zone-yyc-airdrie',      'mkt-airdrie',  'Airdrie',      'SRID=4326;POLYGON((-114.0700 51.2600,-113.9600 51.2600,-113.9600 51.3300,-114.0700 51.3300,-114.0700 51.2600))', 2, 499, 0, 3500, 1)
ON CONFLICT (id) DO NOTHING;
