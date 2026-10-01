-- S-96 platform console on-call (console batch 2 range V230–V239, docs/IMPLEMENTATION_PLAN.md). Additive only.

-- The staff on-call rota (design 03 `oncall`): who is on call when, for what. Admins plan shifts; the person on a shift
-- can hand it to a colleague ("Swap a shift"). Every change is audit-logged (console.oncall_*).
CREATE TABLE identity.oncall_shifts (
  id         text        PRIMARY KEY,
  user_id    text        NOT NULL REFERENCES identity.users(id),
  starts_at  timestamptz NOT NULL,
  ends_at    timestamptz NOT NULL,
  -- what the person answers for, in the planner's words ("Dispatch · courier incidents")
  duty       text        NOT NULL CHECK (char_length(duty) BETWEEN 1 AND 120),
  created_by text        NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (ends_at > starts_at AND ends_at <= starts_at + interval '7 days')
);
CREATE INDEX ix_oncall_shifts_time ON identity.oncall_shifts(ends_at, starts_at);
