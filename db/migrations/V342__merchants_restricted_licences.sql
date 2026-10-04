-- Age-restricted purchases (owner decision 2026-10-04). Additive only.
--
-- A business lists products of an age-restricted class (V341) only with a licence for that class in its province: a
-- liquor licence or a tobacco/vape retail permit — its number, the document (merchants.documents, purpose
-- 'verification', stored through the storage port) and its expiry. Trust & safety approve or reject it in the
-- console's vetting queue; every decision is in developer.audit_log. An approved licence past its expiry becomes
-- 'expired' (nightly job) and the business's restricted listings are hidden until a renewal is approved.
CREATE TABLE merchants.restricted_licences (
  id              text PRIMARY KEY,
  merchant_id     text NOT NULL REFERENCES merchants.merchants (id),
  age_class       text NOT NULL CHECK (age_class IN ('alcohol', 'tobacco', 'cannabis')),
  province        text NOT NULL CHECK (province ~ '^[A-Z]{2}$'),   -- the business's province when submitted
  licence_number  text NOT NULL CHECK (char_length(btrim(licence_number)) BETWEEN 2 AND 40),
  document_id     text NOT NULL,                                    -- merchants.documents id
  expires_on      date NOT NULL,
  status          text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected', 'expired', 'replaced')),
  submitted_by    text NOT NULL,                                    -- identity.users id
  submitted_at    timestamptz NOT NULL,
  decided_by      text,                                             -- staff identity.users id
  decided_at      timestamptz,
  reject_reason   text CHECK (reject_reason IN ('unreadable', 'wrong_class', 'wrong_business', 'expired', 'not_valid', 'other')),
  note            text CHECK (char_length(note) <= 500),
  reminded_at     timestamptz,                                      -- the expiry reminder went out
  CONSTRAINT chk_restricted_licence_decided CHECK ((decided_at IS NULL) = (decided_by IS NULL)),
  CONSTRAINT chk_restricted_licence_reason CHECK (status <> 'rejected' OR reject_reason IS NOT NULL)
);
-- one licence in force (or waiting) per business and class: a renewal replaces the previous one when approved
CREATE INDEX ix_restricted_licences_merchant ON merchants.restricted_licences (merchant_id, age_class, submitted_at DESC);
CREATE INDEX ix_restricted_licences_queue ON merchants.restricted_licences (status, submitted_at) WHERE status = 'pending';
CREATE INDEX ix_restricted_licences_expiry ON merchants.restricted_licences (expires_on) WHERE status = 'approved';
