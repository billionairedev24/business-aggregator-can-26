-- V004 TODO "check(role in allowed set for merchants.structure)" — the x-principals roles of
-- docs/spec/legal-details.schema.json. Sits next to the V016 triggers as the last line of defence; the API validates
-- first and answers 422.
CREATE OR REPLACE FUNCTION merchants.principal_role_check() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE st text;
BEGIN
  SELECT structure INTO st FROM merchants.merchants WHERE id = NEW.merchant_id;
  IF st IS NOT NULL AND NOT (
    (st = 'sole' AND NEW.role IN ('owner')) OR
    (st = 'partnership' AND NEW.role IN ('partner_signing', 'partner')) OR
    (st IN ('corp_ab', 'corp_fed', 'corp_ex') AND NEW.role IN ('director', 'officer', 'shareholder')) OR
    (st = 'coop' AND NEW.role IN ('chair', 'director', 'secretary', 'treasurer')) OR
    (st = 'nonprofit' AND NEW.role IN ('president', 'director', 'treasurer', 'secretary'))
  ) THEN
    RAISE EXCEPTION 'principal role % not allowed for structure %', NEW.role, st USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_principal_role BEFORE INSERT OR UPDATE ON merchants.merchant_principals
  FOR EACH ROW EXECUTE FUNCTION merchants.principal_role_check();
