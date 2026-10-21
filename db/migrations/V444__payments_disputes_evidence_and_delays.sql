-- S-156: card-dispute evidence submitted to Stripe from Northline, the Stripe dispute fee in the ledger, the
-- completion-photo delay on escrow releases, and Northline's compensation when a seller wins an appeal. Additive only.

-- Card disputes: the issuer's evidence deadline as Stripe sends it (respond_by stays two days before it, S-12).
ALTER TABLE payments.disputes ADD COLUMN stripe_evidence_due_by timestamptz;

-- The evidence an agent assembles in the console and submits once through DisputeEvidenceGateway. `fields` holds
-- Stripe's text fields (product_description, service_date, uncategorized_text, …); `files` maps Stripe's file fields
-- (receipt, service_documentation, customer_communication, …) to evidence ids of the case (the business's uploads);
-- `stripe_files` the file_… ids Stripe gave them. No personal data is copied here.
CREATE TABLE payments.card_dispute_evidence (
  dispute_id     text        PRIMARY KEY REFERENCES payments.disputes(id),
  fields         jsonb       NOT NULL DEFAULT '{}'::jsonb,
  files          jsonb       NOT NULL DEFAULT '{}'::jsonb,
  updated_by     text,
  updated_at     timestamptz,
  submitted_at   timestamptz,
  submitted_by   text,
  submitted_role text,
  stripe_files   jsonb       NOT NULL DEFAULT '{}'::jsonb,
  last_error     text,
  version        integer     NOT NULL DEFAULT 0
);

-- Stripe's dispute fee, from the dispute's balance transactions (one row each: positive = charged, negative = given
-- back). Northline carries it (ledger account `dispute_fees`); the row is the idempotency key of its posting.
CREATE TABLE payments.dispute_fees (
  balance_transaction text        PRIMARY KEY,
  dispute_id          text        NOT NULL REFERENCES payments.disputes(id),
  fee_cents           bigint      NOT NULL CHECK (fee_cents <> 0),
  at                  timestamptz NOT NULL
);
CREATE INDEX ix_dispute_fees_dispute ON payments.dispute_fees (dispute_id);

-- S-93 "Completion photo missing → escrow release delayed 48 h": one row per delayed escrow. Staff can lift it.
CREATE TABLE payments.release_delays (
  escrow_id     text        PRIMARY KEY REFERENCES payments.escrows(id),
  merchant_id   text        NOT NULL,
  booking_id    text        NOT NULL,
  hours         integer     NOT NULL CHECK (hours > 0),
  delayed_until timestamptz NOT NULL,
  created_at    timestamptz NOT NULL,
  lifted_at     timestamptz,
  lifted_by     text
);
CREATE INDEX ix_release_delays_merchant ON payments.release_delays (merchant_id, created_at DESC);

-- A seller's appeal against an agent's dispute decision was overturned: Northline pays the seller what the decision
-- cost them (ledger `appeal_compensation` → merchant balance, then a transfer to the connected account). The customer
-- keeps their refund. One per appeal.
CREATE TABLE payments.appeal_compensations (
  appeal_id       text        PRIMARY KEY,
  dispute_id      text        NOT NULL REFERENCES payments.disputes(id),
  merchant_id     text        NOT NULL,
  cents           bigint      NOT NULL CHECK (cents > 0),
  stripe_transfer text,
  created_by      text        NOT NULL,
  created_at      timestamptz NOT NULL
);
CREATE UNIQUE INDEX ux_appeal_compensations_dispute ON payments.appeal_compensations (dispute_id);
