-- Dev-only seed (profile `local`), search workstream: where the V100 personas' businesses are, so search results have a
-- distance and the geo filters work locally (merchants.locations, V120). Approximate points of the addresses in V102.
INSERT INTO merchants.locations (merchant_id, geom, service_radius_km, source) VALUES
  -- Prairie Wrench: 1208 17 Ave SW, Calgary — "Calgary + 40 km · Airdrie, Cochrane"
  ('01J9ZD3V00000000000000PWM1', ST_GeogFromText('POINT(-114.0880 51.0379)'), 40, 'seed'),
  -- Prairie Wrench Parts: 4410 Manhattan Rd SE (pooled delivery across Calgary)
  ('01J9ZD3V00000000000000PWP1', ST_GeogFromText('POINT(-114.0460 50.9960)'), 30, 'seed'),
  -- Pho Dau Bo: 3715 17 Ave SE (delivery radius from food.kitchen_settings)
  ('01J9ZD3V00000000000000PDB1', ST_GeogFromText('POINT(-113.9870 51.0376)'), NULL, 'seed')
ON CONFLICT (merchant_id) DO NOTHING;
