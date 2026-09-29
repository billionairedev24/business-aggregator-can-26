-- schema: messaging · support · owner: Java (java)
-- 2026-09-29 (backend foundation): Postgres schema is `messaging` — the label "messaging · support" is not a valid identifier. See docs/DECISIONS.md.
-- Generated from the Northline spec (docs/DATA_MODEL.md). Hand-edit freely; keep column names.
CREATE SCHEMA IF NOT EXISTS messaging;

-- Conversation per booking/order/quote/ticket; retained for disputes.
CREATE TABLE messaging.threads (
  -- PK
  id text PRIMARY KEY,
  ref_type text,
  ref_id text,
  participant_ids text[],
  last_message_at timestamptz
);
-- TODO indexes/constraints: index(ref_type,ref_id)

-- Messages with attachments; phone numbers masked.
CREATE TABLE messaging.messages (
  -- PK
  id text PRIMARY KEY,
  -- FK
  thread_id text,
  sender_id text,
  body text,
  attachments text[],
  template_key text,
  at timestamptz,
  -- off-platform payment detector
  flagged boolean
);
-- TODO indexes/constraints: index(thread_id,at)
-- outbox events: message.sent · trust.flagged

-- Every push/SMS/email with locale and delivery result.
CREATE TABLE messaging.notifications (
  -- PK
  id text PRIMARY KEY,
  user_id text,
  channel text,
  template_key text,
  locale text,
  payload jsonb,
  sent_at timestamptz,
  delivered boolean,
  read_at timestamptz
);
-- TODO indexes/constraints: index(user_id,sent_at)

-- Per-user channel matrix and quiet hours.
CREATE TABLE messaging.notification_prefs (
  -- PK
  user_id text,
  matrix jsonb,
  quiet_from time,
  quiet_to time
);

-- Helpdesk cases from any party with SLA.
CREATE TABLE messaging.tickets (
  -- PK
  id text PRIMARY KEY,
  -- customer | merchant | courier
  requester_type text CHECK (requester_type IN ('customer', 'merchant', 'courier')),
  requester_id text,
  topic text,
  priority text,
  -- new | in_progress | waiting | resolved
  state text CHECK (state IN ('new', 'in_progress', 'waiting', 'resolved')),
  agent_id text,
  sla_due_at timestamptz,
  ref_type text,
  ref_id text,
  lang text,
  csat smallint
);
-- TODO indexes/constraints: index(state,sla_due_at) · index(agent_id)
-- outbox events: ticket.opened · ticket.resolved

-- Canned replies per locale.
CREATE TABLE messaging.macros (
  -- PK
  id text PRIMARY KEY,
  key text,
  body_i18n jsonb,
  topic text
);

-- Foreign keys (in-module only)
-- logical ref (cross-module, no FK): messaging.threads.ref_id → booking.bookings.id
-- logical ref (cross-module, no FK): messaging.messages.thread_id → messaging.threads.id
-- logical ref (cross-module, no FK): messaging.notifications.user_id → identity.users.id
-- logical ref (cross-module, no FK): messaging.notification_prefs.user_id → identity.users.id
-- logical ref (cross-module, no FK): messaging.tickets.agent_id → identity.users.id
