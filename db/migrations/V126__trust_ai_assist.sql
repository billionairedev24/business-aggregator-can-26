-- S-133 (AI workstream, V125–V129): AI trust & safety assist. Additive only. See docs/DECISIONS.md "S-133".
-- The model only suggests: screenings and anomaly scans raise open trust.flags for the console queue, staff decide.
-- Nothing here changes a listing, review, message or business automatically.

-- One row per screened item (listing submission, review, message). The item's text is NOT copied here: only the
-- verdict, the categories and, for flagged items, the model's explanation (which the flag's evidence also carries).
CREATE TABLE trust.ai_screenings (
  id          text PRIMARY KEY,
  target_type text NOT NULL CHECK (target_type IN ('listing', 'review', 'message')),
  target_id   text NOT NULL,
  merchant_id text,
  flagged     boolean NOT NULL,
  categories  text[] NOT NULL DEFAULT '{}',
  explanation text,
  model       text NOT NULL,
  prompt      text NOT NULL,
  screened_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ai_screenings_target_idx ON trust.ai_screenings (target_type, target_id);
CREATE INDEX ai_screenings_merchant_idx ON trust.ai_screenings (merchant_id, screened_at DESC);

-- How far the screening job has read each source: the last item's time and id (ties broken by id).
CREATE TABLE trust.ai_screening_marks (
  source     text PRIMARY KEY CHECK (source IN ('listing', 'review', 'message')),
  after_at   timestamptz NOT NULL,
  after_id   text NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- The weekly anomaly scan: one row per market and week (UTC, Monday start). market = the region market id, else the
-- province code, else 'unplaced'. summary is the model's market-level explanation (no names, no message text).
CREATE TABLE trust.anomaly_scans (
  id             text PRIMARY KEY,
  market         text NOT NULL,
  week_start     date NOT NULL,
  merchants      integer NOT NULL,
  flags_raised   integer NOT NULL,
  summary        text,
  model          text,
  prompt         text,
  ran_at         timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT anomaly_scans_market_week_uq UNIQUE (market, week_start)
);

-- Staff decide every flag in the console queue: who, when and why. state goes open → dismissed | actioned.
ALTER TABLE trust.flags
  ADD COLUMN decided_by    text,
  ADD COLUMN decided_at    timestamptz,
  ADD COLUMN decision_note text;
CREATE INDEX flags_state_created_idx ON trust.flags (state, created_at DESC);
