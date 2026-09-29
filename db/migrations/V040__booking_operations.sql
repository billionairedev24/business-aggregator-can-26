-- Operations workstream (range V040–V049): booking — jobs, job flow, quotes, mid-job approvals.
-- Additive only. See docs/DECISIONS.md "Operations".

-- Jobs: display snapshot taken when the booking is created (the service name and the address can change later),
-- a human reference (BK-7712) and optimistic locking for the job-flow transitions.
ALTER TABLE booking.bookings
  ADD COLUMN ref text,
  ADD COLUMN title text,
  ADD COLUMN address_line text,
  ADD COLUMN area text,
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN version bigint NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX ux_bookings_ref ON booking.bookings(ref) WHERE ref IS NOT NULL;
CREATE INDEX ix_bookings_merchant_starts ON booking.bookings(merchant_id, starts_at);
CREATE INDEX ix_bookings_customer ON booking.bookings(customer_id);
CREATE INDEX ix_booking_events_booking_at ON booking.booking_events(booking_id, at);
CREATE SEQUENCE booking.booking_ref_seq START 7800;

-- Quote requests: when the customer asked (list order), the number behind the quote reference (QT-3104, shown in
-- the composer before anything is sent and kept by every version) and per-merchant declines.
CREATE SEQUENCE booking.quote_ref_seq START 3200;
ALTER TABLE booking.quote_requests
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN number bigint NOT NULL DEFAULT nextval('booking.quote_ref_seq');
CREATE UNIQUE INDEX ux_quote_requests_number ON booking.quote_requests(number);
CREATE INDEX ix_quote_requests_merchants ON booking.quote_requests USING gin(merchant_ids);
CREATE TABLE booking.quote_request_declines (
  request_id text NOT NULL REFERENCES booking.quote_requests(id),
  merchant_id text NOT NULL,
  declined_at timestamptz NOT NULL,
  declined_by text NOT NULL,
  PRIMARY KEY (request_id, merchant_id)
);

-- Quotes: audit columns, the computed deposit (QuoteAccepted carries it) and one row per (request, merchant, version).
ALTER TABLE booking.quotes
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN created_by text,
  ADD COLUMN sent_at timestamptz,
  ADD COLUMN deposit_cents bigint NOT NULL DEFAULT 0,
  ADD COLUMN tax_bps integer NOT NULL DEFAULT 500 CHECK (tax_bps BETWEEN 0 AND 10000),
  ADD CONSTRAINT chk_quote_version CHECK (version >= 1),
  ADD CONSTRAINT chk_quote_duration CHECK (duration_min IS NULL OR duration_min > 0),
  ADD CONSTRAINT chk_quote_deposit CHECK (
    (deposit_kind = 'pct' AND deposit_bps BETWEEN 1 AND 10000) OR (deposit_kind <> 'pct' AND deposit_bps IS NULL)),
  ADD CONSTRAINT chk_quote_deposit_cents CHECK (deposit_cents BETWEEN 0 AND total_cents);
CREATE UNIQUE INDEX ux_quotes_request_merchant_version ON booking.quotes(request_id, merchant_id, version);

-- "Quote is immutable once sent" (validation-rules.md § Quote). Only draft quotes change content; afterwards only the
-- lifecycle columns (state, viewed_at) may move, and lines can no longer be added, changed or removed. Together with
-- trg_quote_subtotal (V016) this makes the sent total tamper-proof.
CREATE OR REPLACE FUNCTION booking.quote_immutable_check() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.state <> 'draft' AND (
       NEW.request_id IS DISTINCT FROM OLD.request_id OR NEW.merchant_id IS DISTINCT FROM OLD.merchant_id
    OR NEW.ref IS DISTINCT FROM OLD.ref OR NEW.version IS DISTINCT FROM OLD.version
    OR NEW.scope IS DISTINCT FROM OLD.scope OR NEW.exclusions IS DISTINCT FROM OLD.exclusions
    OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at OR NEW.duration_min IS DISTINCT FROM OLD.duration_min
    OR NEW.warranty IS DISTINCT FROM OLD.warranty OR NEW.deposit_kind IS DISTINCT FROM OLD.deposit_kind
    OR NEW.deposit_bps IS DISTINCT FROM OLD.deposit_bps OR NEW.deposit_cents IS DISTINCT FROM OLD.deposit_cents
    OR NEW.tax_bps IS DISTINCT FROM OLD.tax_bps
    OR NEW.subtotal_cents IS DISTINCT FROM OLD.subtotal_cents OR NEW.tax_cents IS DISTINCT FROM OLD.tax_cents
    OR NEW.total_cents IS DISTINCT FROM OLD.total_cents OR NEW.attachments IS DISTINCT FROM OLD.attachments
    OR NEW.valid_hours IS DISTINCT FROM OLD.valid_hours OR NEW.valid_until IS DISTINCT FROM OLD.valid_until
    OR NEW.sent_at IS DISTINCT FROM OLD.sent_at) THEN
    RAISE EXCEPTION 'quote % is % and can no longer be changed', OLD.id, OLD.state USING ERRCODE = 'check_violation';
  END IF;
  IF OLD.state IN ('accepted', 'declined', 'expired', 'superseded') AND NEW.state IS DISTINCT FROM OLD.state THEN
    RAISE EXCEPTION 'quote % is final (%)', OLD.id, OLD.state USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_quote_immutable BEFORE UPDATE ON booking.quotes
  FOR EACH ROW EXECUTE FUNCTION booking.quote_immutable_check();

CREATE OR REPLACE FUNCTION booking.quote_lines_immutable_check() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE st text;
BEGIN
  SELECT state INTO st FROM booking.quotes WHERE id = coalesce(NEW.quote_id, OLD.quote_id);
  IF st IS DISTINCT FROM 'draft' THEN
    RAISE EXCEPTION 'lines of quote % (%) can no longer be changed', coalesce(NEW.quote_id, OLD.quote_id), st
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN coalesce(NEW, OLD);
END $$;
CREATE TRIGGER trg_quote_lines_immutable BEFORE INSERT OR UPDATE OR DELETE ON booking.quote_lines
  FOR EACH ROW EXECUTE FUNCTION booking.quote_lines_immutable_check();

-- Mid-job approvals: explicit states + who asked when.
ALTER TABLE booking.approvals
  ADD COLUMN requested_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN requested_by text,
  ADD CONSTRAINT chk_approval_state CHECK (state IN ('pending', 'approved', 'declined')),
  ADD CONSTRAINT chk_approval_amount CHECK (amount_cents > 0),
  ADD CONSTRAINT chk_approval_desc CHECK (char_length(description) BETWEEN 1 AND 160);
CREATE INDEX ix_approvals_booking ON booking.approvals(booking_id);

-- Files attached to quotes and completion photos (bytes live in object storage behind the MediaStore port).
CREATE TABLE booking.media (
  id text PRIMARY KEY,
  merchant_id text NOT NULL,
  file_name text NOT NULL,
  content_type text NOT NULL,
  size_bytes bigint NOT NULL CHECK (size_bytes > 0),
  storage_key text NOT NULL,
  created_by text NOT NULL,
  created_at timestamptz NOT NULL
);
CREATE INDEX ix_booking_media_merchant ON booking.media(merchant_id);
