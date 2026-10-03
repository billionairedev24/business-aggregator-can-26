-- S-121 UAT with pilot merchants and customers; feedback triage (range V325–V329, above main's V323 — S-120's
-- pilot cohort). Additive only. See docs/DECISIONS.md "S-121" and docs/uat/README.md.
CREATE SCHEMA IF NOT EXISTS uat;

-- Who takes part in UAT. Businesses are S-120's pilot cohort (merchants.pilot_businesses with a business: its
-- provider / seller / kitchen persona from the business type); this table holds the people S-120 has no notion of —
-- pilot customers, couriers and console staff. The label is the working name staff use ("Pilot customer 3"), not the
-- person's name.
CREATE TABLE uat.participants (
  id          text PRIMARY KEY,
  user_id     text NOT NULL,                         -- logical ref → identity.users
  persona     text NOT NULL CHECK (persona IN ('customer', 'courier', 'staff')),
  label       text NOT NULL CHECK (char_length(btrim(label)) BETWEEN 1 AND 80),
  active      boolean NOT NULL DEFAULT true,
  added_by    text NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_participants_user ON uat.participants (user_id, persona);

-- The UAT scripts in docs/uat/ (one per persona). The version is the script's, so a sign-off says what was run.
CREATE TABLE uat.scripts (
  code     text PRIMARY KEY,
  persona  text NOT NULL UNIQUE CHECK (persona IN ('provider', 'seller', 'kitchen', 'customer', 'courier', 'staff')),
  version  text NOT NULL,
  title_i18n jsonb NOT NULL,                         -- {"en": …, "fr": …}
  doc_path text NOT NULL,                            -- docs/uat/<code>.md (fr: docs/uat/fr/<code>.md)
  sort     int  NOT NULL
);
INSERT INTO uat.scripts (code, persona, version, title_i18n, doc_path, sort) VALUES
  ('merchant-provider', 'provider', '1.0', '{"en": "Merchant — service provider", "fr": "Commerçant — prestataire de services"}', 'docs/uat/merchant-provider.md', 1),
  ('merchant-seller',   'seller',   '1.0', '{"en": "Merchant — seller", "fr": "Commerçant — vendeur"}', 'docs/uat/merchant-seller.md', 2),
  ('merchant-kitchen',  'kitchen',  '1.0', '{"en": "Merchant — kitchen", "fr": "Commerçant — cuisine"}', 'docs/uat/merchant-kitchen.md', 3),
  ('customer',          'customer', '1.0', '{"en": "Customer", "fr": "Client"}', 'docs/uat/customer.md', 4),
  ('courier',           'courier',  '1.0', '{"en": "Courier", "fr": "Livreur"}', 'docs/uat/courier.md', 5),
  ('console-staff',     'staff',    '1.0', '{"en": "Console staff", "fr": "Personnel de la console"}', 'docs/uat/console-staff.md', 6);

CREATE SEQUENCE uat.feedback_number_seq START 1001;

-- Feedback a pilot participant sent from the product. The text was run through the log redaction (S-112) before it was
-- stored, and the route has no query string or fragment. Context (route, version, locale, platform) is what the app
-- reported; nothing else about the device or the person is kept.
CREATE TABLE uat.feedback (
  id               text PRIMARY KEY,
  number           bigint NOT NULL UNIQUE DEFAULT nextval('uat.feedback_number_seq'),  -- "UAT-1001"
  -- a uat.participants id (a person) or a merchants.pilot_businesses id (a pilot business, S-120); logical ref
  participant_id   text NOT NULL,
  persona          text NOT NULL CHECK (persona IN ('provider', 'seller', 'kitchen', 'customer', 'courier', 'staff')),
  user_id          text NOT NULL,                  -- who sent it (identity.users)
  merchant_id      text,                           -- the business they sent it for (Studio)
  app              text NOT NULL CHECK (app IN ('studio', 'consumer', 'console', 'mobile', 'courier')),
  category         text NOT NULL CHECK (category IN ('bug', 'confusing', 'idea', 'praise')),
  severity         text NOT NULL CHECK (severity IN ('blocker', 'major', 'minor', 'cosmetic')), -- as the user sees it
  body             text NOT NULL CHECK (char_length(btrim(body)) BETWEEN 1 AND 4000),
  route            text NOT NULL CHECK (char_length(route) BETWEEN 1 AND 500),
  app_version      text NOT NULL CHECK (char_length(app_version) BETWEEN 1 AND 40),
  locale           text NOT NULL CHECK (char_length(locale) BETWEEN 2 AND 16),
  platform         text NOT NULL CHECK (char_length(platform) BETWEEN 1 AND 200),             -- browser / OS
  screenshot_key   text,                           -- object key under uat/ (STORAGE_PROVIDER)
  screenshot_type  text CHECK (screenshot_type IN ('image/png', 'image/jpeg')),
  screenshot_bytes int,
  state            text NOT NULL DEFAULT 'new'
                   CHECK (state IN ('new', 'triaged', 'accepted', 'fixed', 'verified', 'closed', 'wont_fix', 'duplicate')),
  blocking         boolean,                        -- decided at triage; null until accepted
  owner_id         text,                           -- staff member who drives it (identity.users)
  tracker_url      text CHECK (tracker_url ~ '^https?://' AND char_length(tracker_url) <= 500),
  duplicate_of     text REFERENCES uat.feedback (id),
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  version          int NOT NULL DEFAULT 0,
  CONSTRAINT chk_feedback_screenshot CHECK ((screenshot_key IS NULL) = (screenshot_type IS NULL)),
  CONSTRAINT chk_feedback_duplicate CHECK ((state = 'duplicate') = (duplicate_of IS NOT NULL)),
  CONSTRAINT chk_feedback_blocking CHECK (state IN ('new', 'triaged', 'wont_fix', 'duplicate') OR blocking IS NOT NULL)
);
CREATE INDEX ix_feedback_state ON uat.feedback (state, created_at);
CREATE INDEX ix_feedback_user ON uat.feedback (user_id, created_at DESC);
CREATE INDEX ix_feedback_participant ON uat.feedback (participant_id);
CREATE INDEX ix_feedback_duplicate ON uat.feedback (duplicate_of) WHERE duplicate_of IS NOT NULL;

-- Screenshots uploaded before the feedback is sent (an unsent one is removed after a day).
CREATE TABLE uat.screenshots (
  id           text PRIMARY KEY,
  user_id      text NOT NULL,
  storage_key  text NOT NULL,
  content_type text NOT NULL CHECK (content_type IN ('image/png', 'image/jpeg')),
  byte_size    int  NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_screenshots_user ON uat.screenshots (user_id, created_at);

-- Every triage step: the go/no-go trend replays it, and the console shows it. Ids and codes; the note is staff's.
CREATE TABLE uat.feedback_history (
  id          text PRIMARY KEY,
  feedback_id text NOT NULL REFERENCES uat.feedback (id),
  from_state  text,
  to_state    text NOT NULL,
  blocking    boolean,
  actor_id    text NOT NULL,
  note        text CHECK (char_length(note) <= 1000),
  at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_feedback_history ON uat.feedback_history (feedback_id, at);
CREATE INDEX ix_feedback_history_at ON uat.feedback_history (at);

-- A participant's sign-off of a script, recorded by staff from the printed or shared form. The latest row per
-- participant and script counts; earlier ones stay as history.
CREATE TABLE uat.signoffs (
  id             text PRIMARY KEY,
  participant_id text NOT NULL,                      -- uat.participants id or merchants.pilot_businesses id
  script_code    text NOT NULL REFERENCES uat.scripts (code),
  script_version text NOT NULL,
  outcome        text NOT NULL CHECK (outcome IN ('signed_off', 'with_comments', 'blocked')),
  comments       text CHECK (char_length(comments) <= 2000),
  blocking_ids   text[] NOT NULL DEFAULT '{}',     -- uat.feedback ids the participant named as blocking
  recorded_by    text NOT NULL,
  recorded_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT chk_signoff_blocked CHECK (outcome <> 'blocked' OR cardinality(blocking_ids) > 0 OR comments IS NOT NULL)
);
CREATE INDEX ix_signoffs_participant ON uat.signoffs (participant_id, script_code, recorded_at DESC);
