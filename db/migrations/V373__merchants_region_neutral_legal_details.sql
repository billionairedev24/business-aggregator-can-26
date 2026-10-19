-- 2026-10-19 (DECISIONS "2026-10-19 — Province legal details"): the Business step's legal details no longer name a
-- province. The structure a business incorporated in its own province chose was stored as corp_ab and its provincial
-- identifiers under alberta_* keys; they are now corp_prov and generic keys, the province being the business's own
-- (merchants.merchants.province):
--   corp_ab                                        → corp_prov
--   alberta_corporate_access_number                → provincial_registry_number
--   alberta_extra_provincial_registration          → extra_provincial_registration_number
--   attorney_for_service.alberta_address           → attorney_for_service.address_in_province
-- Widening plus a data rewrite of the rows that exist (no column is dropped): the structure CHECK and the V031
-- principal-role trigger accept both codes, so an api still running the previous release while this deploys keeps
-- working, and the api maps the old code and keys when it reads a row (merchants.domain.LegacyLegalDetails).

DO $$
DECLARE c text;
BEGIN
  FOR c IN
    SELECT conname FROM pg_constraint
     WHERE conrelid = 'merchants.merchants'::regclass AND contype = 'c'
       AND pg_get_constraintdef(oid) LIKE '%corp_ab%'
  LOOP
    EXECUTE format('ALTER TABLE merchants.merchants DROP CONSTRAINT %I', c);
  END LOOP;
END $$;

ALTER TABLE merchants.merchants
  ADD CONSTRAINT chk_merchants_structure CHECK (structure IS NULL OR structure IN
    ('sole', 'partnership', 'corp_prov', 'corp_ab', 'corp_fed', 'corp_ex', 'coop', 'nonprofit'));

-- V031's last line of defence, with the new code (the roles are unchanged).
CREATE OR REPLACE FUNCTION merchants.principal_role_check() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE st text;
BEGIN
  SELECT structure INTO st FROM merchants.merchants WHERE id = NEW.merchant_id;
  IF st IS NOT NULL AND NOT (
    (st = 'sole' AND NEW.role IN ('owner')) OR
    (st = 'partnership' AND NEW.role IN ('partner_signing', 'partner')) OR
    (st IN ('corp_prov', 'corp_ab', 'corp_fed', 'corp_ex') AND NEW.role IN ('director', 'officer', 'shareholder')) OR
    (st = 'coop' AND NEW.role IN ('chair', 'director', 'secretary', 'treasurer')) OR
    (st = 'nonprofit' AND NEW.role IN ('president', 'director', 'treasurer', 'secretary'))
  ) THEN
    RAISE EXCEPTION 'principal role % not allowed for structure %', NEW.role, st USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;

-- The rows: structure, the legal_details keys and the structure copy inside legal_details.
UPDATE merchants.merchants SET structure = 'corp_prov' WHERE structure = 'corp_ab';

UPDATE merchants.merchants
   SET legal_details = (legal_details - 'alberta_corporate_access_number')
         || jsonb_build_object('provincial_registry_number', legal_details->'alberta_corporate_access_number')
 WHERE legal_details ? 'alberta_corporate_access_number';

UPDATE merchants.merchants
   SET legal_details = (legal_details - 'alberta_extra_provincial_registration')
         || jsonb_build_object('extra_provincial_registration_number', legal_details->'alberta_extra_provincial_registration')
 WHERE legal_details ? 'alberta_extra_provincial_registration';

UPDATE merchants.merchants
   SET legal_details = jsonb_set(legal_details, '{attorney_for_service}',
         ((legal_details->'attorney_for_service') - 'alberta_address')
           || jsonb_build_object('address_in_province', legal_details->'attorney_for_service'->'alberta_address'))
 WHERE jsonb_typeof(legal_details->'attorney_for_service') = 'object'
   AND legal_details->'attorney_for_service' ? 'alberta_address';

UPDATE merchants.merchants
   SET legal_details = jsonb_set(legal_details, '{structure}', '"corp_prov"')
 WHERE legal_details->>'structure' = 'corp_ab';
