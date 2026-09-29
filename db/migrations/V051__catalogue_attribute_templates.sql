-- Required attributes and variation themes per shop leaf category (product editor › "Required attributes · <leaf>",
-- bulk-upload templates, import validation). Leaves without a row have no required attributes and the default
-- themes Size / Colour / Size × Colour. Category ids are the stable slugs from CategorySeeder.
INSERT INTO catalogue.attribute_templates (category_id, attributes, variant_themes) VALUES
  ('shop.hardware-and-auto.auto-parts', '[
     {"key": "partType", "label": "Part type", "required": true, "options": ["Wiper blades", "Filters", "Brakes", "Batteries", "Lighting", "Fluids & chemicals"]},
     {"key": "length", "label": "Length", "required": true, "options": ["20 in", "22 in", "24 in", "26 in", "n/a"]},
     {"key": "position", "label": "Position", "required": true, "options": ["Front", "Rear", "Front & rear", "n/a"]}
   ]', '{length,length_position,colour,size_colour}'),
  ('shop.hardware-and-auto.tires', '[
     {"key": "rim", "label": "Rim diameter", "required": true, "options": ["15 in", "16 in", "17 in", "18 in", "19 in", "20 in"]},
     {"key": "season", "label": "Season", "required": true, "options": ["All-season", "Winter", "Summer"]}
   ]', '{size}'),
  ('shop.apparel.clothing', '[
     {"key": "department", "label": "Department", "required": true, "options": ["Women", "Men", "Unisex"]},
     {"key": "material", "label": "Material", "required": true, "options": ["Cotton", "Wool", "Polyester", "Blend", "Other"]}
   ]', '{size,colour,size_colour}'),
  ('shop.apparel.kids-clothing', '[
     {"key": "department", "label": "Department", "required": true, "options": ["Girls", "Boys", "Unisex", "Baby"]},
     {"key": "material", "label": "Material", "required": true, "options": ["Cotton", "Wool", "Polyester", "Blend", "Other"]}
   ]', '{size,colour,size_colour}'),
  ('shop.apparel.workwear', '[
     {"key": "department", "label": "Department", "required": true, "options": ["Women", "Men", "Unisex"]},
     {"key": "safetyRating", "label": "Safety rating", "required": true, "options": ["CSA Green Triangle", "CSA Grade 1", "Hi-vis Class 2", "None"]}
   ]', '{size,colour,size_colour}'),
  ('shop.apparel.footwear', '[
     {"key": "department", "label": "Department", "required": true, "options": ["Women", "Men", "Unisex", "Kids"]},
     {"key": "width", "label": "Width", "required": true, "options": ["Regular", "Wide"]}
   ]', '{size,colour,size_colour}'),
  ('shop.food-and-grocery.groceries', '[
     {"key": "volume", "label": "Volume", "required": true, "options": ["100 g", "250 g", "500 g", "1 kg", "500 ml", "1 L", "2 L", "each"]},
     {"key": "storage", "label": "Storage", "required": true, "options": ["Shelf", "Refrigerated", "Frozen"]}
   ]', '{size}'),
  ('shop.food-and-grocery.produce', '[
     {"key": "volume", "label": "Volume", "required": true, "options": ["each", "250 g", "500 g", "1 kg", "bunch"]},
     {"key": "origin", "label": "Grown in", "required": true, "options": ["Alberta", "Canada", "Imported"]}
   ]', '{size}'),
  ('shop.food-and-grocery.dairy-and-eggs', '[
     {"key": "volume", "label": "Volume", "required": true, "options": ["500 ml", "1 L", "2 L", "4 L", "dozen"]},
     {"key": "storage", "label": "Storage", "required": true, "options": ["Refrigerated", "Frozen"]}
   ]', '{size}'),
  ('shop.food-and-grocery.frozen', '[
     {"key": "volume", "label": "Volume", "required": true, "options": ["250 g", "500 g", "1 kg", "2 kg", "each"]}
   ]', '{size}'),
  ('shop.food-and-grocery.coffee-and-tea', '[
     {"key": "volume", "label": "Volume", "required": true, "options": ["250 g", "340 g", "500 g", "1 kg", "20 bags", "100 bags"]},
     {"key": "form", "label": "Form", "required": true, "options": ["Whole bean", "Ground", "Pods", "Loose leaf", "Bags"]}
   ]', '{size}'),
  ('shop.health-and-beauty.vitamins-and-supplements', '[
     {"key": "npn", "label": "NPN on label", "required": true, "options": ["Yes", "No"]},
     {"key": "volume", "label": "Count", "required": true, "options": ["30", "60", "90", "120", "180"]}
   ]', '{size}');
