-- S-153: the balance of a deposit quote (held automatically before the event) and late-cancellation fees. Additive.

-- The balance: what an accepted deposit quote still owes (before tax and its tax), when it is held (by default 48 h
-- before the start), and how holding it went. pending → held | failed (after the last retry); none = nothing owed.
ALTER TABLE booking.bookings
  ADD COLUMN balance_cents        bigint      NOT NULL DEFAULT 0 CHECK (balance_cents >= 0),
  ADD COLUMN balance_tax_cents    bigint      NOT NULL DEFAULT 0 CHECK (balance_tax_cents >= 0),
  ADD COLUMN balance_state        text        NOT NULL DEFAULT 'none'
    CHECK (balance_state IN ('none', 'pending', 'held', 'failed', 'cancelled')),
  ADD COLUMN balance_due_at       timestamptz,
  ADD COLUMN balance_next_at      timestamptz,
  ADD COLUMN balance_attempts     integer     NOT NULL DEFAULT 0 CHECK (balance_attempts >= 0),
  ADD COLUMN balance_escrow_id    text,
  ADD COLUMN balance_failure      text,
  -- The late-cancellation policy as it was shown before booking (snapshot of Availability › Booking rules within the
  -- region's limits; free_cancel_until is start − window): a share of the price or a fixed fee. Then the cancellation
  -- itself: who, when, and the fee charged from the hold (0 = free).
  ADD COLUMN cancel_window_hours  integer     CHECK (cancel_window_hours BETWEEN 0 AND 720),
  ADD COLUMN cancel_fee_bps       integer     CHECK (cancel_fee_bps BETWEEN 0 AND 10000),
  ADD COLUMN cancel_fee_fixed_cents bigint    CHECK (cancel_fee_fixed_cents >= 0),
  ADD COLUMN cancelled_at         timestamptz,
  ADD COLUMN cancelled_by         text,
  ADD COLUMN cancel_fee_cents     bigint      CHECK (cancel_fee_cents >= 0),
  ADD COLUMN cancel_fee_tax_cents bigint      CHECK (cancel_fee_tax_cents >= 0);

-- The balance job's lookup: pending balances whose next attempt is due.
CREATE INDEX ix_bookings_balance_due ON booking.bookings (balance_next_at) WHERE balance_state = 'pending';
