-- Region-neutral attribute templates (demo rehearsal 2026-10-19, DECISIONS 2026-09-30 "Region-neutral by design"):
-- produce's "Grown in" offered one province by name (V051). The province list now comes from the region model: the
-- option "@provinces" stands for every province and territory, by name, and the catalogue expands it when it reads the
-- template (CategoryCatalogAdapter). Values stored on listings stay plain names, so "Alberta" on an existing listing is
-- still one of the options. Only that one option list changes; the rest of the template is kept as it is.
UPDATE catalogue.attribute_templates t
   SET attributes = (
         SELECT jsonb_agg(
                  CASE WHEN a ->> 'key' = 'origin'
                       THEN jsonb_set(a, '{options}', '["@provinces", "Canada", "Imported"]'::jsonb)
                       ELSE a END
                  ORDER BY ord)
           FROM jsonb_array_elements(t.attributes) WITH ORDINALITY AS e(a, ord))
 WHERE t.category_id = 'shop.food-and-grocery.produce';
