-- S-65: bundles, per-variant images (catalogue.variants.image_set, already in V005) and compliance documents.

-- A bundle is an offer whose items are other offers of the same business; its stock follows the items.
ALTER TABLE catalogue.offers ADD COLUMN listing_type text NOT NULL DEFAULT 'product'
  CHECK (listing_type IN ('product', 'bundle'));

CREATE TABLE catalogue.bundle_items (
  bundle_offer_id text NOT NULL REFERENCES catalogue.offers (id) ON DELETE CASCADE,
  position integer NOT NULL CHECK (position >= 0),
  -- one of the business's own product offers (never a bundle; the service checks both)
  offer_id text NOT NULL REFERENCES catalogue.offers (id),
  -- catalogue.variants.id (logical: variants are rewritten on save), required when the offer has variants
  variant_id text,
  qty integer NOT NULL CHECK (qty BETWEEN 1 AND 99),
  PRIMARY KEY (bundle_offer_id, position),
  CHECK (offer_id <> bundle_offer_id)
);
CREATE INDEX ix_bundle_items_offer ON catalogue.bundle_items (offer_id);

-- Spec sheets and invoices (authenticity) a seller attaches on the editor's Compliance tab, for vetting. Private:
-- the business and Northline staff only; never shown to customers.
CREATE TABLE catalogue.listing_documents (
  id text PRIMARY KEY,
  merchant_id text NOT NULL,
  offer_id text NOT NULL REFERENCES catalogue.offers (id) ON DELETE CASCADE,
  purpose text NOT NULL CHECK (purpose IN ('spec_sheet', 'invoice')),
  file_name text NOT NULL CHECK (char_length(file_name) BETWEEN 1 AND 200),
  content_type text NOT NULL CHECK (content_type IN ('application/pdf', 'image/png', 'image/jpeg')),
  byte_size integer NOT NULL CHECK (byte_size > 0),
  storage_key text NOT NULL,
  uploaded_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_listing_documents_offer ON catalogue.listing_documents (offer_id, created_at);

-- Whole bundles the items' stock allows: the smallest of (item stock / qty). Read wherever an offer's stock is shown
-- or sold (cart, shop pages, the Studio's listings, the search indexer).
CREATE FUNCTION catalogue.bundle_stock(p_bundle text) RETURNS integer
LANGUAGE sql STABLE AS $$
  SELECT coalesce(min(greatest(coalesce(v.stock, o.stock, 0), 0) / bi.qty), 0)::integer
    FROM catalogue.bundle_items bi
    JOIN catalogue.offers o ON o.id = bi.offer_id
    LEFT JOIN catalogue.variants v ON v.id = bi.variant_id
   WHERE bi.bundle_offer_id = p_bundle
$$;
