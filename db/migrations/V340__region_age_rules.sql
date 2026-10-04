-- Age-restricted purchases (owner decision 2026-10-04, range V340–V349 above main's V334). Additive only.
-- See docs/DECISIONS.md "2026-10-04 — Age-restricted purchases" and docs/runbooks/age-restricted.md.
--
-- The age a customer must be, per province and per restriction class, is region data: code never names a province or an
-- age. A class is what a taxonomy category carries (catalogue.categories.age_class, V341): alcohol, tobacco (tobacco and
-- vaping products) and cannabis (cannabis accessories). A province with no row for a class can't receive that class at
-- all (checkout refuses it). Hours are local times in the delivery address's market zone; null = no window configured.
-- A window whose end is before its start runs past midnight (10:00 → 02:00).
--
-- SOURCES (checked 2026-10-04 from public summaries; the official statutes could not be fetched from this machine, so
-- every value below is FOR COUNSEL TO CONFIRM — docs/compliance/legal/counsel-questions.md H4):
--   alcohol  18 in AB, MB, QC; 19 elsewhere — Gaming, Liquor and Cannabis Act (AB) and the provinces' liquor acts, as
--            summarised e.g. by WorldAtlas "What's the legal drinking age in Canada?".
--   tobacco  federal Tobacco and Vaping Products Act floor 18; provincial: AB 18 (Tobacco, Smoking and Vaping Reduction
--            Act), BC 19 (Tobacco and Vapour Products Control Act, gov.bc.ca), SK 19 since February 2024 (Tobacco and
--            Vapour Products Control Act amendments, Global News 2024), MB 18 (Non-Smokers Health Protection and Vapour
--            Products Act), ON 19 (Smoke-Free Ontario Act, 2017, ontario.ca/laws/statute/17s26), QC 18 (Tobacco Control
--            Act), NB 19 (Tobacco and Electronic Cigarette Sales Act), NS 19 (Tobacco Access Act), PE 21 (Tobacco and
--            Electronic Smoking Device Sales and Access Act, 2019 amendment, Canadian Cancer Society release), NL 19
--            (Tobacco and Vapour Products Control Act), YT 19 (Tobacco and Vaping Products Control and Regulation Act),
--            NT 19 (Tobacco and Vapour Products Control Act, Bill 41, 2019), NU 19 (Tobacco and Smoking Act).
--   cannabis 18 in AB, 21 in QC, 19 elsewhere — provincial cannabis acts. The class covers cannabis *accessories*; whether a
--            province's cannabis age applies to accessories (the federal Cannabis Act floor is 18) is for counsel.
--   hours    alcohol in ON: delivery 09:00–23:00, pickup 07:00–23:00 (AGCO "Hours for liquor sale, service and
--            delivery"); alcohol in AB: 10:00–02:00 (AGLC Retail Liquor Store Handbook, hours of sale). No other province
--            has a window here yet — counsel to supply them before a market opens there.
--   delivery tobacco and cannabis accessories stay banned categories by configuration (northline.catalogue
--            .banned-categories) until counsel answers H2; their rows exist so the ages are known.
CREATE TABLE region.age_rules (
  province         text    NOT NULL CHECK (province ~ '^[A-Z]{2}$'),
  age_class        text    NOT NULL CHECK (age_class IN ('alcohol', 'tobacco', 'cannabis')),
  minimum_age      integer NOT NULL CHECK (minimum_age BETWEEN 16 AND 25),
  delivery_allowed boolean NOT NULL DEFAULT true,
  pickup_allowed   boolean NOT NULL DEFAULT true,
  delivery_from    time,
  delivery_until   time,
  pickup_from      time,
  pickup_until     time,
  source           text    NOT NULL CHECK (char_length(btrim(source)) BETWEEN 1 AND 300),
  confirmed        boolean NOT NULL DEFAULT false,    -- counsel confirmed the row (none yet)
  updated_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (province, age_class),
  CONSTRAINT chk_age_rules_delivery_window CHECK ((delivery_from IS NULL) = (delivery_until IS NULL)),
  CONSTRAINT chk_age_rules_pickup_window CHECK ((pickup_from IS NULL) = (pickup_until IS NULL))
);
COMMENT ON TABLE region.age_rules IS
  'Minimum age and sale/delivery windows per province and age-restriction class (V340). Counsel to confirm every row.';

INSERT INTO region.age_rules (province, age_class, minimum_age, source) VALUES
  ('AB', 'alcohol', 18, 'Gaming, Liquor and Cannabis Act (Alberta)'),
  ('BC', 'alcohol', 19, 'Liquor Control and Licensing Act (British Columbia)'),
  ('SK', 'alcohol', 19, 'The Alcohol Control Regulations, 2016 (Saskatchewan)'),
  ('MB', 'alcohol', 18, 'The Liquor, Gaming and Cannabis Control Act (Manitoba)'),
  ('ON', 'alcohol', 19, 'Liquor Licence and Control Act, 2019 (Ontario)'),
  ('QC', 'alcohol', 18, 'Loi sur les infractions en matière de boissons alcooliques (Québec)'),
  ('NB', 'alcohol', 19, 'Liquor Control Act (New Brunswick)'),
  ('NS', 'alcohol', 19, 'Liquor Control Act (Nova Scotia)'),
  ('PE', 'alcohol', 19, 'Liquor Control Act (Prince Edward Island)'),
  ('NL', 'alcohol', 19, 'Liquor Control Act (Newfoundland and Labrador)'),
  ('YT', 'alcohol', 19, 'Liquor Act (Yukon)'),
  ('NT', 'alcohol', 19, 'Liquor Act (Northwest Territories)'),
  ('NU', 'alcohol', 19, 'Liquor Act (Nunavut)'),
  ('AB', 'tobacco', 18, 'Tobacco, Smoking and Vaping Reduction Act (Alberta)'),
  ('BC', 'tobacco', 19, 'Tobacco and Vapour Products Control Act (British Columbia)'),
  ('SK', 'tobacco', 19, 'The Tobacco and Vapour Products Control Act (Saskatchewan), 19 since February 2024'),
  ('MB', 'tobacco', 18, 'The Non-Smokers Health Protection and Vapour Products Act (Manitoba)'),
  ('ON', 'tobacco', 19, 'Smoke-Free Ontario Act, 2017'),
  ('QC', 'tobacco', 18, 'Loi concernant la lutte contre le tabagisme (Québec)'),
  ('NB', 'tobacco', 19, 'Tobacco and Electronic Cigarette Sales Act (New Brunswick)'),
  ('NS', 'tobacco', 19, 'Tobacco Access Act (Nova Scotia)'),
  ('PE', 'tobacco', 21, 'Tobacco and Electronic Smoking Device Sales and Access Act (Prince Edward Island)'),
  ('NL', 'tobacco', 19, 'Tobacco and Vapour Products Control Act (Newfoundland and Labrador)'),
  ('YT', 'tobacco', 19, 'Tobacco and Vaping Products Control and Regulation Act (Yukon)'),
  ('NT', 'tobacco', 19, 'Tobacco and Vapour Products Control Act (Northwest Territories)'),
  ('NU', 'tobacco', 19, 'Tobacco and Smoking Act (Nunavut)'),
  ('AB', 'cannabis', 18, 'Gaming, Liquor and Cannabis Act (Alberta); accessories: counsel'),
  ('BC', 'cannabis', 19, 'Cannabis Control and Licensing Act (British Columbia); accessories: counsel'),
  ('SK', 'cannabis', 19, 'The Cannabis Control (Saskatchewan) Act; accessories: counsel'),
  ('MB', 'cannabis', 19, 'The Liquor, Gaming and Cannabis Control Act (Manitoba); accessories: counsel'),
  ('ON', 'cannabis', 19, 'Cannabis Licence Act, 2018 (Ontario); accessories: counsel'),
  ('QC', 'cannabis', 21, 'Loi encadrant le cannabis (Québec); accessories: counsel'),
  ('NB', 'cannabis', 19, 'Cannabis Control Act (New Brunswick); accessories: counsel'),
  ('NS', 'cannabis', 19, 'Cannabis Control Act (Nova Scotia); accessories: counsel'),
  ('PE', 'cannabis', 19, 'Cannabis Control Act (Prince Edward Island); accessories: counsel'),
  ('NL', 'cannabis', 19, 'Cannabis Control Act (Newfoundland and Labrador); accessories: counsel'),
  ('YT', 'cannabis', 19, 'Cannabis Control and Regulation Act (Yukon); accessories: counsel'),
  ('NT', 'cannabis', 19, 'Cannabis Legalization and Regulation Implementation Act (Northwest Territories); accessories: counsel'),
  ('NU', 'cannabis', 19, 'Cannabis Act (Nunavut); accessories: counsel');

UPDATE region.age_rules SET delivery_from = '09:00', delivery_until = '23:00', pickup_from = '07:00', pickup_until = '23:00',
       source = source || '; hours: AGCO "Hours for liquor sale, service and delivery"'
 WHERE province = 'ON' AND age_class = 'alcohol';
UPDATE region.age_rules SET delivery_from = '10:00', delivery_until = '02:00', pickup_from = '10:00', pickup_until = '02:00',
       source = source || '; hours: AGLC Retail Liquor Store Handbook'
 WHERE province = 'AB' AND age_class = 'alcohol';
