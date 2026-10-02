-- 2026-10-02 (S-116, Loi 96 readiness): the language the Terms of Service and Privacy Policy were accepted in.
-- Additive only. Where the region configuration makes a place French-first (region.regions.french_first, V315), the
-- registration form presents the Terms in French first; English is shown only when the person expressly asks for it,
-- and that request is recorded here with the acceptance (northline-auth, RegistrationService):
--   terms_language              en | fr — the language the Terms were shown in when accepted; NULL = not said
--                               (accounts created before S-116, or a client that doesn't send it)
--   terms_english_requested_at  when the person expressly asked for the English version; NULL = no such request
ALTER TABLE identity.users
  ADD COLUMN terms_language text CHECK (terms_language IN ('en', 'fr')),
  ADD COLUMN terms_english_requested_at timestamptz,
  ADD CONSTRAINT chk_users_terms_english CHECK (terms_english_requested_at IS NULL OR terms_language = 'en');
