-- schema: messaging · owner: Java (worker)
-- 2026-10-02 — S-115 (runbooks: Kafka DLQ replay and the deferred notifications path). A deferred SMS / push / email
-- that the worker gives up on after its attempts used to be deleted, so an outage longer than ~50 minutes lost it for
-- good. It now stays as a dead row — the table's own dead-letter queue — until an operator requeues it
-- (DlqReplayCommand --deferred, docs/runbooks/events.md) or the nightly purge removes it (30 days by default).
-- Additive only: the job skips dead rows; nothing else reads these columns.

ALTER TABLE messaging.deferred_notifications
  ADD COLUMN IF NOT EXISTS dead_at    timestamptz,   -- given up at; null = still pending
  ADD COLUMN IF NOT EXISTS last_error text;          -- the failure's class only (provider messages can hold numbers)

CREATE INDEX IF NOT EXISTS deferred_notifications_dead_idx
  ON messaging.deferred_notifications (dead_at) WHERE dead_at IS NOT NULL;
