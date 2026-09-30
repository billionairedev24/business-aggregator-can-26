-- S-49 (consumer web, design 06 Shop): public browse of the shop catalogue and the pooled runs it advertises.
-- Consumer range V111–V113. Additive only; see docs/DECISIONS.md "S-49".

-- ── Pooled runs per market ───────────────────────────────────────────────────────────────────────────────────────
-- region.zones has no rows yet, so a run belongs to a market (a city: Calgary, Edmonton, Airdrie). `slot` names the
-- configured daily run it comes from (evening | morning, northline.orders.delivery.runs). Windows created before
-- (the dev seed's) have neither and serve every market.
ALTER TABLE orders.delivery_windows
  ADD COLUMN market text,
  ADD COLUMN slot text;
CREATE UNIQUE INDEX ux_delivery_windows_market_start ON orders.delivery_windows (market, starts_at) WHERE market IS NOT NULL;
CREATE INDEX ix_orders_window ON orders.orders (window_id) WHERE window_id IS NOT NULL;
-- Run codes for scheduled windows ("R-700"…); the dev seed uses R-609…R-612.
CREATE SEQUENCE orders.run_label_seq START 700;

-- ── Browse ───────────────────────────────────────────────────────────────────────────────────────────────────────
-- What the Shop pages read: approved, live offers per merchant and product.
CREATE INDEX ix_offers_live ON catalogue.offers (merchant_id, product_id) WHERE vetting = 'approved' AND status = 'live';
CREATE INDEX ix_offers_live_product ON catalogue.offers (product_id) WHERE vetting = 'approved' AND status = 'live';

-- ── Category names in French ─────────────────────────────────────────────────────────────────────────────────────
-- db/seed/categories.json is English only and `seedCategories` rewrites name_i18n on every run, so translations live
-- beside it. No FK: the categories may be seeded after this migration (same reason as attribute_templates, V050).
CREATE TABLE catalogue.category_labels (
  category_id text NOT NULL,
  lang text NOT NULL CHECK (lang IN ('en', 'fr')),
  name text NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
  PRIMARY KEY (category_id, lang)
);

INSERT INTO catalogue.category_labels (category_id, lang, name) VALUES
  ('shop.food-and-grocery', 'fr', 'Alimentation et épicerie'),
  ('shop.food-and-grocery.groceries', 'fr', 'Épicerie'),
  ('shop.food-and-grocery.butcher', 'fr', 'Boucherie'),
  ('shop.food-and-grocery.bakery', 'fr', 'Boulangerie'),
  ('shop.food-and-grocery.produce', 'fr', 'Fruits et légumes'),
  ('shop.food-and-grocery.dairy-and-eggs', 'fr', 'Produits laitiers et œufs'),
  ('shop.food-and-grocery.specialty-and-international', 'fr', 'Produits fins et internationaux'),
  ('shop.food-and-grocery.coffee-and-tea', 'fr', 'Café et thé'),
  ('shop.food-and-grocery.frozen', 'fr', 'Surgelés'),
  ('shop.health-and-beauty', 'fr', 'Santé et beauté'),
  ('shop.health-and-beauty.pharmacy-otc', 'fr', 'Pharmacie (sans ordonnance)'),
  ('shop.health-and-beauty.vitamins-and-supplements', 'fr', 'Vitamines et suppléments'),
  ('shop.health-and-beauty.cosmetics-and-skincare', 'fr', 'Cosmétiques et soins de la peau'),
  ('shop.health-and-beauty.baby-care', 'fr', 'Soins pour bébé'),
  ('shop.home', 'fr', 'Maison'),
  ('shop.home.home-and-kitchen', 'fr', 'Maison et cuisine'),
  ('shop.home.furniture', 'fr', 'Meubles'),
  ('shop.home.bedding-and-bath', 'fr', 'Literie et bain'),
  ('shop.home.decor', 'fr', 'Décoration'),
  ('shop.home.cleaning-supplies', 'fr', 'Produits d''entretien'),
  ('shop.home.garden', 'fr', 'Jardin'),
  ('shop.hardware-and-auto', 'fr', 'Quincaillerie et auto'),
  ('shop.hardware-and-auto.hardware', 'fr', 'Quincaillerie'),
  ('shop.hardware-and-auto.tools', 'fr', 'Outils'),
  ('shop.hardware-and-auto.paint', 'fr', 'Peinture'),
  ('shop.hardware-and-auto.plumbing-and-electrical-supply', 'fr', 'Plomberie et électricité'),
  ('shop.hardware-and-auto.auto-parts', 'fr', 'Pièces auto'),
  ('shop.hardware-and-auto.tires', 'fr', 'Pneus'),
  ('shop.apparel', 'fr', 'Mode'),
  ('shop.apparel.clothing', 'fr', 'Vêtements'),
  ('shop.apparel.footwear', 'fr', 'Chaussures'),
  ('shop.apparel.accessories', 'fr', 'Accessoires'),
  ('shop.apparel.kids-clothing', 'fr', 'Vêtements pour enfants'),
  ('shop.apparel.workwear', 'fr', 'Vêtements de travail'),
  ('shop.electronics', 'fr', 'Électronique'),
  ('shop.electronics.electronics', 'fr', 'Électronique'),
  ('shop.electronics.phones-and-accessories', 'fr', 'Téléphones et accessoires'),
  ('shop.electronics.computers', 'fr', 'Ordinateurs'),
  ('shop.electronics.small-appliances', 'fr', 'Petits électroménagers'),
  ('shop.kids-gifts-and-hobbies', 'fr', 'Enfants, cadeaux et loisirs'),
  ('shop.kids-gifts-and-hobbies.toys', 'fr', 'Jouets'),
  ('shop.kids-gifts-and-hobbies.kids', 'fr', 'Enfants'),
  ('shop.kids-gifts-and-hobbies.gifts-and-crafts', 'fr', 'Cadeaux'),
  ('shop.kids-gifts-and-hobbies.books-and-stationery', 'fr', 'Livres et papeterie'),
  ('shop.kids-gifts-and-hobbies.sporting-goods', 'fr', 'Articles de sport'),
  ('shop.kids-gifts-and-hobbies.pet-supplies', 'fr', 'Animaux'),
  ('shop.restricted', 'fr', 'Produits réglementés'),
  ('shop.restricted.alcohol', 'fr', 'Alcool'),
  ('shop.restricted.tobacco-and-vape', 'fr', 'Tabac et vapotage'),
  ('shop.restricted.cannabis-accessories', 'fr', 'Accessoires de cannabis');
