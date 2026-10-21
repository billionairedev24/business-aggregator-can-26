-- S-156: S-93 "Customer no-show ×2 → reliability score drop; providers see it before accepting". The standing module
-- sets these when a customer reaches the rule's count of confirmed no-shows (12 months): providers see the count on
-- quote requests and jobs, and the customer can't book instantly (the provider accepts a request first). Cleared when
-- staff lift it. Additive only.
ALTER TABLE identity.users
  ADD COLUMN no_show_count         integer     NOT NULL DEFAULT 0 CHECK (no_show_count >= 0),
  ADD COLUMN no_show_restricted_at timestamptz;

-- The nightly count of confirmed no-shows (trust.flags rule no_show / customer_no_show, action confirm).
CREATE INDEX IF NOT EXISTS ix_flags_no_shows ON trust.flags (rule, decided_at)
  WHERE rule IN ('no_show', 'customer_no_show') AND action = 'confirm';
