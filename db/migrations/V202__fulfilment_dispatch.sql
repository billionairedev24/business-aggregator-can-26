-- Fulfilment workstream (range V200–V209). S-86: pooled run planning, courier shifts and assignment, the courier's
-- stops with proof of delivery. Additive only: V010's runs / stops / couriers keep their columns and checks.
-- See docs/DECISIONS.md "S-86".

-- An order that needs a courier, as the orders module hands it over (fulfilment never reads orders' tables).
CREATE TABLE fulfilment.deliveries (
  order_id     text PRIMARY KEY,                       -- logical ref → orders.orders.id
  order_ref    text,
  order_type   text NOT NULL CHECK (order_type IN ('goods', 'food')),
  kind         text NOT NULL CHECK (kind IN ('pooled', 'direct')),
  market       text NOT NULL,
  window_id    text,                                   -- logical ref → orders.delivery_windows.id (pooled)
  window_label text,                                   -- R-701
  starts_at    timestamptz,                            -- the run's window (pooled)
  ends_at      timestamptz,
  order_by     timestamptz,                            -- customers' cut-off: the run is planned from here
  pack_by      timestamptz,                            -- shops' cut-off
  ready_by     timestamptz,                            -- food: when the kitchen expects it ready
  customer_id  text,                                   -- logical ref → identity.users.id
  -- where it goes: street, unit, city, postal, note, lat, lng. Personal data — shown to the assigned courier only,
  -- and cleared 30 days after the delivery ends (DispatchJobs).
  dropoff      jsonb,
  pin          text NOT NULL CHECK (pin ~ '^[0-9]{4}$'), -- the customer's drop-off PIN (proof option)
  state        text NOT NULL DEFAULT 'waiting'
               CHECK (state IN ('waiting', 'planned', 'picked_up', 'delivered', 'cancelled')),
  run_id       text REFERENCES fulfilment.runs (id),
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  CHECK (kind = 'direct' OR (window_id IS NOT NULL AND order_by IS NOT NULL))
);
CREATE INDEX ix_deliveries_waiting ON fulfilment.deliveries (market, kind, order_by) WHERE state = 'waiting';
CREATE INDEX ix_deliveries_run ON fulfilment.deliveries (run_id);

-- The shops an order is picked up from, and whether each has packed.
CREATE TABLE fulfilment.delivery_pickups (
  order_id    text NOT NULL REFERENCES fulfilment.deliveries (order_id),
  merchant_id text NOT NULL,                           -- logical ref → merchants.merchants.id
  packed_at   timestamptz,
  PRIMARY KEY (order_id, merchant_id)
);

-- Couriers: the market they work in and when they were last given a run (fair assignment).
ALTER TABLE fulfilment.couriers
  ADD COLUMN market text,
  ADD COLUMN active boolean NOT NULL DEFAULT true,
  ADD COLUMN last_assigned_at timestamptz,
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
CREATE UNIQUE INDEX ux_couriers_user ON fulfilment.couriers (user_id);
CREATE INDEX ix_couriers_market_status ON fulfilment.couriers (market, status) WHERE active;

-- Shifts: scheduled by ops, started and ended by the courier.
CREATE TABLE fulfilment.shifts (
  id         text PRIMARY KEY,
  courier_id text NOT NULL REFERENCES fulfilment.couriers (id),
  starts_at  timestamptz NOT NULL,
  ends_at    timestamptz NOT NULL,
  state      text NOT NULL DEFAULT 'scheduled' CHECK (state IN ('scheduled', 'on', 'done', 'cancelled')),
  started_at timestamptz,
  ended_at   timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (ends_at > starts_at)
);
CREATE INDEX ix_shifts_courier ON fulfilment.shifts (courier_id, starts_at);
-- at most one shift on at a time per courier
CREATE UNIQUE INDEX ux_shifts_on ON fulfilment.shifts (courier_id) WHERE state = 'on';

-- Runs: market, the run code, the window, how the stops were ordered, and when it moved.
ALTER TABLE fulfilment.runs
  ADD COLUMN market text,
  ADD COLUMN label text,
  ADD COLUMN part integer NOT NULL DEFAULT 1,          -- a window split over several couriers: 1, 2, …
  ADD COLUMN starts_at timestamptz,
  ADD COLUMN ends_at timestamptz,
  ADD COLUMN pack_by timestamptz,
  ADD COLUMN heuristic text,
  ADD COLUMN planned_at timestamptz,
  ADD COLUMN assigned_at timestamptz,
  ADD COLUMN started_at timestamptz,
  ADD COLUMN done_at timestamptz;
CREATE UNIQUE INDEX ux_runs_window_part ON fulfilment.runs (window_id, part) WHERE window_id IS NOT NULL;
CREATE INDEX ix_runs_market_start ON fulfilment.runs (market, starts_at);
-- a courier has at most one run that isn't done
CREATE UNIQUE INDEX ux_runs_courier_open ON fulfilment.runs (courier_id) WHERE courier_id IS NOT NULL AND state <> 'done';

-- Stops: the shop of a pickup, progress, and the proof kind at a drop-off (photo / signature in object storage under
-- proof_media_id, or the customer's PIN).
ALTER TABLE fulfilment.stops
  ADD COLUMN merchant_id text,
  ADD COLUMN state text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'arrived', 'done')),
  ADD COLUMN done_at timestamptz,
  ADD COLUMN proof_kind text CHECK (proof_kind IS NULL OR proof_kind IN ('photo', 'signature', 'pin')),
  ADD CONSTRAINT chk_stops_dropoff_proof CHECK (kind <> 'dropoff' OR state <> 'done' OR proof_kind IS NOT NULL);
