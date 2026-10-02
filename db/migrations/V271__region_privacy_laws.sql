-- S-105: the privacy laws of the region model. region.regions.privacy_law (V130) says which law a province's people
-- are under; this table says what that law requires of a request: its name (en, fr), the regulator a person can
-- complain to, and the response deadline and the one extension it allows. Code reads these rows; no law, deadline
-- or province is written in code (S-134). Values drafted from the statutes for legal review (DECISIONS 2026-09-30
-- S-105):
--   PIPEDA s. 8(3)–(4): 30 days, extendable by up to 30 days.
--   Alberta PIPA ss. 28, 31: 45 days, extendable by up to 30 days.
--   BC PIPA ss. 29, 31: 30 days (a "day" excludes Saturdays and holidays, s. 1), extendable by up to 30 days.
--   Québec private sector act s. 32 (as amended by Law 25): 30 days, no extension.
CREATE TABLE region.privacy_laws (
  code            text    PRIMARY KEY CHECK (code IN ('pipeda', 'ab_pipa', 'bc_pipa', 'qc_law25')),
  name_i18n       jsonb   NOT NULL,      -- {en, fr}: full name
  short_i18n      jsonb   NOT NULL,      -- {en, fr}: "PIPEDA" / "LPRPDE"
  authority_i18n  jsonb   NOT NULL,      -- {en, fr}: the regulator
  authority_url   text    NOT NULL CHECK (authority_url ~ '^https://'),
  response_days   integer NOT NULL CHECK (response_days BETWEEN 1 AND 90),
  business_days   boolean NOT NULL DEFAULT false,   -- count only weekdays that are not the province's holidays
  extension_days  integer NOT NULL DEFAULT 0 CHECK (extension_days BETWEEN 0 AND 90)
);

INSERT INTO region.privacy_laws (code, name_i18n, short_i18n, authority_i18n, authority_url, response_days,
                                 business_days, extension_days) VALUES
  ('pipeda',
   '{"en": "Personal Information Protection and Electronic Documents Act", "fr": "Loi sur la protection des renseignements personnels et les documents électroniques"}',
   '{"en": "PIPEDA", "fr": "LPRPDE"}',
   '{"en": "Office of the Privacy Commissioner of Canada", "fr": "Commissariat à la protection de la vie privée du Canada"}',
   'https://www.priv.gc.ca', 30, false, 30),
  ('ab_pipa',
   '{"en": "Personal Information Protection Act (Alberta)", "fr": "Personal Information Protection Act (Alberta)"}',
   '{"en": "PIPA (Alberta)", "fr": "PIPA (Alberta)"}',
   '{"en": "Office of the Information and Privacy Commissioner of Alberta", "fr": "Commissariat à l''information et à la protection de la vie privée de l''Alberta"}',
   'https://oipc.ab.ca', 45, false, 30),
  ('bc_pipa',
   '{"en": "Personal Information Protection Act (British Columbia)", "fr": "Personal Information Protection Act (Colombie-Britannique)"}',
   '{"en": "PIPA (BC)", "fr": "PIPA (C.-B.)"}',
   '{"en": "Office of the Information and Privacy Commissioner for British Columbia", "fr": "Commissariat à l''information et à la protection de la vie privée de la Colombie-Britannique"}',
   'https://www.oipc.bc.ca', 30, true, 30),
  ('qc_law25',
   '{"en": "Act respecting the protection of personal information in the private sector (Law 25)", "fr": "Loi sur la protection des renseignements personnels dans le secteur privé (Loi 25)"}',
   '{"en": "Law 25", "fr": "Loi 25"}',
   '{"en": "Commission d''accès à l''information du Québec", "fr": "Commission d''accès à l''information du Québec"}',
   'https://www.cai.gouv.qc.ca', 30, false, 0);
