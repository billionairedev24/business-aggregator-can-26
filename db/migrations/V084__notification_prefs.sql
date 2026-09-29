-- Settings & compliance workstream (range V080–V089). Settings › Notifications: the per-user channel matrix.
-- V013 documents user_id as the PK but never declared it; declared here (guarded, in case the messaging workstream
-- adds it too).
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
     WHERE conrelid = 'messaging.notification_prefs'::regclass AND contype = 'p'
  ) THEN
    ALTER TABLE messaging.notification_prefs ADD PRIMARY KEY (user_id);
  END IF;
END $$;

ALTER TABLE messaging.notification_prefs ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
