-- 2026-10-02 (S-116, Loi 96 readiness): a listing's name and description in a second language. Additive only.
--
-- A merchant writes a listing in one language (catalogue.offers / services / catalog_products keep it). Where the region
-- configuration asks for French listing text (region.regions.french_listings, V315), the Studio warns while the French
-- name or description is missing and — with `require` — refuses to submit or publish the listing without them. The
-- consumer web and app show this text when the page is read in that language. No FK: a listing is an offer or a
-- service (one id space, ULIDs); deleting the listing deletes its texts (ListingTextsJdbc).
CREATE TABLE catalogue.listing_texts (
  listing_id  text        NOT NULL,
  merchant_id text        NOT NULL,
  lang        text        NOT NULL CHECK (lang IN ('en', 'fr')),
  title       text        NOT NULL CHECK (char_length(btrim(title)) BETWEEN 1 AND 120),
  description text        CHECK (description IS NULL OR char_length(description) <= 4000),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  updated_by  text,
  PRIMARY KEY (listing_id, lang)
);
CREATE INDEX ix_listing_texts_merchant ON catalogue.listing_texts (merchant_id);
