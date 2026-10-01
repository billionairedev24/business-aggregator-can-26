-- 2026-10-01 (S-134): Alberta is the first *configured* live region. This is region data — the same rows the dev seed
-- V119 has, inserted only where missing — so every environment knows its launch markets without a city in code:
-- delivery markets, the shop's fallback market, the providers' service zones and the municipal licence registry all
-- come from here. Opening another province is more rows like these (or the console, phase 3), never a code change.
-- Additive only.

UPDATE region.regions SET stage = 'live' WHERE kind = 'province' AND province = 'AB';

INSERT INTO region.regions (id, kind, parent_id, province, city, name_i18n, stage, center, radius_km, languages, sort) VALUES
  ('mkt-calgary',  'market', 'prov-ab', 'AB', 'Calgary',  '{"en":"Calgary","fr":"Calgary"}',   'live', 'SRID=4326;POINT(-114.0719 51.0447)', 25, '{en,fr}', 1),
  ('mkt-edmonton', 'market', 'prov-ab', 'AB', 'Edmonton', '{"en":"Edmonton","fr":"Edmonton"}', 'live', 'SRID=4326;POINT(-113.4938 53.5461)', 25, '{en,fr}', 2),
  ('mkt-airdrie',  'market', 'prov-ab', 'AB', 'Airdrie',  '{"en":"Airdrie","fr":"Airdrie"}',   'live', 'SRID=4326;POINT(-114.0144 51.2917)', 10, '{en,fr}', 3)
ON CONFLICT (id) DO NOTHING;

-- The City of Calgary's business-licence dataset answers for businesses in that market only.
UPDATE region.regions SET registries = '{calgary_business_licences}' WHERE id = 'mkt-calgary';

-- Service zones (S-53, V114) belong to a market: the zones a provider can pick are its market's. default_on = the
-- zones a provider starts with before saving its own booking rules (the design's defaults).
ALTER TABLE availability.service_zones
  ADD COLUMN market_id text,
  ADD COLUMN sort integer NOT NULL DEFAULT 0,
  ADD COLUMN default_on boolean NOT NULL DEFAULT false;
-- logical ref (cross-module, no FK): availability.service_zones.market_id → region.regions.id (kind = market)
CREATE INDEX ix_service_zones_market ON availability.service_zones (market_id, sort);

UPDATE availability.service_zones z
   SET market_id = 'mkt-calgary', sort = v.sort, default_on = v.default_on
  FROM (VALUES
    ('Beltline', 1, true), ('Kensington', 2, true), ('Inglewood', 3, true), ('Downtown', 4, true),
    ('NW Calgary', 5, false), ('SE Calgary', 6, false), ('Airdrie', 7, true), ('Cochrane', 8, false),
    ('Okotoks', 9, false)
  ) AS v(name, sort, default_on)
 WHERE z.name = v.name;
