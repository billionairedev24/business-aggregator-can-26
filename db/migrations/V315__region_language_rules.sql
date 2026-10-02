-- 2026-10-02 (S-116, Loi 96 French-first readiness): language rules of the region model. Additive only.
--
-- A place whose language law puts French first gets these toggles; code reads them through region.api.Regions
-- (LanguageRules) and never names the place (DECISIONS "Region-neutral by design", S-116):
--   french_first     default the interfaces to French for visitors, customers, merchants and couriers there, present
--                    contracts of adhesion (the Terms) in French first with English only on an express, recorded
--                    request, and send receipts and notifications in French unless the person chose English.
--                    On a market row NULL = the province's value; on a province row NULL = false.
--   french_listings  what a merchant there must provide in French before a listing goes live:
--                    off     = nothing,
--                    warn    = the Studio warns when the French name or description is missing,
--                    require = the warning, and submitting or publishing without them is refused (422 `french`).
--                    On a market row NULL = the province's value; on a province row NULL = off.
--
-- Initial values come from the region data itself, not from a list of places: a province whose first official
-- language in region.regions.languages (V117) is French is French-first and requires French listings. Operations
-- change them per province or market (and REGION_FRENCH_FIRST adds places by configuration, docs/runbooks/regions.md).

ALTER TABLE region.regions
  ADD COLUMN french_first boolean,
  ADD COLUMN french_listings text CHECK (french_listings IN ('off', 'warn', 'require'));

UPDATE region.regions
   SET french_first = coalesce(languages[1] = 'fr', false),
       french_listings = CASE WHEN coalesce(languages[1] = 'fr', false) THEN 'require' ELSE 'off' END
 WHERE kind = 'province';

COMMENT ON COLUMN region.regions.french_first IS 'S-116: French-first interfaces, Terms, receipts (market NULL = province)';
COMMENT ON COLUMN region.regions.french_listings IS 'S-116: off | warn | require French listing text (market NULL = province)';
