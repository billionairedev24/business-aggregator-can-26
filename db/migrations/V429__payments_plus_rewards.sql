-- S-152: the money of Northline Plus and of provider-funded rewards in payments. Additive only.
--
-- Plus invoices Stripe Billing (or the local fake) collected: one row per invoice, the revenue's source for the console
-- (ledger: stripe_balance → revenue + tax_payable, ref_type plus_invoice). No card or personal data.
CREATE TABLE payments.plus_invoices (
  id              text PRIMARY KEY,
  provider        text NOT NULL CHECK (provider IN ('local', 'stripe')),
  invoice_id      text NOT NULL UNIQUE,
  subscription_id text NOT NULL,
  household_id    text NOT NULL,
  plan            text NOT NULL CHECK (plan IN ('monthly', 'annual')),
  amount_cents    bigint NOT NULL CHECK (amount_cents >= 0),
  tax_cents       bigint NOT NULL DEFAULT 0 CHECK (tax_cents >= 0),
  paid_at         timestamptz NOT NULL
);
CREATE INDEX ix_plus_invoices_paid ON payments.plus_invoices (paid_at);

-- What a provider-funded reward charged the business for its extra points (ledger merchant:<id> → rewards_funded),
-- deducted from its money before it is paid out: a reversal of the sale's transfer when there is one, else its balance.
-- Refunds of the sale give back their share (negative rows are never written: refunds are separate rows, kind refund).
CREATE TABLE payments.reward_charges (
  id               text PRIMARY KEY,
  ref              text NOT NULL UNIQUE,        -- the reward credit (trust.reward_credits.id) or its refund's key
  kind             text NOT NULL CHECK (kind IN ('charge', 'refund')),
  merchant_id      text NOT NULL,
  escrow_id        text NOT NULL,
  cents            bigint NOT NULL CHECK (cents > 0),
  transfer_reversal text,
  created_at       timestamptz NOT NULL
);
CREATE INDEX ix_reward_charges_merchant ON payments.reward_charges (merchant_id, created_at);
