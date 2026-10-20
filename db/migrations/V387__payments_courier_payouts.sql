-- S-151 (range V385–V394): couriers are paid through Stripe Connect as recipients. Their Express account
-- (transfers capability only), and each payout: a transfer from the platform to the account, then a payout to the bank
-- Stripe collected at onboarding. Never stores an account number: Stripe's references and the last four digits only.
-- See docs/DECISIONS.md "S-151" and docs/runbooks/stripe.md § Courier payouts.

CREATE TABLE payments.courier_accounts (
  user_id          text PRIMARY KEY,                   -- logical ref → identity.users.id (the courier)
  stripe_account   text NOT NULL UNIQUE,               -- acct_…
  payouts_enabled  boolean NOT NULL DEFAULT false,
  requirements_due integer NOT NULL DEFAULT 0,
  external_ref     text,                               -- ba_… the default payout destination
  bank_last4       text CHECK (bank_last4 IS NULL OR bank_last4 ~ '^[0-9]{4}$'),
  last_event_at    timestamptz,                        -- the latest account.updated applied
  created_at       timestamptz NOT NULL,
  updated_at       timestamptz NOT NULL
);

CREATE TABLE payments.courier_payouts (
  id              text PRIMARY KEY,
  user_id         text NOT NULL,
  stripe_account  text NOT NULL,
  amount_cents    bigint NOT NULL CHECK (amount_cents > 0),
  stripe_transfer text,                                -- tr_… platform → courier account
  stripe_payout   text UNIQUE,                         -- po_… courier account → bank
  state           text NOT NULL CHECK (state IN ('pending', 'in_transit', 'paid', 'failed', 'canceled')),
  arrives_at      timestamptz,
  failure         text,                                -- Stripe's failure code
  created_at      timestamptz NOT NULL,
  updated_at      timestamptz NOT NULL
);
CREATE INDEX ix_courier_payouts_user ON payments.courier_payouts (user_id, created_at DESC);
