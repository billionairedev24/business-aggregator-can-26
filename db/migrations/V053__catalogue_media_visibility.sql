-- S-123: listing images are served across businesses (and publicly) only once vetted. "Vetted" is read from the
-- listings that use the image; these partial GIN indexes serve MediaAdapter.approved (`own_images @> array[id]`,
-- `image_set @> array[id]`).
CREATE INDEX ix_offers_approved_own_images ON catalogue.offers USING gin (own_images) WHERE vetting = 'approved';
CREATE INDEX ix_catalog_products_locked_image_set ON catalogue.catalog_products USING gin (image_set) WHERE locked;
