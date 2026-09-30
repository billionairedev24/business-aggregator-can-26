-- Finance workstream (V060–V069) · S-12 Stripe webhooks. Additive only; see docs/DECISIONS.md.

-- Every Stripe event received (both endpoints), deduplicated by Stripe's event id and processed asynchronously.
-- payload = the event's data.object with personal fields removed (billing details, e-mail, names, addresses, evidence).
CREATE TABLE payments.stripe_events (
  id text PRIMARY KEY,                                                    -- evt_…
  type text NOT NULL,                                                     -- payout.paid, charge.dispute.created, …
  endpoint text NOT NULL CHECK (endpoint IN ('platform', 'connect')),
  account text,                                                           -- acct_… (Connect events)
  livemode boolean NOT NULL,
  object_id text,                                                         -- data.object.id
  created_at timestamptz NOT NULL,                                        -- Stripe's event time
  received_at timestamptz NOT NULL,
  payload jsonb NOT NULL,
  state text NOT NULL CHECK (state IN ('received', 'processed', 'ignored', 'failed')),
  attempts integer NOT NULL DEFAULT 0,
  processed_at timestamptz,
  error text
);
CREATE INDEX stripe_events_pending ON payments.stripe_events (received_at) WHERE state IN ('received', 'failed');
CREATE INDEX stripe_events_object ON payments.stripe_events (object_id, created_at);

-- Payouts: why Stripe returned one.
ALTER TABLE payments.payouts
  ADD COLUMN failure_code text,                                           -- Stripe's failure_code (account_closed, …)
  ADD COLUMN returned_fee_transfer text;                                  -- tr_… giving an instant fee back

-- Card disputes (chargebacks) from Stripe feed the existing disputes flow.
ALTER TABLE payments.disputes
  ADD COLUMN stripe_dispute text,                                         -- dp_… / du_…
  ADD COLUMN stripe_status text,                                          -- needs_response, under_review, won, lost, …
  ADD COLUMN stripe_reason text,                                          -- fraudulent, product_not_received, …
  ADD COLUMN stripe_updated_at timestamptz;                               -- created time of the last event applied
CREATE UNIQUE INDEX disputes_stripe_dispute ON payments.disputes (stripe_dispute);

-- Refunds: Stripe's status of the refund (pending, succeeded, failed, canceled).
ALTER TABLE payments.refunds ADD COLUMN stripe_status text;

-- Connected accounts: the latest account.updated.
ALTER TABLE payments.connected_accounts
  ADD COLUMN charges_enabled boolean,
  ADD COLUMN payouts_enabled boolean,
  ADD COLUMN requirements_due integer NOT NULL DEFAULT 0,
  ADD COLUMN requirements_past_due integer NOT NULL DEFAULT 0,
  ADD COLUMN disabled_reason text,
  ADD COLUMN stripe_updated_at timestamptz;
