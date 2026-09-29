-- Finance workstream (V060–V069): columns the Studio's Earnings / Reports / Payouts / Refunds & disputes screens need
-- that the V011 baseline lacks. Additive only; no baseline column renamed or dropped. See docs/DECISIONS.md.

-- ── escrows: what the ledger table, reports and release rules need ─────────────────────────────────────────────
ALTER TABLE payments.escrows
  ADD COLUMN kind text CHECK (kind IN ('service', 'goods', 'food')),       -- release rule: 48 h / 7 days / handoff
  ADD COLUMN label text,                                                  -- "Brake pads", "Wiper blades ×2"
  ADD COLUMN order_number text,                                           -- "NL-48190" for orders, null for jobs
  ADD COLUMN customer_id text,                                            -- logical ref → identity.users
  ADD COLUMN customer_name text,                                          -- display form "D. Kowalski" (copied at hold)
  ADD COLUMN listing_id text,                                             -- logical ref → catalogue / food
  ADD COLUMN listing_name text,                                           -- "By listing" report
  ADD COLUMN source text CHECK (source IN ('search', 'repeat', 'embed', 'referral')),
  ADD COLUMN take_rate_bps integer,                                       -- tier take rate when the money was held
  ADD COLUMN fee_cents bigint,                                            -- Northline fee on amount_cents
  ADD COLUMN tax_cents bigint NOT NULL DEFAULT 0,                         -- GST/HST collected (remitted by Northline)
  ADD COLUMN occurred_at timestamptz,                                     -- job date / order date
  ADD COLUMN fulfilled_at timestamptz,                                    -- completed / delivered / handed off
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN version integer NOT NULL DEFAULT 0;
CREATE INDEX escrows_merchant_occurred ON payments.escrows (merchant_id, occurred_at DESC);
CREATE INDEX escrows_merchant_state ON payments.escrows (merchant_id, state);
CREATE INDEX escrows_release_due ON payments.escrows (release_at) WHERE state = 'held';
CREATE UNIQUE INDEX escrows_ref ON payments.escrows (ref_type, ref_id);
ALTER TABLE payments.escrows ADD CONSTRAINT escrows_fee_le_amount CHECK (fee_cents IS NULL OR fee_cents <= amount_cents);

CREATE UNIQUE INDEX payment_intents_stripe_pi ON payments.payment_intents (stripe_pi);
CREATE INDEX transfers_escrow ON payments.transfers (escrow_id);

-- ── payouts ───────────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE payments.payouts
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN item_count integer NOT NULL DEFAULT 0,                       -- "Jobs" column of Payout history
  ADD COLUMN payout_account_id text,
  ADD COLUMN destination text,                                            -- "TD ··3391" at the time of the payout
  ADD COLUMN requested_by text,                                           -- instant payouts: the owner who asked
  ADD COLUMN version integer NOT NULL DEFAULT 0,
  ADD CONSTRAINT payouts_state_check CHECK (state IN ('pending', 'in_transit', 'paid', 'failed', 'canceled'));
CREATE INDEX payouts_merchant_created ON payments.payouts (merchant_id, created_at DESC);

-- ── payout settings: V011 marks merchant_id as PK but never declared it ───────────────────────────────────────
ALTER TABLE payments.payout_settings
  ADD PRIMARY KEY (merchant_id),
  ADD COLUMN monthly_anchor text CHECK (monthly_anchor IN ('first', 'fifteenth', 'last')),
  ADD COLUMN reserve_percent smallint CHECK (reserve_percent BETWEEN 0 AND 100),
  ADD COLUMN updated_at timestamptz,
  ADD COLUMN updated_by text,
  ADD CONSTRAINT payout_settings_weekday CHECK (weekday IS NULL OR weekday BETWEEN 1 AND 7);

-- Stripe Connect Express account per merchant.
CREATE TABLE payments.connected_accounts (
  merchant_id text PRIMARY KEY,
  stripe_account text NOT NULL UNIQUE,                                    -- acct_…
  instant_payouts boolean NOT NULL DEFAULT false,                         -- eligible debit-linked external account
  created_at timestamptz NOT NULL DEFAULT now()
);

