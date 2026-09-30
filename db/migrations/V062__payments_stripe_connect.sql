-- Finance workstream (V060–V069) · S-11 Stripe Connect Express live adapter. Additive only; see docs/DECISIONS.md.

-- ── payment intents: what capture, transfers and the re-authorization job need ─────────────────────────────────
-- V011's CHECK lists requires_action | authorized | captured | refunded | failed; a hold can also be canceled
-- (refund before capture, replaced by a re-authorization, lapsed at Stripe).
ALTER TABLE payments.payment_intents DROP CONSTRAINT IF EXISTS payment_intents_state_check;
ALTER TABLE payments.payment_intents
  ADD CONSTRAINT payment_intents_state_check
      CHECK (state IN ('requires_action', 'authorized', 'captured', 'refunded', 'failed', 'canceled')),
  ADD COLUMN stripe_customer text,                                        -- cus_… the card is saved to
  ADD COLUMN stripe_charge text,                                          -- ch_… (latest charge; transfers' source)
  ADD COLUMN transfer_group text,                                         -- order:<id> | booking:<id>
  ADD COLUMN ref_type text CHECK (ref_type IN ('booking', 'order_line')),
  ADD COLUMN ref_id text,
  ADD COLUMN merchant_id text,
  ADD COLUMN authorized_at timestamptz,
  ADD COLUMN capture_before timestamptz,                                  -- Stripe's deadline for the capture
  ADD COLUMN reauthorizations integer NOT NULL DEFAULT 0,                 -- how many holds came before this one
  ADD COLUMN reauth_failed_at timestamptz,                                -- last failed renewal (retried after 12 h)
  ADD COLUMN replaced_by text REFERENCES payments.payment_intents (id),   -- the renewed hold
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
-- The card (pm_…) goes in V011's payment_method_ref.
CREATE INDEX payment_intents_renewal ON payments.payment_intents (coalesce(capture_before, authorized_at))
  WHERE state = 'authorized';
CREATE INDEX payment_intents_ref ON payments.payment_intents (ref_type, ref_id);

-- Stripe Customer per Northline user (metadata = our user id only; cards live at Stripe).
CREATE TABLE payments.stripe_customers (
  customer_id text PRIMARY KEY,                                           -- logical ref → identity.users
  stripe_customer text NOT NULL UNIQUE,                                   -- cus_…
  created_at timestamptz NOT NULL DEFAULT now()
);

-- ── transfers: the transfer group and what was pulled back for refunds ──────────────────────────────────────────
ALTER TABLE payments.transfers
  ADD COLUMN transfer_group text,
  ADD COLUMN reversed_cents bigint NOT NULL DEFAULT 0 CHECK (reversed_cents >= 0);
CREATE UNIQUE INDEX transfers_stripe_transfer ON payments.transfers (stripe_transfer);

-- ── refunds: the transfer reversal that recovered a refund of released money ────────────────────────────────────
ALTER TABLE payments.refunds
  ADD COLUMN stripe_transfer_reversal text,                               -- trr_…
  ADD COLUMN reversed_cents bigint NOT NULL DEFAULT 0;

-- ── payouts: the instant payout fee recovered from the connected account (account debit) ─────────────────────────
ALTER TABLE payments.payouts ADD COLUMN stripe_fee_transfer text;
CREATE UNIQUE INDEX payouts_stripe_payout ON payments.payouts (stripe_payout);
