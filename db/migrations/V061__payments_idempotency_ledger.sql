-- Finance workstream: idempotency keys (the database store used under the `local` and `test` profiles; Redis in
-- production) and the append-only rule of the ledger (V011 "TODO … append-only").

CREATE TABLE payments.idempotency_keys (
  scope text NOT NULL,                                 -- "<merchantId>:<userId>:<operation>" or "step-up"
  key text NOT NULL,
  fingerprint text NOT NULL,                           -- SHA-256 of the request body
  status integer,                                      -- null while the first request is still running
  body text,
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  PRIMARY KEY (scope, key)
);
CREATE INDEX idempotency_keys_expiry ON payments.idempotency_keys (expires_at);

CREATE INDEX ledger_entries_account_at ON payments.ledger_entries (account, at);
CREATE INDEX ledger_entries_ref ON payments.ledger_entries (ref_type, ref_id);
ALTER TABLE payments.ledger_entries
  ALTER COLUMN account SET NOT NULL,
  ALTER COLUMN debit_cents SET NOT NULL,
  ALTER COLUMN credit_cents SET NOT NULL,
  ALTER COLUMN at SET NOT NULL,
  ADD CONSTRAINT ledger_entries_one_side CHECK (debit_cents >= 0 AND credit_cents >= 0 AND (debit_cents = 0) <> (credit_cents = 0));

-- A posted entry is never changed or removed: corrections are new, reversing entries.
CREATE FUNCTION payments.ledger_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'payments.ledger_entries is append-only (% refused)', TG_OP USING ERRCODE = 'check_violation';
END $$;
CREATE TRIGGER ledger_entries_append_only BEFORE UPDATE OR DELETE ON payments.ledger_entries
  FOR EACH ROW EXECUTE FUNCTION payments.ledger_append_only();

-- Human case numbers: refunds "RF-2201", disputes "DS-1188" (one shared counter; the dev seed uses lower numbers).
CREATE SEQUENCE payments.case_numbers START WITH 3000;
