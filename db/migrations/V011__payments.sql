-- schema: payments · owner: Java (java)
-- Generated from the Northline spec (docs/DATA_MODEL.md). Hand-edit freely; keep column names.
CREATE SCHEMA IF NOT EXISTS payments;

-- Mirror of Stripe PaymentIntents (manual capture for escrow).
CREATE TABLE payments.payment_intents (
  -- PK
  id text PRIMARY KEY,
  -- unique
  stripe_pi text,
  customer_id text,
  amount_cents bigint,
  -- CAD
  currency char(3),
  -- manual | automatic
  capture_method text CHECK (capture_method IN ('manual', 'automatic')),
  -- requires_action | authorized | captured | refunded | failed
  state text CHECK (state IN ('requires_action', 'authorized', 'captured', 'refunded', 'failed')),
  -- pm_… token
  payment_method_ref text,
  three_ds boolean
);
-- TODO indexes/constraints: unique(stripe_pi)
-- outbox events: payment.held · payment.captured

-- The hold: what is owed to whom, when it releases.
CREATE TABLE payments.escrows (
  -- PK
  id text PRIMARY KEY,
  -- FK
  payment_intent_id text,
  -- booking | order_line
  ref_type text CHECK (ref_type IN ('booking', 'order_line')),
  ref_id text,
  merchant_id text,
  amount_cents bigint,
  -- auto 48 h / 24 h
  release_at timestamptz,
  released_at timestamptz,
  -- held | released | refunded | disputed
  state text CHECK (state IN ('held', 'released', 'refunded', 'disputed'))
);
-- TODO indexes/constraints: index(release_at) for the auto-release job · index(merchant_id,state)
-- outbox events: payment.released

-- Stripe transfers to connected accounts, net of take rate.
CREATE TABLE payments.transfers (
  -- PK
  id text PRIMARY KEY,
  -- FK
  escrow_id text,
  stripe_transfer text,
  gross_cents bigint,
  fee_cents bigint,
  net_cents bigint,
  at timestamptz
);

-- Scheduled or instant payouts per merchant.
CREATE TABLE payments.payouts (
  -- PK
  id text PRIMARY KEY,
  merchant_id text,
  stripe_payout text,
  amount_cents bigint,
  -- scheduled | instant
  kind text CHECK (kind IN ('scheduled', 'instant')),
  fee_cents bigint,
  state text,
  arrives_at timestamptz
);
-- TODO indexes/constraints: index(merchant_id,at)
-- outbox events: payout.sent

-- Schedule, reserve, bank change hold.
CREATE TABLE payments.payout_settings (
  -- PK
  merchant_id text,
  -- daily | weekly | monthly | manual
  schedule text CHECK (schedule IN ('daily', 'weekly', 'monthly', 'manual')),
  weekday smallint,
  reserve_cents bigint,
  -- 24 h hold
  bank_changed_at timestamptz
);
-- outbox events: payout_settings.changed

-- Double-entry ledger reconciled daily against Stripe.
CREATE TABLE payments.ledger_entries (
  -- PK
  id text PRIMARY KEY,
  -- escrow | revenue | merchant:<id> | stripe_fees | tax_payable
  account text,
  debit_cents bigint,
  credit_cents bigint,
  ref_type text,
  ref_id text,
  at timestamptz
);
-- TODO indexes/constraints: index(account,at) · append-only

-- Auto (< $25) or agent-decided refunds.
CREATE TABLE payments.refunds (
  -- PK
  id text PRIMARY KEY,
  payment_intent_id text,
  order_line_id text,
  booking_id text,
  amount_cents bigint,
  reason text,
  -- merchant | platform
  charged_to text CHECK (charged_to IN ('merchant', 'platform')),
  -- requested | seller_review | agent_review | approved | denied | paid
  state text CHECK (state IN ('requested', 'seller_review', 'agent_review', 'approved', 'denied', 'paid'))
);
-- TODO indexes/constraints: index(state)
-- outbox events: refund.requested · refund.decided

-- Cases with evidence from both sides and an agent decision.
CREATE TABLE payments.disputes (
  -- PK
  id text PRIMARY KEY,
  ref_type text,
  ref_id text,
  opened_by text,
  evidence jsonb,
  -- open | seller_replied | agent | decided | appealed
  state text CHECK (state IN ('open', 'seller_replied', 'agent', 'decided', 'appealed')),
  -- full_refund | partial | release | goodwill
  decision text CHECK (decision IN ('full_refund', 'partial', 'release', 'goodwill')),
  decided_by text,
  note_i18n jsonb
);
-- TODO indexes/constraints: index(state)
-- outbox events: dispute.opened · dispute.decided

-- Foreign keys (in-module only)
ALTER TABLE payments.escrows ADD CONSTRAINT fk_escrows_payment_intent_id FOREIGN KEY (payment_intent_id) REFERENCES payments.payment_intents(id);
-- logical ref (cross-module, no FK): payments.escrows.merchant_id → merchants.merchants.id
ALTER TABLE payments.transfers ADD CONSTRAINT fk_transfers_escrow_id FOREIGN KEY (escrow_id) REFERENCES payments.escrows(id);
-- logical ref (cross-module, no FK): payments.payouts.merchant_id → merchants.merchants.id
-- logical ref (cross-module, no FK): payments.payout_settings.merchant_id → merchants.merchants.id
ALTER TABLE payments.refunds ADD CONSTRAINT fk_refunds_payment_intent_id FOREIGN KEY (payment_intent_id) REFERENCES payments.payment_intents(id);
-- logical ref (cross-module, no FK): payments.refunds.order_line_id → orders.order_lines.id
ALTER TABLE payments.disputes ADD CONSTRAINT fk_disputes_ref_id FOREIGN KEY (ref_id) REFERENCES payments.escrows(id);
