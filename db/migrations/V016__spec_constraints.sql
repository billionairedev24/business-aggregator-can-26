-- Constraints that the spec states in prose. These are the rules the UI enforces; the DB must enforce them too.

-- merchants: GST format, required unless sole/partnership
ALTER TABLE merchants.merchants
  ADD CONSTRAINT chk_gst_format CHECK (gst_number IS NULL OR gst_number ~ '^\d{9}\s?RT\s?\d{4}$'),
  ADD CONSTRAINT chk_gst_required CHECK (structure IN ('sole','partnership') OR gst_number IS NOT NULL OR status = 'applicant'),
  ADD CONSTRAINT chk_display_name_len CHECK (char_length(display_name) BETWEEN 2 AND 80),
  ADD CONSTRAINT chk_business_number CHECK (business_number IS NULL OR business_number ~ '^\d{9}$');
CREATE INDEX ix_merchants_type_status ON merchants.merchants(type, status);
CREATE INDEX ix_merchants_tier ON merchants.merchants(tier);
CREATE UNIQUE INDEX ux_merchants_bn ON merchants.merchants(business_number) WHERE business_number IS NOT NULL;

-- principals: ownership 0–100, sum ≤ 100 per merchant
ALTER TABLE merchants.merchant_principals ADD CONSTRAINT chk_pct CHECK (ownership_pct IS NULL OR ownership_pct BETWEEN 0 AND 100);
CREATE INDEX ix_principals_merchant ON merchants.merchant_principals(merchant_id);
CREATE OR REPLACE FUNCTION merchants.principals_sum_check() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (SELECT coalesce(sum(ownership_pct),0) FROM merchants.merchant_principals WHERE merchant_id = NEW.merchant_id) > 100 THEN
    RAISE EXCEPTION 'ownership_pct for merchant % exceeds 100', NEW.merchant_id USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER trg_principals_sum AFTER INSERT OR UPDATE ON merchants.merchant_principals
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION merchants.principals_sum_check();

-- categories per merchant: provider 10 · seller 5 · both 10 · kitchen 3
CREATE OR REPLACE FUNCTION merchants.category_limit_check() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE lim int; mtype text; cnt int;
BEGIN
  SELECT type INTO mtype FROM merchants.merchants WHERE id = NEW.merchant_id;
  lim := CASE mtype WHEN 'provider' THEN 10 WHEN 'seller' THEN 5 WHEN 'both' THEN 10 WHEN 'kitchen' THEN 3 ELSE 10 END;
  SELECT count(*) INTO cnt FROM merchants.merchant_categories WHERE merchant_id = NEW.merchant_id AND status <> 'rejected';
  IF cnt > lim THEN
    RAISE EXCEPTION 'merchant % may select at most % categories', NEW.merchant_id, lim USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER trg_category_limit AFTER INSERT OR UPDATE ON merchants.merchant_categories
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION merchants.category_limit_check();

-- storefront: slug, brand colour, always-on sections
ALTER TABLE merchants.storefronts
  ADD CONSTRAINT chk_slug CHECK (slug ~ '^[a-z0-9-]{3,40}$'),
  ADD CONSTRAINT chk_brand_color CHECK (brand_color IS NULL OR brand_color ~ '^#[0-9a-fA-F]{6}$');
CREATE UNIQUE INDEX ux_storefront_domain ON merchants.storefronts(custom_domain) WHERE custom_domain IS NOT NULL;
ALTER TABLE merchants.storefront_sections
  ADD CONSTRAINT chk_always_on CHECK (kind NOT IN ('hero','cta') OR enabled = true);
CREATE UNIQUE INDEX ux_section_position ON merchants.storefront_sections(storefront_id, position);
-- allowed kinds per page_kind (see docs/spec/storefront-sections.json)
CREATE OR REPLACE FUNCTION merchants.section_kind_check() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE pk text;
BEGIN
  SELECT page_kind INTO pk FROM merchants.storefronts WHERE id = NEW.storefront_id;
  IF NOT (
    (pk = 'business_page' AND NEW.kind IN ('hero','cta','about','services','reviews','area','gallery','faq','featured','catalogue','delivery','policies')) OR
    (pk = 'store' AND NEW.kind IN ('hero','cta','about','featured','catalogue','delivery','reviews','policies')) OR
    (pk = 'menu_page' AND NEW.kind IN ('hero','cta','about','menu','hours','fulfil','reviews','permit'))
  ) THEN RAISE EXCEPTION 'section kind % not allowed on %', NEW.kind, pk USING ERRCODE = 'check_violation'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_section_kind BEFORE INSERT OR UPDATE ON merchants.storefront_sections FOR EACH ROW EXECUTE FUNCTION merchants.section_kind_check();

-- quotes: itemized, totals consistent, validity
ALTER TABLE booking.quotes
  ADD CONSTRAINT chk_valid_hours CHECK (valid_hours IN (24, 72, 168, 336)),
  ADD CONSTRAINT chk_totals CHECK (total_cents = subtotal_cents + tax_cents);
CREATE INDEX ix_quotes_request ON booking.quotes(request_id);
CREATE INDEX ix_quotes_merchant_state ON booking.quotes(merchant_id, state);
ALTER TABLE booking.quote_lines
  ADD CONSTRAINT chk_line_desc CHECK (char_length(description) BETWEEN 1 AND 160),
  ADD CONSTRAINT chk_line_qty CHECK (qty > 0),
  ADD CONSTRAINT chk_line_amount CHECK ((kind = 'discount' AND amount_cents <= 0) OR (kind <> 'discount' AND amount_cents > 0));
CREATE UNIQUE INDEX ux_quote_line_pos ON booking.quote_lines(quote_id, position);
CREATE OR REPLACE FUNCTION booking.quote_subtotal_check() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE q record; s bigint; n int;
BEGIN
  SELECT * INTO q FROM booking.quotes WHERE id = coalesce(NEW.quote_id, OLD.quote_id);
  IF q.state IN ('sent','viewed','accepted') THEN
    SELECT coalesce(sum(amount_cents),0), count(*) INTO s, n FROM booking.quote_lines WHERE quote_id = q.id;
    IF n = 0 THEN RAISE EXCEPTION 'quote % has no lines', q.id USING ERRCODE = 'check_violation'; END IF;
    IF s <> q.subtotal_cents THEN RAISE EXCEPTION 'quote % subtotal % does not match lines %', q.id, q.subtotal_cents, s USING ERRCODE = 'check_violation'; END IF;
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER trg_quote_subtotal AFTER INSERT OR UPDATE OR DELETE ON booking.quote_lines
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION booking.quote_subtotal_check();
