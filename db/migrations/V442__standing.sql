-- S-156: schema `standing` (module ca.northline.standing) — a business's and a customer's standing with Northline: the
-- trust consequences in force (S-93's rules applied, S-82's staff actions), the appeals against them and against dispute
-- decisions, and the coaching plans staff assign. Money stays in payments, the switches stay with the module that owns
-- them (merchants: status and search; catalogue: instant book; identity: the customer's booking restriction). Additive.
CREATE SCHEMA IF NOT EXISTS standing;

-- One row per consequence, active until it is lifted (by staff, an upheld appeal, or the rule itself when the business
-- recovers). Reasons are written once, in English, like a staff member's reason on the S-82 trail.
CREATE TABLE standing.consequences (
  id            text        PRIMARY KEY,
  subject_type  text        NOT NULL CHECK (subject_type IN ('business', 'customer')),
  merchant_id   text,
  customer_id   text,
  -- suspended / search_hidden: merchants' status and search switch · instant_book_off: catalogue's instant-book pause ·
  -- photo_delay: one escrow released later (no completion photo) · no_show_warning: a customer who didn't show
  kind          text        NOT NULL CHECK (kind IN ('suspended', 'search_hidden', 'instant_book_off', 'photo_delay',
                                                     'no_show_warning')),
  -- rating_floor | off_platform_payment | provider_no_shows | missing_photo | customer_no_shows | staff
  cause         text        NOT NULL,
  reason        text        NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 1000),
  -- the merchants oversight action, the escrow, …
  ref_id        text,
  -- codes and figures only (booking id, hours, counts)
  detail        jsonb       NOT NULL DEFAULT '{}'::jsonb,
  applied_by    text        NOT NULL,
  applied_role  text        NOT NULL,
  started_at    timestamptz NOT NULL,
  -- a photo delay: the escrow is released no earlier than this
  ends_at       timestamptz,
  state         text        NOT NULL CHECK (state IN ('active', 'lifted')),
  lifted_at     timestamptz,
  lifted_by     text,
  lifted_role   text,
  lift_reason   text        CHECK (char_length(lift_reason) <= 1000),
  CONSTRAINT chk_consequences_subject CHECK (
    (subject_type = 'business' AND merchant_id IS NOT NULL AND customer_id IS NULL)
    OR (subject_type = 'customer' AND customer_id IS NOT NULL))
);
-- one active consequence of a kind per business or customer; photo delays are one per escrow
CREATE UNIQUE INDEX ux_consequences_active_business ON standing.consequences (merchant_id, kind)
  WHERE state = 'active' AND subject_type = 'business' AND kind <> 'photo_delay';
CREATE UNIQUE INDEX ux_consequences_active_customer ON standing.consequences (customer_id, kind)
  WHERE state = 'active' AND subject_type = 'customer';
CREATE UNIQUE INDEX ux_consequences_photo_delay ON standing.consequences (ref_id) WHERE kind = 'photo_delay';
CREATE INDEX ix_consequences_merchant ON standing.consequences (merchant_id, started_at DESC) WHERE merchant_id IS NOT NULL;
CREATE INDEX ix_consequences_customer ON standing.consequences (customer_id, started_at DESC) WHERE customer_id IS NOT NULL;

-- A business appeals a dispute decision once, and each consequence once (UNIQUE subject). Staff take it (assign) and
-- decide with a reason: granted (reversed: the consequence is lifted; a dispute's seller is compensated by Northline —
-- the customer keeps the refund) or denied (the decision or consequence stands).
CREATE SEQUENCE standing.appeal_number_seq START 1001;
CREATE TABLE standing.appeals (
  id                 text        PRIMARY KEY,
  number             text        NOT NULL UNIQUE,                     -- "AP-1001"
  merchant_id        text        NOT NULL,
  subject_type       text        NOT NULL CHECK (subject_type IN ('dispute', 'consequence')),
  subject_id         text        NOT NULL,
  -- a dispute's decision (full_refund | partial) or the consequence's kind
  subject_kind       text        NOT NULL,
  -- who took the decision being appealed (a staff member, or `system` for the rules) — they can't decide the appeal
  original_actor     text,
  statement          text        NOT NULL CHECK (char_length(statement) BETWEEN 1 AND 2000),
  state              text        NOT NULL CHECK (state IN ('open', 'in_review', 'granted', 'denied')),
  filed_by           text        NOT NULL,
  filed_at           timestamptz NOT NULL,
  assignee_id        text,
  assigned_at        timestamptz,
  decision_reason    text        CHECK (char_length(decision_reason) <= 1000),
  decided_by         text,
  decided_role       text,
  decided_at         timestamptz,
  compensation_cents bigint      NOT NULL DEFAULT 0 CHECK (compensation_cents >= 0),
  UNIQUE (subject_type, subject_id)
);
CREATE INDEX ix_appeals_waiting ON standing.appeals (filed_at) WHERE state IN ('open', 'in_review');
CREATE INDEX ix_appeals_merchant ON standing.appeals (merchant_id, filed_at DESC);

-- Evidence the business attached (object storage, `standing/<merchant>/<id>.<ext>`); never public.
CREATE TABLE standing.appeal_files (
  id           text        PRIMARY KEY,
  appeal_id    text        NOT NULL REFERENCES standing.appeals(id),
  name         text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 200),
  content_type text        NOT NULL,
  size_bytes   bigint      NOT NULL CHECK (size_bytes > 0),
  storage_key  text        NOT NULL,
  created_at   timestamptz NOT NULL
);
CREATE INDEX ix_appeal_files_appeal ON standing.appeal_files (appeal_id);

-- A coaching plan staff assign from the console (design 03 "Coaching plan"; the rating floor's "coaching checklist"):
-- tasks the business ticks off in the Studio; staff close it as completed or cancel it. One active plan per business.
CREATE TABLE standing.coaching_plans (
  id           text        PRIMARY KEY,
  merchant_id  text        NOT NULL,
  title        text        NOT NULL CHECK (char_length(title) BETWEEN 1 AND 120),
  note         text        CHECK (char_length(note) <= 1000),
  due_on       date,
  state        text        NOT NULL CHECK (state IN ('active', 'completed', 'cancelled')),
  created_by   text        NOT NULL,
  created_role text        NOT NULL,
  created_at   timestamptz NOT NULL,
  closed_at    timestamptz,
  closed_by    text,
  close_reason text        CHECK (char_length(close_reason) <= 1000)
);
CREATE UNIQUE INDEX ux_coaching_active ON standing.coaching_plans (merchant_id) WHERE state = 'active';
CREATE INDEX ix_coaching_merchant ON standing.coaching_plans (merchant_id, created_at DESC);

CREATE TABLE standing.coaching_tasks (
  id       text        PRIMARY KEY,
  plan_id  text        NOT NULL REFERENCES standing.coaching_plans(id),
  sort     integer     NOT NULL,
  title    text        NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
  done_at  timestamptz,
  done_by  text,
  UNIQUE (plan_id, sort)
);
