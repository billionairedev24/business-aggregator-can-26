-- Dev-only seed (profile `local`, S-77): pictures for the seeded listings. V104's shared record NL-P-88120 already
-- names its three images (seed/nl-p-88120-*.jpg); these rows add one main image to each seller-owned listing of V104
-- (Prairie Wrench Parts' brake pads and oil) and V113 (the neighbourhood shops). The bytes are bundled sample pictures
-- (server/api/src/main/resources/seed-media/catalogue), served by the local media store for `seed/` keys until
-- something is uploaded under them. The cabin air filter (V104) stays a draft without photos on purpose: it shows the
-- editor's missing-photo state.
INSERT INTO catalogue.media (id, url, kind, merchant_id, content_type, width, height, byte_size, on_white, exif_ok) VALUES
  ('01J9ZD3V000000000000PWMD01', 'seed/brake-pads-ceramic.jpg', 'main', '01J9ZD3V00000000000000PWP1', 'image/jpeg', 1200, 1200, 35127, false, true),
  ('01J9ZD3V000000000000PWMD02', 'seed/synthetic-oil-5w30.jpg', 'main', '01J9ZD3V00000000000000PWP1', 'image/jpeg', 1200, 1200, 40070, false, true),
  ('01J9ZD3V000000000000SMED01', 'seed/country-sourdough.jpg', 'main', '01J9ZD3V000000000000SHPM01', 'image/jpeg', 1200, 1200, 35722, false, true),
  ('01J9ZD3V000000000000SMED02', 'seed/seeded-sourdough.jpg', 'main', '01J9ZD3V000000000000SHPM01', 'image/jpeg', 1200, 1200, 36015, false, true),
  ('01J9ZD3V000000000000SMED03', 'seed/rye.jpg', 'main', '01J9ZD3V000000000000SHPM01', 'image/jpeg', 1200, 1200, 26703, false, true),
  ('01J9ZD3V000000000000SMED04', 'seed/baguette.jpg', 'main', '01J9ZD3V000000000000SHPM01', 'image/jpeg', 1200, 1200, 29935, false, true),
  ('01J9ZD3V000000000000SMED05', 'seed/cinnamon-buns.jpg', 'main', '01J9ZD3V000000000000SHPM01', 'image/jpeg', 1200, 1200, 33839, false, true),
  ('01J9ZD3V000000000000SMED06', 'seed/croissants.jpg', 'main', '01J9ZD3V000000000000SHPM07', 'image/jpeg', 1200, 1200, 32176, false, true),
  ('01J9ZD3V000000000000SMED07', 'seed/ribeye.jpg', 'main', '01J9ZD3V000000000000SHPM02', 'image/jpeg', 1200, 1200, 32234, false, true),
  ('01J9ZD3V000000000000SMED08', 'seed/chicken-thighs.jpg', 'main', '01J9ZD3V000000000000SHPM02', 'image/jpeg', 1200, 1200, 34159, false, true),
  ('01J9ZD3V000000000000SMED09', 'seed/kale-chard.jpg', 'main', '01J9ZD3V000000000000SHPM03', 'image/jpeg', 1200, 1200, 33317, false, true),
  ('01J9ZD3V000000000000SMED10', 'seed/carrots.jpg', 'main', '01J9ZD3V000000000000SHPM03', 'image/jpeg', 1200, 1200, 29304, false, true),
  ('01J9ZD3V000000000000SMED11', 'seed/apples-ambrosia.jpg', 'main', '01J9ZD3V000000000000SHPM03', 'image/jpeg', 1200, 1200, 35858, false, true),
  ('01J9ZD3V000000000000SMED12', 'seed/saskatoon-jam.jpg', 'main', '01J9ZD3V000000000000SHPM04', 'image/jpeg', 1200, 1200, 32910, false, true),
  ('01J9ZD3V000000000000SMED13', 'seed/lentils.jpg', 'main', '01J9ZD3V000000000000SHPM04', 'image/jpeg', 1200, 1200, 28703, false, true),
  ('01J9ZD3V000000000000SMED14', 'seed/starter-kit.jpg', 'main', '01J9ZD3V000000000000SHPM04', 'image/jpeg', 1200, 1200, 34509, false, true),
  ('01J9ZD3V000000000000SMED15', 'seed/free-run-eggs.jpg', 'main', '01J9ZD3V000000000000SHPM08', 'image/jpeg', 1200, 1200, 29000, false, true),
  ('01J9ZD3V000000000000SMED16', 'seed/cheddar-aged.jpg', 'main', '01J9ZD3V000000000000SHPM08', 'image/jpeg', 1200, 1200, 32576, false, true),
  ('01J9ZD3V000000000000SMED17', 'seed/cold-flu-relief.jpg', 'main', '01J9ZD3V000000000000SHPM05', 'image/jpeg', 1200, 1200, 33403, false, true),
  ('01J9ZD3V000000000000SMED18', 'seed/stacking-rings.jpg', 'main', '01J9ZD3V000000000000SHPM06', 'image/jpeg', 1200, 1200, 36640, false, true),
  ('01J9ZD3V000000000000SMED19', 'seed/free-run-eggs.jpg', 'main', '01J9ZD3V000000000000SHPM04', 'image/jpeg', 1200, 1200, 29000, false, true);

UPDATE catalogue.offers o
   SET own_images = array[v.media_id], image_source = 'own'
  FROM (VALUES
  ('01J9ZD3V0000000000000OBPCF', '01J9ZD3V000000000000PWMD01'),
  ('01J9ZD3V0000000000000OOIL5', '01J9ZD3V000000000000PWMD02'),
  ('01J9ZD3V000000000000SFRS01', '01J9ZD3V000000000000SMED01'),
  ('01J9ZD3V000000000000SFRS02', '01J9ZD3V000000000000SMED02'),
  ('01J9ZD3V000000000000SFRS03', '01J9ZD3V000000000000SMED03'),
  ('01J9ZD3V000000000000SFRS04', '01J9ZD3V000000000000SMED04'),
  ('01J9ZD3V000000000000SFRS05', '01J9ZD3V000000000000SMED05'),
  ('01J9ZD3V000000000000SFRS06', '01J9ZD3V000000000000SMED06'),
  ('01J9ZD3V000000000000SFRS07', '01J9ZD3V000000000000SMED07'),
  ('01J9ZD3V000000000000SFRS08', '01J9ZD3V000000000000SMED08'),
  ('01J9ZD3V000000000000SFRS09', '01J9ZD3V000000000000SMED09'),
  ('01J9ZD3V000000000000SFRS10', '01J9ZD3V000000000000SMED10'),
  ('01J9ZD3V000000000000SFRS11', '01J9ZD3V000000000000SMED11'),
  ('01J9ZD3V000000000000SFRS12', '01J9ZD3V000000000000SMED12'),
  ('01J9ZD3V000000000000SFRS13', '01J9ZD3V000000000000SMED13'),
  ('01J9ZD3V000000000000SFRS14', '01J9ZD3V000000000000SMED14'),
  ('01J9ZD3V000000000000SFRS15', '01J9ZD3V000000000000SMED15'),
  ('01J9ZD3V000000000000SFRS16', '01J9ZD3V000000000000SMED16'),
  ('01J9ZD3V000000000000SFRS17', '01J9ZD3V000000000000SMED17'),
  ('01J9ZD3V000000000000SFRS18', '01J9ZD3V000000000000SMED18'),
  ('01J9ZD3V000000000000SFRS19', '01J9ZD3V000000000000SMED19')
       ) AS v(offer_id, media_id)
 WHERE o.id = v.offer_id;
