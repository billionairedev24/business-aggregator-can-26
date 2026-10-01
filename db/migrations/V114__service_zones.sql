-- S-53 (consumer web · Services): where the service-area zones are, so the provider list can keep the businesses whose
-- service area covers the customer's location. Providers pick zones by name in Availability › Booking rules
-- (availability.service_areas, V041; the names are BookingRules.ZONES) because region.zones has no Calgary rows yet.
-- Each name gets a city and an approximate area: a circle around the neighbourhood / town centre. Reference data, the
-- same in every environment. Additive only.
CREATE TABLE availability.service_zones (
  name text PRIMARY KEY,
  city text NOT NULL,
  centre geography(Point) NOT NULL,
  area geography(Polygon) NOT NULL,
  radius_m integer NOT NULL CHECK (radius_m > 0)
);
CREATE INDEX ix_service_zones_area ON availability.service_zones USING gist (area);
CREATE INDEX ix_service_areas_zone ON availability.service_areas (zone);

INSERT INTO availability.service_zones (name, city, centre, area, radius_m)
SELECT z.name, z.city,
       ST_SetSRID(ST_MakePoint(z.lng, z.lat), 4326)::geography,
       ST_Buffer(ST_SetSRID(ST_MakePoint(z.lng, z.lat), 4326)::geography, z.radius_m, 'quad_segs=16')::geography(Polygon),
       z.radius_m
  FROM (VALUES
    ('Beltline',   'Calgary', 51.0385, -114.0720,  1200),
    ('Kensington', 'Calgary', 51.0526, -114.0906,  1500),
    ('Inglewood',  'Calgary', 51.0381, -114.0247,  1800),
    ('Downtown',   'Calgary', 51.0490, -114.0680,  1100),
    ('NW Calgary', 'Calgary', 51.1100, -114.1500,  9000),
    ('SE Calgary', 'Calgary', 50.9600, -113.9800, 10000),
    ('Airdrie',    'Airdrie', 51.2917, -114.0144,  6000),
    ('Cochrane',   'Cochrane', 51.1894, -114.4672, 5000),
    ('Okotoks',    'Okotoks', 50.7256, -113.9749,  5000)
  ) AS z(name, city, lat, lng, radius_m);
