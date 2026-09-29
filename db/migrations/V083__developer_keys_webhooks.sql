-- Settings & compliance workstream (range V080–V089). Settings › API & integrations, and the audit log.

-- API keys: the secret is shown once; only its SHA-256 is kept (V015 key_hash). prefix = the first characters shown
-- in the table so owners can tell keys apart.
ALTER TABLE developer.api_keys
  ADD COLUMN prefix     text,
  ADD COLUMN created_by text,
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
CREATE UNIQUE INDEX IF NOT EXISTS ux_api_keys_hash ON developer.api_keys(key_hash);
CREATE INDEX IF NOT EXISTS ix_api_keys_merchant ON developer.api_keys(merchant_id, created_at DESC);

-- Webhook signing secret: AES-256-GCM encrypted (the worker decrypts it to sign HMAC-SHA256 deliveries); secret_ref
-- names the key version ("db:v1") until a KMS adapter replaces it.
ALTER TABLE developer.webhook_endpoints
  ADD COLUMN secret_enc bytea,
  ADD COLUMN created_by text,
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX IF NOT EXISTS ix_webhook_endpoints_merchant ON developer.webhook_endpoints(merchant_id, created_at);
CREATE INDEX IF NOT EXISTS ix_webhook_deliveries_endpoint ON developer.webhook_deliveries(endpoint_id, at DESC);

-- Studio owners' privileged actions are audit-logged per business (Settings › Security › "Audit log · last 90 days").
ALTER TABLE developer.audit_log ADD COLUMN merchant_id text;
CREATE INDEX IF NOT EXISTS ix_audit_log_merchant ON developer.audit_log(merchant_id, at DESC) WHERE merchant_id IS NOT NULL;
