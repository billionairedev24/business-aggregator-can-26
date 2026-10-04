-- restricted (age-restricted purchases, 2026-10-04): an age check holds no document or date of birth, only "over N on a
-- date"; staging keeps it masked to "pending" so a masked copy never lets a staging customer skip the check, and the
-- provider's session ids (live-mode) are dropped. Handoff checks hold ids and codes only.
update restricted.age_verifications
   set state = 'pending', over_age = null, verified_on = null, method = null, session_id = null, last_error = null;
