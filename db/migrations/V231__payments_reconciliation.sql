-- Console batch 2 (range V230–V239). S-85: the daily reconciliation of Stripe (the platform account's balance
-- transactions and the payouts Stripe reported) against the payments ledger's cash at Stripe (account
-- stripe_balance). One row per platform-zone day, its differences item by item, and how finance resolved a day.
-- Additive only. See docs/DECISIONS.md "S-85".
CREATE TABLE payments.reconciliation_days (
  day           date PRIMARY KEY,                          -- in the platform zone (REGION_PLATFORM_ZONE)
  from_at       timestamptz NOT NULL,
  to_at         timestamptz NOT NULL,
  stripe_cents  bigint NOT NULL,                           -- Σ Stripe amounts (charges +, refunds/disputes/payouts −)
  ledger_cents  bigint NOT NULL,                           -- Σ stripe_balance debits − credits
  fee_cents     bigint NOT NULL DEFAULT 0,                 -- Stripe's processing fees that day (not in the ledger)
  items         integer NOT NULL DEFAULT 0,                -- objects compared
  mismatches    integer NOT NULL DEFAULT 0,
  status        text NOT NULL CHECK (status IN ('matched', 'mismatch', 'resolved')),
  computed_at   timestamptz NOT NULL,
  resolved_note text CHECK (resolved_note IS NULL OR char_length(resolved_note) BETWEEN 1 AND 500),
  resolved_by   text,                                      -- logical ref → identity.users.id (staff)
  resolved_at   timestamptz,
  CHECK ((status = 'resolved') = (resolved_at IS NOT NULL))
);

CREATE TABLE payments.reconciliation_items (
  id              text PRIMARY KEY,
  day             date NOT NULL REFERENCES payments.reconciliation_days (day) ON DELETE CASCADE,
  kind            text NOT NULL CHECK (kind IN ('charge', 'refund', 'dispute', 'payout', 'other')),
  stripe_id       text,                                    -- ch_ / py_ / re_ / dp_ / po_ / txn_
  stripe_cents    bigint,                                  -- null: Stripe has nothing for it
  ledger_ref_type text,                                    -- escrow | order_delivery | refund | dispute | payout
  ledger_ref_id   text,
  ledger_cents    bigint,                                  -- null: the ledger has nothing for it
  status          text NOT NULL CHECK (status IN ('matched', 'missing_in_ledger', 'missing_at_stripe', 'amount_differs'))
);
CREATE INDEX ix_reconciliation_items_day ON payments.reconciliation_items (day, status);