-- Bank accounts payouts go to. Never stores the account number: only the last 4 digits and Stripe's reference.
CREATE TABLE payments.payout_accounts (
  id text PRIMARY KEY,
  merchant_id text NOT NULL,
  method text NOT NULL CHECK (method IN ('instant', 'manual')),           -- Financial Connections | typed details
  institution_name text NOT NULL,                                         -- "TD Canada Trust"
  institution_number char(3),
  transit_number char(5),
  last4 char(4) NOT NULL,
  holder_name text NOT NULL,
  external_ref text NOT NULL,                                             -- ba_… / fca_… (Stripe)
  -- draft: entered, not confirmed · pending: confirmed, inside the 24 h hold · active · replaced · discarded
  state text NOT NULL CHECK (state IN ('draft', 'pending', 'active', 'replaced', 'discarded')),
  created_at timestamptz NOT NULL,
  created_by text NOT NULL,
  confirmed_at timestamptz,
  effective_at timestamptz,
  replaced_at timestamptz,
  version integer NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX payout_accounts_one_active ON payments.payout_accounts (merchant_id) WHERE state = 'active';
CREATE UNIQUE INDEX payout_accounts_one_pending ON payments.payout_accounts (merchant_id) WHERE state = 'pending';
CREATE INDEX payout_accounts_due ON payments.payout_accounts (effective_at) WHERE state = 'pending';

-- ── refunds ───────────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE payments.refunds
  ADD COLUMN merchant_id text,
  ADD COLUMN escrow_id text REFERENCES payments.escrows (id),
  ADD COLUMN dispute_id text REFERENCES payments.disputes (id),
  ADD COLUMN case_number text,                                            -- "RF-2201"
  ADD COLUMN what text,                                                   -- "Wiper blade wrong size"
  ADD COLUMN customer_name text,
  ADD COLUMN kind text NOT NULL DEFAULT 'refund' CHECK (kind IN ('refund', 'credit')),
  ADD COLUMN auto boolean NOT NULL DEFAULT false,                         -- < $25: approved unless contested
  ADD COLUMN contest_by timestamptz,
  ADD COLUMN contest_reason text,
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN decided_at timestamptz,
  ADD COLUMN paid_at timestamptz,
  ADD COLUMN stripe_refund text,
  ADD COLUMN version integer NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX refunds_case_number ON payments.refunds (case_number);
CREATE INDEX refunds_merchant_created ON payments.refunds (merchant_id, created_at DESC);
CREATE INDEX refunds_state ON payments.refunds (state);

-- ── disputes ──────────────────────────────────────────────────────────────────────────────────────────────────
-- evidence jsonb = [{id, kind, name, contentType, size, by, at, storageKey}] (files) — statements are columns.
ALTER TABLE payments.disputes
  ADD COLUMN merchant_id text,
  ADD COLUMN case_number text,                                            -- "DS-1188"
  ADD COLUMN subject text,                                                -- "Pre-purchase inspection"
  ADD COLUMN amount_cents bigint,
  ADD COLUMN customer_name text,
  ADD COLUMN customer_statement text,
  ADD COLUMN response text,                                               -- the merchant's response (draft until sent)
  ADD COLUMN response_updated_at timestamptz,
  ADD COLUMN offer_cents bigint,
  ADD COLUMN offer_state text CHECK (offer_state IN ('pending', 'accepted', 'declined', 'expired')),
  ADD COLUMN offer_expires_at timestamptz,
  ADD COLUMN refund_cents bigint,                                         -- decided refund
  ADD COLUMN respond_by timestamptz,
  ADD COLUMN opened_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN decided_at timestamptz,
  ADD COLUMN version integer NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX disputes_case_number ON payments.disputes (case_number);
CREATE INDEX disputes_merchant_state ON payments.disputes (merchant_id, state);

-- Platform benchmarks shown next to a merchant's own numbers ("refund rate · category avg 3.1%"). Precomputed so a
-- merchant never sees an average over fewer than five other businesses.
CREATE TABLE payments.benchmarks (
  kind text PRIMARY KEY CHECK (kind IN ('service', 'goods', 'food')),
  refund_rate_bps integer NOT NULL,
  merchants integer NOT NULL,
  updated_at timestamptz NOT NULL
);
INSERT INTO payments.benchmarks (kind, refund_rate_bps, merchants, updated_at) VALUES
  ('service', 310, 0, now()), ('goods', 310, 0, now()), ('food', 280, 0, now());
