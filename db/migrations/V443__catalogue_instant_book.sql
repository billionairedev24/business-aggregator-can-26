-- S-156: a business-level instant-book state (S-82 "no instant-book state exists to switch"). Each service keeps its own
-- `instant_book`; customers can book one instantly only when the business has instant book on (its Studio switch) and
-- Northline hasn't paused it (S-93 "3 no-shows in 30 days → instant book off", or staff). No row = on, not paused.
-- Search and checkout read the effective value. Additive only.
CREATE TABLE catalogue.instant_book_settings (
  merchant_id   text        PRIMARY KEY,
  enabled       boolean     NOT NULL DEFAULT true,
  paused_at     timestamptz,
  -- provider_no_shows | staff
  paused_cause  text,
  paused_reason text        CHECK (char_length(paused_reason) <= 1000),
  updated_by    text,
  updated_at    timestamptz NOT NULL,
  CONSTRAINT chk_instant_book_pause CHECK ((paused_at IS NULL) = (paused_cause IS NULL))
);
