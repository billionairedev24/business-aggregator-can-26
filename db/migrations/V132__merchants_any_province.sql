-- 2026-10-01 (S-134): a business may operate in any province or territory the region model opens (live, pilot or
-- waitlist), not only the four V030 listed. The CHECK on merchants.merchants.province named those four; it now only
-- asks for a two-letter code (the api checks the province against region.regions). Loosening only: no row changes.
DO $$
DECLARE c text;
BEGIN
  FOR c IN
    SELECT conname FROM pg_constraint
     WHERE conrelid = 'merchants.merchants'::regclass AND contype = 'c'
       AND pg_get_constraintdef(oid) LIKE '%province%'
  LOOP
    EXECUTE format('ALTER TABLE merchants.merchants DROP CONSTRAINT %I', c);
  END LOOP;
END $$;

ALTER TABLE merchants.merchants
  ADD CONSTRAINT chk_merchants_province CHECK (province IS NULL OR province ~ '^[A-Z]{2}$');
