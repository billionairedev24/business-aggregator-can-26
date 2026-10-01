-- 2026-09-30 (S-47, consumer web): markets and delivery zones for the Location screen (design 06 `location`), and the
-- waitlist for addresses outside a live market. Additive only. See docs/DECISIONS.md "S-47".
--
-- region.regions holds both provinces (kind = province) and the city markets inside them (kind = market, parent_id =
-- the province). A market covers the addresses within radius_km of its centre; the nearest covering centre wins (a
-- satellite town next to a large city). region.zones are the delivery zones (PostGIS polygons) inside a market.
--
-- Region-neutral (DECISIONS "Region-neutral by design"): this migration names no city. It only lists Canada's 13
-- provinces and territories, all `off`; which provinces are served comes from configuration (SEARCH_MARKETS, read by
-- region.api.Markets), and markets / zones are region data that operations add (the local dev seed V119 has some).

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

-- Canada's provinces and territories (names en/fr). `off` until operations open a waitlist or a pilot; a province
-- listed in SEARCH_MARKETS counts as live whatever its row says.
INSERT INTO region.regions (id, kind, province, name_i18n, stage, languages, sort) VALUES
  ('prov-ab', 'province', 'AB', '{"en":"Alberta","fr":"Alberta"}',                                     'off', '{en,fr}', 1),
  ('prov-bc', 'province', 'BC', '{"en":"British Columbia","fr":"Colombie-Britannique"}',               'off', '{en,fr}', 2),
  ('prov-mb', 'province', 'MB', '{"en":"Manitoba","fr":"Manitoba"}',                                   'off', '{en,fr}', 3),
  ('prov-nb', 'province', 'NB', '{"en":"New Brunswick","fr":"Nouveau-Brunswick"}',                     'off', '{en,fr}', 4),
  ('prov-nl', 'province', 'NL', '{"en":"Newfoundland and Labrador","fr":"Terre-Neuve-et-Labrador"}',   'off', '{en,fr}', 5),
  ('prov-ns', 'province', 'NS', '{"en":"Nova Scotia","fr":"Nouvelle-Écosse"}',                         'off', '{en,fr}', 6),
  ('prov-nt', 'province', 'NT', '{"en":"Northwest Territories","fr":"Territoires du Nord-Ouest"}',     'off', '{en,fr}', 7),
  ('prov-nu', 'province', 'NU', '{"en":"Nunavut","fr":"Nunavut"}',                                     'off', '{en,fr}', 8),
  ('prov-on', 'province', 'ON', '{"en":"Ontario","fr":"Ontario"}',                                     'off', '{en,fr}', 9),
  ('prov-pe', 'province', 'PE', '{"en":"Prince Edward Island","fr":"Île-du-Prince-Édouard"}',          'off', '{en,fr}', 10),
  ('prov-qc', 'province', 'QC', '{"en":"Quebec","fr":"Québec"}',                                       'off', '{fr,en}', 11),
  ('prov-sk', 'province', 'SK', '{"en":"Saskatchewan","fr":"Saskatchewan"}',                           'off', '{en,fr}', 12),
  ('prov-yt', 'province', 'YT', '{"en":"Yukon","fr":"Yukon"}',                                         'off', '{en,fr}', 13)
ON CONFLICT (id) DO NOTHING;
