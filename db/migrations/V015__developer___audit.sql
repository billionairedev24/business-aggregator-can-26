-- schema: developer · audit · owner: Java (java)
-- Generated from the Northline spec (docs/DATA_MODEL.md). Hand-edit freely; keep column names.
CREATE SCHEMA IF NOT EXISTS developer · audit;

-- Scoped keys per merchant (hashed).
CREATE TABLE developer · audit.api_keys (
  -- PK
  id text PRIMARY KEY,
  merchant_id text,
  name text,
  scopes text[],
  key_hash bytea,
  last_used_at timestamptz,
  rate_limit integer,
  revoked_at timestamptz
);
-- TODO indexes/constraints: index(key_hash)

-- HMAC-signed endpoints and subscribed events.
CREATE TABLE developer · audit.webhook_endpoints (
  -- PK
  id text PRIMARY KEY,
  merchant_id text,
  url text,
  -- KMS
  secret_ref text,
  events text[],
  active boolean
);

-- Attempts with response codes; retried with backoff.
CREATE TABLE developer · audit.webhook_deliveries (
  -- PK
  id text PRIMARY KEY,
  endpoint_id text,
  event_id text,
  attempt integer,
  status_code integer,
  at timestamptz
);
-- TODO indexes/constraints: index(endpoint_id,at)

-- Transactional outbox → Debezium → Kafka.
CREATE TABLE developer · audit.outbox (
  -- PK
  id text PRIMARY KEY,
  aggregate text,
  aggregate_id text,
  type text,
  payload jsonb,
  trace_id text,
  at timestamptz
);
-- TODO indexes/constraints: Debezium reads WAL; rows purged after publish
-- outbox events: everything

-- Immutable record of every privileged action (Console, Studio owners).
CREATE TABLE developer · audit.audit_log (
  -- PK
  id text PRIMARY KEY,
  actor_id text,
  role text,
  action text,
  target_type text,
  target_id text,
  before jsonb,
  after jsonb,
  at timestamptz
);
-- TODO indexes/constraints: append-only · nightly export to cold storage · 7-year retention

-- Foreign keys (in-module only)
-- logical ref (cross-module, no FK): developer · audit.api_keys.merchant_id → merchants.merchants.id
-- logical ref (cross-module, no FK): developer · audit.webhook_endpoints.merchant_id → merchants.merchants.id
ALTER TABLE developer · audit.webhook_deliveries ADD CONSTRAINT fk_webhook_deliveries_endpoint_id FOREIGN KEY (endpoint_id) REFERENCES developer · audit.webhook_endpoints(id);
