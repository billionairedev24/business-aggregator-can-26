-- Mobile gaps part 2: customers post reviews (design 01 C11 / B9, design 06). Additive; the immutability trigger is
-- widened only for the author's short edit window. See docs/DECISIONS.md "Mobile gaps part 2".

-- edit_until: the author may change the stars, tags and words until then (24 h after posting, and only while the
-- business hasn't replied — the service enforces both; the trigger the time). screened: the words went through the
-- profanity / personal-information filter and something was masked (a trust & safety flag is open for it).
-- hidden_*: trust & safety hid the review (a report or a screening flag upheld): it leaves the public pages and the
-- business's rating, and is kept for the record (unhiding brings it back).
ALTER TABLE trust.reviews
  ADD COLUMN edit_until    timestamptz,
  ADD COLUMN edited_at     timestamptz,
  ADD COLUMN screened      boolean NOT NULL DEFAULT false,
  ADD COLUMN hidden_at     timestamptz,
  ADD COLUMN hidden_by     text,
  ADD COLUMN hidden_reason text,
  ADD COLUMN moderated_at  timestamptz;                  -- last hide / unhide (the search projection's change clock)

-- A shop order can have several shops: one review per (transaction, author, business), not per transaction.
-- Widening only — every existing row satisfies the new key.
ALTER TABLE trust.reviews DROP CONSTRAINT reviews_one_per_transaction_uq;
ALTER TABLE trust.reviews
  ADD CONSTRAINT reviews_one_per_transaction_uq UNIQUE (ref_id, author_id, target_type, target_id);

CREATE INDEX ix_reviews_author ON trust.reviews (author_id, created_at DESC);
CREATE INDEX ix_reviews_visible ON trust.reviews (target_type, target_id) WHERE hidden_at IS NULL;

-- V073 / V272 rules, plus: the content may change while now() < edit_until (and the reply is still empty). The
-- transaction, the business, the author, the dates and the window itself never change; a privacy change (S-105) still
-- only blanks the name and the words.
CREATE OR REPLACE FUNCTION trust.reviews_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  privacy boolean := coalesce(current_setting('northline.privacy_change', true), '') = 'on';
  editing boolean := OLD.edit_until IS NOT NULL AND now() < OLD.edit_until AND OLD.reply IS NULL
                     AND NEW.reply IS NULL;
BEGIN
  IF (NEW.ref_type, NEW.ref_id, NEW.author_id, NEW.target_type, NEW.target_id, NEW.job_label, NEW.created_at,
      NEW.edit_until)
     IS DISTINCT FROM
     (OLD.ref_type, OLD.ref_id, OLD.author_id, OLD.target_type, OLD.target_id, OLD.job_label, OLD.created_at,
      OLD.edit_until) THEN
    RAISE EXCEPTION 'review % is verified and cannot be edited', OLD.id USING ERRCODE = 'check_violation';
  END IF;
  IF NOT editing AND (NEW.rating, NEW.tags, NEW.lang) IS DISTINCT FROM (OLD.rating, OLD.tags, OLD.lang) THEN
    RAISE EXCEPTION 'review % is verified and cannot be edited', OLD.id USING ERRCODE = 'check_violation';
  END IF;
  IF NOT privacy AND NOT editing AND (NEW.text, NEW.author_name) IS DISTINCT FROM (OLD.text, OLD.author_name) THEN
    RAISE EXCEPTION 'review % is verified and cannot be edited', OLD.id USING ERRCODE = 'check_violation';
  END IF;
  IF privacy AND NOT editing AND NEW.text IS DISTINCT FROM OLD.text AND NEW.text IS NOT NULL THEN
    RAISE EXCEPTION 'review % text can only be erased', OLD.id USING ERRCODE = 'check_violation';
  END IF;
  IF OLD.reply IS NOT NULL AND NEW.reply IS DISTINCT FROM OLD.reply THEN
    RAISE EXCEPTION 'review % already has a reply', OLD.id USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;

-- Points redeemed at checkout and given back (abandoned checkout, refund): one row per reference, so a retried
-- listener never moves points twice.
CREATE UNIQUE INDEX ux_points_ledger_ref ON trust.points_ledger (user_id, ref_type, ref_id)
  WHERE ref_type IN ('redemption', 'redemption_return', 'refund_return');
