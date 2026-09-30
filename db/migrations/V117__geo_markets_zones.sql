-- 2026-09-30 (S-47, consumer web): markets and delivery zones for the Location screen (design 06 `location`), and the
-- waitlist for addresses outside a live market. Additive only. See docs/DECISIONS.md "S-47".
--
-- region.regions holds both provinces (kind = province) and the city markets inside them (kind = market, parent_id =
-- the province). A market covers the addresses within radius_km of its centre; the nearest covering centre wins (Airdrie
-- sits 30 km from central Calgary). region.zones are the delivery zones (PostGIS polygons) inside a market.

ALTER TABLE region.regions
  ADD COLUMN kind text NOT NULL DEFAULT 'province' CHECK (kind IN ('province', 'market')),
  ADD COLUMN parent_id text REFERENCES region.regions(id),
  ADD COLUMN city text,
  ADD COLUMN center geography(Point, 4326),
  ADD COLUMN radius_km numeric(5, 1),
  ADD COLUMN sort integer NOT NULL DEFAULT 0,
  ADD CONSTRAINT chk_regions_market CHECK (kind = 'province'
      OR (parent_id IS NOT NULL AND city IS NOT NULL AND center IS NOT NULL AND radius_km > 0)),
  ADD CONSTRAINT chk_regions_province CHECK (province IS NOT NULL AND char_length(province) = 2);
CREATE UNIQUE INDEX ux_regions_province ON region.regions(province) WHERE kind = 'province';
CREATE INDEX ix_regions_center ON region.regions USING gist(center) WHERE kind = 'market';

ALTER TABLE region.zones
  ADD COLUMN sort integer NOT NULL DEFAULT 0,
  ADD CONSTRAINT chk_zones_name CHECK (name IS NOT NULL AND char_length(btrim(name)) BETWEEN 1 AND 60),
  ADD CONSTRAINT chk_zones_runs CHECK (runs_per_day IS NULL OR runs_per_day BETWEEN 0 AND 24);
CREATE INDEX ix_zones_polygon ON region.zones USING gist(polygon);
CREATE INDEX ix_zones_region ON region.zones(region_id);

-- Someone who wants Northline where it isn't live yet. A signed-in person by user id, a guest by email (their own).
CREATE TABLE region.waitlist (
  id text PRIMARY KEY,
  region_id text NOT NULL REFERENCES region.regions(id),
  user_id text,
  email text CHECK (email IS NULL OR char_length(email) <= 254),
  locale text NOT NULL DEFAULT 'en' CHECK (locale IN ('en', 'fr')),
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (user_id IS NOT NULL OR email IS NOT NULL)
);
CREATE UNIQUE INDEX ux_waitlist_user ON region.waitlist(region_id, user_id) WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX ux_waitlist_email ON region.waitlist(region_id, lower(email)) WHERE user_id IS NULL;
-- logical ref (cross-module, no FK): region.waitlist.user_id → identity.users.id

-- Reference data: where Northline runs (design 06 Location: "In Alberta: Calgary, Edmonton and Airdrie are live; Red
-- Deer is in pilot; Lethbridge and Medicine Hat have a waitlist"; British Columbia pilot, Ontario and Québec waitlist).
-- Staff change stages from the console later (region.stage_changed); these rows only start the list.
INSERT INTO region.regions (id, kind, province, name_i18n, stage, languages, courier_model, sort) VALUES
  ('prov-ab', 'province', 'AB', '{"en":"Alberta","fr":"Alberta"}',                          'live',     '{en,fr}', 'hybrid', 1),
  ('prov-bc', 'province', 'BC', '{"en":"British Columbia","fr":"Colombie-Britannique"}',    'pilot',    '{en,fr}', 'contracted', 2),
  ('prov-on', 'province', 'ON', '{"en":"Ontario","fr":"Ontario"}',                          'waitlist', '{en,fr}', NULL, 3),
  ('prov-qc', 'province', 'QC', '{"en":"Québec","fr":"Québec"}',                            'waitlist', '{fr,en}', NULL, 4)
ON CONFLICT (id) DO NOTHING;

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
