-- S-151 (range V385–V394): cancelled deliveries and runs, failed drop-offs, return pickups and couriers' earnings.
-- Widens CHECKs and adds tables; no column renamed or dropped. See docs/DECISIONS.md "S-151".

-- A run whose every stop was cancelled is `cancelled` (its courier is free again). A courier still has at most one open
-- run: the partial index now leaves cancelled runs out too.
ALTER TABLE fulfilment.runs DROP CONSTRAINT runs_state_check;
ALTER TABLE fulfilment.runs ADD CONSTRAINT runs_state_check
  CHECK (state IN ('planned', 'loading', 'en_route', 'done', 'cancelled'));
DROP INDEX fulfilment.ux_runs_courier_open;
CREATE UNIQUE INDEX ux_runs_courier_open ON fulfilment.runs (courier_id)
  WHERE courier_id IS NOT NULL AND state NOT IN ('done', 'cancelled');

-- Stops: removed from a run (`cancelled`: the order was cancelled, or moved to another run), a drop-off the courier
-- couldn't hand over (proof `failed`, with the reason and the courier's note), and `collect` — a return picked up at the
-- customer's door. `return_id` ties a collect / return stop to the customer's return (orders.returns).
ALTER TABLE fulfilment.stops DROP CONSTRAINT stops_state_check;
ALTER TABLE fulfilment.stops ADD CONSTRAINT stops_state_check CHECK (state IN ('pending', 'arrived', 'done', 'cancelled'));
ALTER TABLE fulfilment.stops DROP CONSTRAINT stops_kind_check;
ALTER TABLE fulfilment.stops ADD CONSTRAINT stops_kind_check CHECK (kind IN ('pickup', 'dropoff', 'return', 'collect'));
ALTER TABLE fulfilment.stops DROP CONSTRAINT stops_proof_kind_check;
ALTER TABLE fulfilment.stops ADD CONSTRAINT stops_proof_kind_check
  CHECK (proof_kind IS NULL OR proof_kind IN ('photo', 'signature', 'pin', 'id_refused', 'failed'));
ALTER TABLE fulfilment.stops
  ADD COLUMN fail_reason text CHECK (fail_reason IS NULL OR fail_reason IN ('absent', 'unsafe', 'refused', 'access', 'other')),
  ADD COLUMN fail_note text CHECK (fail_note IS NULL OR length(fail_note) <= 500),
  ADD COLUMN return_id text;
CREATE INDEX ix_stops_order ON fulfilment.stops (order_id);

-- Deliveries: a failed drop-off (`failed`, the goods go back to the shops) and the attempt it is on (re-delivery).
ALTER TABLE fulfilment.deliveries DROP CONSTRAINT deliveries_state_check;
ALTER TABLE fulfilment.deliveries ADD CONSTRAINT deliveries_state_check
  CHECK (state IN ('waiting', 'planned', 'picked_up', 'delivered', 'cancelled', 'returning', 'returned', 'failed'));
ALTER TABLE fulfilment.deliveries ADD COLUMN attempts integer NOT NULL DEFAULT 1;

-- A return the shop approved for pickup: collected at the customer's door, brought back to the shop.
CREATE TABLE fulfilment.return_pickups (
  return_id    text PRIMARY KEY,                       -- logical ref → orders.returns.id
  order_id     text NOT NULL,                          -- logical ref → orders.orders.id
  return_ref   text,
  merchant_id  text NOT NULL,                          -- the shop it goes back to
  market       text NOT NULL,
  customer_id  text,
  -- where it is collected (street, unit, city, postal, note). Personal data — shown to the assigned courier only and
  -- cleared 30 days after the pickup ends (DispatchJobs), as deliveries.dropoff is.
  collect_from jsonb,
  state        text NOT NULL DEFAULT 'waiting'
               CHECK (state IN ('waiting', 'planned', 'collected', 'returned', 'cancelled')),
  run_id       text REFERENCES fulfilment.runs (id),
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_return_pickups_waiting ON fulfilment.return_pickups (market) WHERE state = 'waiting';

-- What a courier earned for a finished run under the market's pay model (region configuration), kept with the model it
-- was paid under. One row per run; payments posts the same amount to the courier's ledger account.
CREATE TABLE fulfilment.courier_earnings (
  run_id          text PRIMARY KEY REFERENCES fulfilment.runs (id),
  courier_id      text NOT NULL REFERENCES fulfilment.couriers (id),
  courier_user_id text NOT NULL,                       -- logical ref → identity.users.id
  market          text NOT NULL,
  pickups         integer NOT NULL,
  dropoffs        integer NOT NULL,
  failed          integer NOT NULL,
  collects        integer NOT NULL,
  returns         integer NOT NULL,
  run_cents       bigint NOT NULL CHECK (run_cents >= 0),
  stops_cents     bigint NOT NULL CHECK (stops_cents >= 0),
  total_cents     bigint NOT NULL CHECK (total_cents = run_cents + stops_cents),
  model           jsonb NOT NULL,
  earned_at       timestamptz NOT NULL
);
CREATE INDEX ix_courier_earnings_user ON fulfilment.courier_earnings (courier_user_id, earned_at);
