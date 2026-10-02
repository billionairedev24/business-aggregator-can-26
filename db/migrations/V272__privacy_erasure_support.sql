-- S-105: what the erasure pipeline needs from tables other modules own. Additive (constraints widened only).

-- The console's privacy role (privacy officer): opens the privacy request queue and acts on it.
ALTER TABLE identity.platform_roles DROP CONSTRAINT platform_roles_role_check;
ALTER TABLE identity.platform_roles ADD CONSTRAINT platform_roles_role_check
  CHECK (role IN ('staff', 'admin', 'trust_safety', 'dispatch', 'finance', 'support', 'support_lead', 'analyst',
                  'privacy'));

-- Sign-ins ended because the account was erased (northline-auth drops the auth session on its next request).
ALTER TABLE identity.sessions DROP CONSTRAINT IF EXISTS sessions_revoke_reason_check;
ALTER TABLE identity.sessions
  ADD CONSTRAINT sessions_revoke_reason_check
  CHECK (revoke_reason IN ('revoked', 'revoked_others', 'signed_out', 'refresh_token_reused', 'erased'));

-- When the account was closed for erasure (status = 'erased'); its personal fields are blanked by the pipeline.
ALTER TABLE identity.users ADD COLUMN erased_at timestamptz;
CREATE INDEX ix_users_erased ON identity.users (erased_at) WHERE status = 'erased';

-- A verified review never changes (V073) — except that a privacy request may blank its author's name and words
-- (erasure) or correct the name shown (correction). The transaction says so with
-- set_config('northline.privacy_change', 'on', true); everything else stays immutable, the rating included.
CREATE OR REPLACE FUNCTION trust.reviews_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  privacy boolean := coalesce(current_setting('northline.privacy_change', true), '') = 'on';
BEGIN
  IF (NEW.ref_type, NEW.ref_id, NEW.author_id, NEW.target_type, NEW.target_id, NEW.rating, NEW.tags, NEW.lang,
      NEW.job_label, NEW.created_at)
     IS DISTINCT FROM
     (OLD.ref_type, OLD.ref_id, OLD.author_id, OLD.target_type, OLD.target_id, OLD.rating, OLD.tags, OLD.lang,
      OLD.job_label, OLD.created_at) THEN
    RAISE EXCEPTION 'review % is verified and cannot be edited', OLD.id USING ERRCODE = 'check_violation';
  END IF;
  IF NOT privacy AND (NEW.text, NEW.author_name) IS DISTINCT FROM (OLD.text, OLD.author_name) THEN
    RAISE EXCEPTION 'review % is verified and cannot be edited', OLD.id USING ERRCODE = 'check_violation';
  END IF;
  IF privacy AND NEW.text IS DISTINCT FROM OLD.text AND NEW.text IS NOT NULL THEN
    RAISE EXCEPTION 'review % text can only be erased', OLD.id USING ERRCODE = 'check_violation';
  END IF;
  IF OLD.reply IS NOT NULL AND NEW.reply IS DISTINCT FROM OLD.reply THEN
    RAISE EXCEPTION 'review % already has a reply', OLD.id USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
