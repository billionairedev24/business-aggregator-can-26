-- S-93 platform console trust & safety (console queues range V210–V219, docs/IMPLEMENTATION_PLAN.md). Additive only.

-- The trust & safety rules staff tune in the console (tier thresholds, rating floor, no-show limits, the off-platform
-- payment phrases and the listing vetting keywords). One row per edited rule; a rule without a row uses its default
-- (trust.domain.TrustRule). Changes are audit-logged (trust.rule_changed, before/after).
CREATE TABLE trust.rules (
  key         text        PRIMARY KEY,
  value       jsonb       NOT NULL,
  updated_by  text        NOT NULL,
  role        text        NOT NULL,
  updated_at  timestamptz NOT NULL
);

-- Staff actions on flags (warn | coach | confirm | suspend_listings | escalate) are kept in trust.flags.action (V012).
CREATE INDEX IF NOT EXISTS ix_flags_merchant_state ON trust.flags(merchant_id, state);
