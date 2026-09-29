-- 2026-09-29 (messaging, help & reviews workstream): Studio Reviews (design/02 › reviews) and the quality score.
-- Additive only. Reviews are verified (one per completed booking / delivered order and author) and immutable: the
-- business may add one public reply and report a review, nothing else. See docs/DECISIONS.md.

ALTER TABLE trust.reviews
  ADD COLUMN author_name   text,             -- display snapshot: "Dana K."
  ADD COLUMN job_label     text,             -- "alternator", "oil & filter" (service or item of the transaction)
  ADD COLUMN created_at    timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN reply_at      timestamptz,
  ADD COLUMN reply_by      text,
  ADD COLUMN reported_at   timestamptz,
  ADD COLUMN report_reason text CHECK (report_reason IN ('fake', 'offensive', 'personal_info', 'wrong_business', 'other')),
  ADD COLUMN report_note   text,
  ADD COLUMN reported_by   text;

ALTER TABLE trust.reviews
  ADD CONSTRAINT reviews_rating_chk CHECK (rating BETWEEN 1 AND 5),
  ADD CONSTRAINT reviews_verified_chk CHECK (ref_type IN ('booking', 'order') AND ref_id IS NOT NULL AND author_id IS NOT NULL),
  ADD CONSTRAINT reviews_one_per_transaction_uq UNIQUE (ref_id, author_id, target_type);
CREATE INDEX reviews_target_idx ON trust.reviews (target_type, target_id, created_at DESC);

-- A verified review never changes after it is written; the reply is written once.
CREATE FUNCTION trust.reviews_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (NEW.ref_type, NEW.ref_id, NEW.author_id, NEW.target_type, NEW.target_id, NEW.rating, NEW.tags, NEW.text, NEW.lang,
      NEW.author_name, NEW.job_label, NEW.created_at)
     IS DISTINCT FROM
     (OLD.ref_type, OLD.ref_id, OLD.author_id, OLD.target_type, OLD.target_id, OLD.rating, OLD.tags, OLD.text, OLD.lang,
      OLD.author_name, OLD.job_label, OLD.created_at) THEN
    RAISE EXCEPTION 'review % is verified and cannot be edited', OLD.id USING ERRCODE = 'check_violation';
  END IF;
  IF OLD.reply IS NOT NULL AND NEW.reply IS DISTINCT FROM OLD.reply THEN
    RAISE EXCEPTION 'review % already has a reply', OLD.id USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER reviews_immutable BEFORE UPDATE ON trust.reviews FOR EACH ROW EXECUTE FUNCTION trust.reviews_immutable();

-- Trust & safety flags raised by reports and detectors.
ALTER TABLE trust.flags
  ADD COLUMN merchant_id text,
  ADD COLUMN created_at  timestamptz NOT NULL DEFAULT now();
CREATE INDEX flags_state_idx ON trust.flags (state);
CREATE INDEX flags_target_idx ON trust.flags (target_type, target_id);
