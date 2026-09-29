-- 2026-09-29 (messaging, help & reviews workstream): Help & support (design/02 › help). Additive only.
-- Helpdesk case display columns, help centre topics + articles (en + fr), platform status. See docs/DECISIONS.md.

-- Cases are numbered HD-<number>. The design's next new case is HD-4480.
CREATE SEQUENCE messaging.ticket_number_seq START 4480;

ALTER TABLE messaging.tickets
  ADD COLUMN number          integer NOT NULL DEFAULT nextval('messaging.ticket_number_seq'),
  ADD COLUMN merchant_id     text,            -- the business the case is about (requester_type = 'merchant')
  ADD COLUMN opened_by       text,            -- identity.users.id of the team member who opened it
  ADD COLUMN subject         text,
  ADD COLUMN channel         text CHECK (channel IN ('chat', 'call', 'email')),
  ADD COLUMN urgent          boolean NOT NULL DEFAULT false,
  ADD COLUMN ref_label       text,            -- "Booking BK-7712 · A. Osei"
  ADD COLUMN context         jsonb,           -- account context attached on open (portal, tier, role, last 5 events)
  ADD COLUMN agent_name      text,            -- display snapshot of the assigned agent ("Dev K.")
  ADD COLUMN resolution_note text,            -- "bank holiday", "in your favour"
  ADD COLUMN resolved_at     timestamptz,
  ADD COLUMN created_at      timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN updated_at      timestamptz NOT NULL DEFAULT now();
ALTER SEQUENCE messaging.ticket_number_seq OWNED BY messaging.tickets.number;
ALTER TABLE messaging.tickets ADD CONSTRAINT tickets_number_uq UNIQUE (number);
ALTER TABLE messaging.tickets ADD CONSTRAINT tickets_priority_chk CHECK (priority IN ('normal', 'priority', 'urgent'));
CREATE INDEX tickets_state_sla_idx ON messaging.tickets (state, sla_due_at);
CREATE INDEX tickets_agent_idx ON messaging.tickets (agent_id);
CREATE INDEX tickets_merchant_idx ON messaging.tickets (merchant_id, created_at DESC);

-- Help centre: topics per portal and articles (title/body per locale, full-text searchable).
CREATE TABLE messaging.help_topics (
  key       text PRIMARY KEY,
  portals   text[] NOT NULL,                  -- provider | seller | both | kitchen
  name_i18n jsonb NOT NULL,
  case_topic text NOT NULL,                   -- the "Contact support" topic this area maps to
  position  smallint NOT NULL
);

CREATE TABLE messaging.help_articles (
  id            text PRIMARY KEY,
  slug          text NOT NULL UNIQUE,
  topic_keys    text[] NOT NULL,
  portals       text[] NOT NULL,
  section_i18n  jsonb NOT NULL,               -- short label shown as meta: "Escrow", "Verification"
  title_i18n    jsonb NOT NULL,
  body_i18n     jsonb NOT NULL,               -- plain text, paragraphs separated by a blank line
  read_min      smallint NOT NULL CHECK (read_min > 0),
  featured      smallint,                     -- position in the portal's suggested list; null = not suggested
  updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX help_articles_topics_idx ON messaging.help_articles USING gin (topic_keys);
CREATE INDEX help_articles_fts_en_idx ON messaging.help_articles
  USING gin (to_tsvector('english', (title_i18n ->> 'en') || ' ' || (body_i18n ->> 'en')));
CREATE INDEX help_articles_fts_fr_idx ON messaging.help_articles
  USING gin (to_tsvector('french', (title_i18n ->> 'fr') || ' ' || (body_i18n ->> 'fr')));

-- Platform status (Help › Platform status). Incidents are set by the console.
CREATE TABLE messaging.status_components (
  key        text PRIMARY KEY,
  name_i18n  jsonb NOT NULL,
  state      text NOT NULL DEFAULT 'operational' CHECK (state IN ('operational', 'degraded', 'outage')),
  note_i18n  jsonb,                           -- "carrier delays in AB"
  since      timestamptz,
  position   smallint NOT NULL
);

INSERT INTO messaging.status_components (key, position, name_i18n) VALUES
  ('ordering',      1, '{"en":"Ordering & checkout","fr":"Commandes et paiement"}'),
  ('payments',      2, '{"en":"Payments (Stripe)","fr":"Paiements (Stripe)"}'),
  ('payouts',       3, '{"en":"Payouts","fr":"Versements"}'),
  ('tracking',      4, '{"en":"Live tracking","fr":"Suivi en direct"}'),
  ('notifications', 5, '{"en":"Notifications (SMS)","fr":"Notifications (texto)"}'),
  ('api',           6, '{"en":"API & webhooks","fr":"API et webhooks"}');
