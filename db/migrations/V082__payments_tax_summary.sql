-- Settings & compliance workstream (range V080–V089). Stripe & compliance › "Tax · Stripe Tax + marketplace
-- facilitator". Read model of tax collected per jurisdiction and quarter, written by the Stripe Tax sync (finance /
-- worker); the Studio only reads it. Amounts are CAD cents.
CREATE TABLE payments.tax_jurisdiction_totals (
  merchant_id      text        NOT NULL,
  period           text        NOT NULL CHECK (period ~ '^\d{4}-Q[1-4]$'),
  -- ab_gst | bc_gst_pst | platform_fee_gst | …
  jurisdiction     text        NOT NULL,
  collected_cents  bigint      NOT NULL DEFAULT 0 CHECK (collected_cents >= 0),
  -- remitted_by_northline | not_selling | charged_on_invoice
  handling         text        NOT NULL CHECK (handling IN ('remitted_by_northline', 'not_selling', 'charged_on_invoice')),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (merchant_id, period, jurisdiction)
);
-- logical ref (cross-module, no FK): payments.tax_jurisdiction_totals.merchant_id → merchants.merchants.id
