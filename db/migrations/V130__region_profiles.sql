-- 2026-10-01 (S-134): the region model is the single source of truth for every province's facts (DECISIONS "S-134").
-- Additive only. Each province row gains:
--   time_zones   IANA zones, the first is the province's default (a market may name its own, else it keeps this one)
--   holidays     statutory holiday codes; the dates are computed per year by region.domain.HolidayRule
--   privacy_law  the private-sector privacy law cited to people in the province (PIPEDA unless a provincial act is
--                substantially similar: Alberta and BC PIPA, Quebec Law 25)
--   registries   business-registry adapter keys (merchants.registry_checks.source codes) that serve the province or,
--                on a market row, the city; none = a Northline agent checks by hand
-- and its tax profile (region.tax_profiles, the S-21 rates in force since 2025-04-01) is linked. Launch status stays
-- region.regions.stage (off | waitlist | pilot | live). The console edits these rows later (phase 3); configuration
-- (REGION_PROVINCES) can still override the served provinces and their zones.

ALTER TABLE region.regions
  ADD COLUMN time_zones text[],
  ADD COLUMN holidays text[] NOT NULL DEFAULT '{}',
  ADD COLUMN privacy_law text CHECK (privacy_law IN ('pipeda', 'ab_pipa', 'bc_pipa', 'qc_law25')),
  ADD COLUMN registries text[] NOT NULL DEFAULT '{}',
  ADD CONSTRAINT chk_regions_time_zones CHECK (time_zones IS NULL OR cardinality(time_zones) >= 1);

UPDATE region.regions r
   SET time_zones = v.zones, holidays = v.holidays, privacy_law = v.law, registries = v.registries
  FROM (VALUES
    ('AB', '{America/Edmonton}'::text[],
     '{new_year,family_day,good_friday,victoria_day,canada_day,heritage_day,labour_day,thanksgiving,remembrance_day,christmas,boxing_day}'::text[],
     'ab_pipa', '{alberta_corporate_registry}'::text[]),
    ('BC', '{America/Vancouver,America/Edmonton,America/Creston,America/Dawson_Creek,America/Fort_Nelson}',
     '{new_year,family_day,good_friday,victoria_day,canada_day,bc_day,labour_day,truth_reconciliation,thanksgiving,remembrance_day,christmas}',
     'bc_pipa', '{}'),
    ('MB', '{America/Winnipeg}',
     '{new_year,louis_riel_day,good_friday,victoria_day,canada_day,labour_day,truth_reconciliation,thanksgiving,christmas}',
     'pipeda', '{}'),
    ('NB', '{America/Moncton}',
     '{new_year,family_day,good_friday,canada_day,new_brunswick_day,labour_day,remembrance_day,christmas}',
     'pipeda', '{}'),
    ('NL', '{America/St_Johns,America/Goose_Bay}',
     '{new_year,good_friday,canada_day,labour_day,remembrance_day,christmas}',
     'pipeda', '{}'),
    ('NS', '{America/Halifax}',
     '{new_year,heritage_day_february,good_friday,canada_day,labour_day,christmas}',
     'pipeda', '{}'),
    ('NT', '{America/Yellowknife,America/Inuvik}',
     '{new_year,good_friday,victoria_day,indigenous_peoples_day,canada_day,civic_holiday,labour_day,truth_reconciliation,thanksgiving,remembrance_day,christmas}',
     'pipeda', '{}'),
    ('NU', '{America/Iqaluit,America/Rankin_Inlet,America/Cambridge_Bay}',
     '{new_year,good_friday,victoria_day,canada_day,nunavut_day,civic_holiday,labour_day,thanksgiving,remembrance_day,christmas}',
     'pipeda', '{}'),
    ('ON', '{America/Toronto,America/Winnipeg,America/Atikokan}',
     '{new_year,family_day,good_friday,victoria_day,canada_day,labour_day,thanksgiving,christmas,boxing_day}',
     'pipeda', '{}'),
    ('PE', '{America/Halifax}',
     '{new_year,islander_day,good_friday,canada_day,labour_day,truth_reconciliation,remembrance_day,christmas}',
     'pipeda', '{}'),
    ('QC', '{America/Toronto,America/Blanc-Sablon}',
     '{new_year,good_friday,patriots_day,saint_jean_baptiste,canada_day,labour_day,thanksgiving,christmas}',
     'qc_law25', '{}'),
    ('SK', '{America/Regina,America/Swift_Current}',
     '{new_year,family_day,good_friday,victoria_day,canada_day,saskatchewan_day,labour_day,thanksgiving,remembrance_day,christmas}',
     'pipeda', '{}'),
    ('YT', '{America/Whitehorse}',
     '{new_year,good_friday,victoria_day,indigenous_peoples_day,canada_day,discovery_day,labour_day,truth_reconciliation,thanksgiving,remembrance_day,christmas}',
     'pipeda', '{}')
  ) AS v(province, zones, holidays, law, registries)
 WHERE r.kind = 'province' AND r.province = v.province;

-- Sales tax (fractions). MB's RST and SK/BC's PST are provincial sales taxes, stored as pst.
INSERT INTO region.tax_profiles (id, gst, pst, hst, qst, effective_from) VALUES
  ('tax-ab-2025', 0.05, NULL,  NULL, NULL,    DATE '2025-04-01'),
  ('tax-bc-2025', 0.05, 0.07,  NULL, NULL,    DATE '2025-04-01'),
  ('tax-mb-2025', 0.05, 0.07,  NULL, NULL,    DATE '2025-04-01'),
  ('tax-nb-2025', NULL, NULL,  0.15, NULL,    DATE '2025-04-01'),
  ('tax-nl-2025', NULL, NULL,  0.15, NULL,    DATE '2025-04-01'),
  ('tax-ns-2025', NULL, NULL,  0.14, NULL,    DATE '2025-04-01'),
  ('tax-nt-2025', 0.05, NULL,  NULL, NULL,    DATE '2025-04-01'),
  ('tax-nu-2025', 0.05, NULL,  NULL, NULL,    DATE '2025-04-01'),
  ('tax-on-2025', NULL, NULL,  0.13, NULL,    DATE '2025-04-01'),
  ('tax-pe-2025', NULL, NULL,  0.15, NULL,    DATE '2025-04-01'),
  ('tax-qc-2025', 0.05, NULL,  NULL, 0.09975, DATE '2025-04-01'),
  ('tax-sk-2025', 0.05, 0.06,  NULL, NULL,    DATE '2025-04-01'),
  ('tax-yt-2025', 0.05, NULL,  NULL, NULL,    DATE '2025-04-01')
ON CONFLICT (id) DO NOTHING;

UPDATE region.regions SET tax_profile_id = 'tax-' || lower(province) || '-2025'
 WHERE kind = 'province' AND tax_profile_id IS NULL;
