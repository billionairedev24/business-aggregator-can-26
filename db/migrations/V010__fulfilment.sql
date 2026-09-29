-- schema: fulfilment · owner: Go (go)
-- Generated from the Northline spec (docs/DATA_MODEL.md). Hand-edit freely; keep column names.
CREATE SCHEMA IF NOT EXISTS fulfilment;

-- A courier's pooled or direct run.
CREATE TABLE fulfilment.runs (
  -- PK
  id text PRIMARY KEY,
  zone_id text,
  window_id text,
  courier_id text,
  -- pooled | direct
  kind text CHECK (kind IN ('pooled', 'direct')),
  -- planned | loading | en_route | done
  state text CHECK (state IN ('planned', 'loading', 'en_route', 'done')),
  -- ordered stops + ETAs
  route jsonb
);
-- TODO indexes/constraints: index(courier_id,state)
-- outbox events: delivery.en_route

-- Pickups and drop-offs with proof.
CREATE TABLE fulfilment.stops (
  -- PK
  id text PRIMARY KEY,
  -- FK
  run_id text,
  order_id text,
  -- pickup | dropoff
  kind text CHECK (kind IN ('pickup', 'dropoff')),
  seq integer,
  eta timestamptz,
  arrived_at timestamptz,
  proof_media_id text,
  -- sealed bag
  scan_ok boolean
);
-- TODO indexes/constraints: index(run_id,seq)
-- outbox events: delivery.delivered

-- Own-fleet and contracted couriers.
CREATE TABLE fulfilment.couriers (
  -- PK
  id text PRIMARY KEY,
  user_id text,
  -- bike | ebike | car | van
  vehicle text CHECK (vehicle IN ('bike', 'ebike', 'car', 'van')),
  -- offline | available | on_run
  status text CHECK (status IN ('offline', 'available', 'on_run')),
  rating numeric(3,2)
);
-- outbox events: courier.offline

-- Live GPS — not in Postgres; 24 h retention, fanned out by the tracking gateway.
CREATE TABLE fulfilment.positions (
  courier_id text,
  lat/lng double precision,
  at ms,
  speed double precision
);
-- TODO indexes/constraints: Redis Streams · TTL 24 h
-- outbox events: position.updated (WebSocket)

-- Foreign keys (in-module only)
ALTER TABLE fulfilment.runs ADD CONSTRAINT fk_runs_courier_id FOREIGN KEY (courier_id) REFERENCES fulfilment.couriers(id);
ALTER TABLE fulfilment.stops ADD CONSTRAINT fk_stops_run_id FOREIGN KEY (run_id) REFERENCES fulfilment.runs(id);
-- logical ref (cross-module, no FK): fulfilment.stops.order_id → orders.orders.id
-- logical ref (cross-module, no FK): fulfilment.couriers.user_id → identity.users.id
