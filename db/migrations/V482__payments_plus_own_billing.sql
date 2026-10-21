-- S-162 (range V482–V491): Northline bills Plus itself — an RFC 5545 RRULE per membership, one PaymentIntent per paid
-- period (Stripe is called for PaymentIntents, SetupIntents and refunds only; no Stripe Billing). Additive only:
-- payments.plus_invoices (V426) is kept and no longer written; loyalty.memberships keeps its columns
-- (subscription_id now holds payments.plus_schedules.id, provider the card processor: local | stripe).
-- No card or personal data: payment methods are Stripe ids (pm_…), people are identity.users ids.

-- One row per membership the household started (a new one each time it joins again). The rule and the price are fixed
-- when the plan starts; a plan change waits in next_* until the next renewal.
CREATE TABLE payments.plus_schedules (
  id                   text PRIMARY KEY,                    -- plus_<ULID>
  household_id         text NOT NULL,                       -- logical ref → identity.households
  payer_id             text NOT NULL,                       -- logical ref → identity.users
  plan                 text NOT NULL CHECK (plan IN ('monthly', 'annual')),
  rrule                text NOT NULL CHECK (rrule ~ '^FREQ=(MONTHLY|YEARLY)(;[A-Z]+=[A-Z0-9,+-]+)*$'),
  dtstart              timestamptz NOT NULL,                -- the first paid period's start (the join, or the trial's end), UTC
  price_cents          bigint NOT NULL CHECK (price_cents >= 0),  -- before tax
  province             text NOT NULL CHECK (province ~ '^[A-Z]{2}$'),  -- the tax's place of supply at the join
  payment_method       text,                                -- pm_… chosen for Plus; null = the payer's default card
  next_plan            text CHECK (next_plan IN ('monthly', 'annual')),
  next_rrule           text,
  next_price_cents     bigint CHECK (next_price_cents >= 0),
  status               text NOT NULL CHECK (status IN ('incomplete', 'trialing', 'active', 'past_due', 'canceled')),
  current_period_end   timestamptz NOT NULL,                -- the end of what was paid for (or of the trial)
  cancel_at_period_end boolean NOT NULL DEFAULT false,
  ended_reason         text CHECK (ended_reason IN ('canceled', 'lapsed', 'refunded', 'unconfirmed')),
  ended_at             timestamptz,
  created_at           timestamptz NOT NULL,
  updated_at           timestamptz NOT NULL,
  CONSTRAINT chk_plus_schedules_next CHECK ((next_plan IS NULL) = (next_rrule IS NULL) AND (next_plan IS NULL) = (next_price_cents IS NULL))
);
-- one running membership per household
CREATE UNIQUE INDEX ux_plus_schedules_household_running ON payments.plus_schedules (household_id) WHERE status <> 'canceled';
CREATE INDEX ix_plus_schedules_payer ON payments.plus_schedules (payer_id);

-- The periods: the trial (free), then one per occurrence of the rule. A period is charged at most once: one
-- PaymentIntent per period (Stripe idempotency key per membership and period), unique here too.
CREATE TABLE payments.plus_periods (
  id               text PRIMARY KEY,
  schedule_id      text NOT NULL REFERENCES payments.plus_schedules (id),
  seq              integer NOT NULL CHECK (seq >= 0),
  starts_at        timestamptz NOT NULL,
  ends_at          timestamptz NOT NULL,
  plan             text NOT NULL CHECK (plan IN ('monthly', 'annual')),
  state            text NOT NULL CHECK (state IN
                     ('free', 'scheduled', 'retrying', 'requires_action', 'paid', 'lapsed', 'void', 'refunded')),
  amount_cents     bigint NOT NULL DEFAULT 0 CHECK (amount_cents >= 0),  -- before tax
  tax_cents        bigint NOT NULL DEFAULT 0 CHECK (tax_cents >= 0),
  province         text,
  tax_calculation  text,                                    -- taxcalc_… of the attempt that was paid
  tax_transaction  text,                                    -- tax_… reported to Stripe Tax (null until reported)
  payment_intent   text UNIQUE,
  attempts         integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  next_attempt_at  timestamptz,
  failure          text,                                    -- the last refusal: a Stripe decline code or authentication_required
  paid_at          timestamptz,
  refund_id        text,                                    -- re_…
  refunded_at      timestamptz,
  created_at       timestamptz NOT NULL,
  updated_at       timestamptz NOT NULL,
  CONSTRAINT uq_plus_periods_seq UNIQUE (schedule_id, seq),
  CONSTRAINT uq_plus_periods_start UNIQUE (schedule_id, starts_at),
  CONSTRAINT chk_plus_periods_order CHECK (ends_at > starts_at),
  CONSTRAINT chk_plus_periods_paid CHECK (state NOT IN ('paid', 'refunded') OR (paid_at IS NOT NULL AND payment_intent IS NOT NULL))
);
CREATE INDEX ix_plus_periods_due ON payments.plus_periods (next_attempt_at)
  WHERE state IN ('scheduled', 'retrying', 'requires_action');
CREATE INDEX ix_plus_periods_untaxed ON payments.plus_periods (paid_at)
  WHERE state = 'paid' AND tax_transaction IS NULL AND tax_calculation IS NOT NULL;

-- Every charge attempt (the first, each retry, the member's own confirmation): the key a concurrent job run collides on.
CREATE TABLE payments.plus_attempts (
  period_id      text NOT NULL REFERENCES payments.plus_periods (id),
  attempt        integer NOT NULL CHECK (attempt >= 1),
  at             timestamptz NOT NULL,
  off_session    boolean NOT NULL,
  outcome        text NOT NULL CHECK (outcome IN ('pending', 'paid', 'declined', 'authentication_required', 'processing')),
  code           text,                                      -- Stripe's decline code
  payment_intent text,
  PRIMARY KEY (period_id, attempt)
);
