-- Finance workstream (V060–V069) · S-21 Stripe Tax sync. Additive only; see docs/DECISIONS.md.

-- ── Tax quoted at checkout (Stripe Tax calculation, or the local fake's fixed Canadian rates) ─────────────────────
-- Only the province of supply is kept (no street, city or postal code). Stripe keeps a calculation 90 days; the sync
-- recalculates at capture when it has expired.
CREATE TABLE payments.tax_calculations (
  id                 text        PRIMARY KEY,
  stripe_calculation text        NOT NULL,                                -- taxcalc_… (fake: taxcalc_local_…)
  merchant_id        text        NOT NULL,                                -- logical ref → merchants.merchants
  kind               text        NOT NULL CHECK (kind IN ('service', 'goods', 'food')),
  province           text        NOT NULL CHECK (province IN ('AB', 'BC', 'MB', 'NB', 'NL', 'NS', 'NT', 'NU', 'ON', 'PE', 'QC', 'SK', 'YT')),
  jurisdiction       text        NOT NULL,                                -- ab_gst | bc_gst_pst | on_hst | qc_gst_qst | …
  amount_cents       bigint      NOT NULL CHECK (amount_cents >= 0),      -- taxable amount (tax exclusive)
  tax_cents          bigint      NOT NULL CHECK (tax_cents >= 0),
  breakdown          jsonb       NOT NULL,                                -- [{taxType, percent, taxCents}]
  ref_type           text        CHECK (ref_type IN ('booking', 'order_line')),   -- set when checkout uses it
  ref_id             text,
  expires_at         timestamptz NOT NULL,
  created_at         timestamptz NOT NULL
);
CREATE INDEX tax_calculations_ref ON payments.tax_calculations (ref_type, ref_id, created_at DESC);

-- ── Tax transactions reported to Stripe Tax: one per captured sale, one per refund / lost chargeback ─────────────
-- Written in the transaction that captures or refunds (state pending), then reported by the sync (listener + job).
-- reference is the dedupe key and Stripe's transaction reference: sale_<escrowId> | refund_<refundId> |
-- chargeback_<disputeId>. Amounts are positive; kind says which way they go.
CREATE TABLE payments.tax_transactions (
  id                 text        PRIMARY KEY,
  reference          text        NOT NULL UNIQUE,
  kind               text        NOT NULL CHECK (kind IN ('sale', 'reversal')),
  merchant_id        text        NOT NULL,
  escrow_id          text        NOT NULL,                                -- logical ref → payments.escrows
  escrow_kind        text        NOT NULL CHECK (escrow_kind IN ('service', 'goods', 'food')),
  original_reference text,                                                -- reversal: the sale it reverses
  calculation_id     text,                                                -- → payments.tax_calculations
  province           text        NOT NULL,
  jurisdiction       text        NOT NULL,
  amount_cents       bigint      NOT NULL CHECK (amount_cents >= 0),      -- taxable amount sold / reversed
  tax_cents          bigint      NOT NULL CHECK (tax_cents >= 0),         -- tax Northline collected / gave back
  period             text        NOT NULL CHECK (period ~ '^\d{4}-Q[1-4]$'),   -- Edmonton quarter of occurred_at
  occurred_at        timestamptz NOT NULL,
  state              text        NOT NULL CHECK (state IN ('pending', 'recorded', 'failed')),
  stripe_transaction text        UNIQUE,                                  -- tax_…
  stripe_tax_cents   bigint,                                              -- what Stripe Tax reports (reconciliation)
  attempts           integer     NOT NULL DEFAULT 0,
  error              text,
  recorded_at        timestamptz,
  reconciled_at      timestamptz,
  created_at         timestamptz NOT NULL,
  CHECK ((kind = 'reversal') = (original_reference IS NOT NULL))
);
CREATE INDEX tax_transactions_pending ON payments.tax_transactions (created_at) WHERE state IN ('pending', 'failed');
CREATE INDEX tax_transactions_totals ON payments.tax_transactions (merchant_id, period, jurisdiction)
  WHERE state = 'recorded';
CREATE INDEX tax_transactions_period ON payments.tax_transactions (period, state);

-- ── What the sync added to each row of the Studio's read model (payments.tax_jurisdiction_totals, V082) ─────────
-- V082 sorts after this file, so its table can't be altered here; this companion table (same key) holds the sync's
-- part. collected_cents = base_cents + sales − reversals, never below 0 (V082's CHECK); a quarter whose reversals
-- exceed its sales keeps the difference visible here. A row written before the sync first touched it (dev seed)
-- keeps its collected_cents as base_cents.
CREATE TABLE payments.tax_totals_sync (
  merchant_id        text        NOT NULL,
  period             text        NOT NULL CHECK (period ~ '^\d{4}-Q[1-4]$'),
  jurisdiction       text        NOT NULL,
  base_cents         bigint      NOT NULL DEFAULT 0 CHECK (base_cents >= 0),
  sales_tax_cents    bigint      NOT NULL DEFAULT 0 CHECK (sales_tax_cents >= 0),
  reversed_tax_cents bigint      NOT NULL DEFAULT 0 CHECK (reversed_tax_cents >= 0),
  transaction_count  integer     NOT NULL DEFAULT 0,
  synced_at          timestamptz NOT NULL,
  PRIMARY KEY (merchant_id, period, jurisdiction)
);

-- ── Refunds give back the GST/HST on the refunded part (S-41's "refunds don't reverse GST on the original sale") ──
ALTER TABLE payments.refunds
  ADD COLUMN tax_cents bigint NOT NULL DEFAULT 0 CHECK (tax_cents >= 0);  -- on top of amount_cents, to the card
