-- S-39: an approved listing whose price, category or images change goes back to pending for the automated checks.
-- revet_reasons says why (price | category | images); empty unless it is being re-vetted. A re-vetted listing keeps
-- the merchant's live / hidden choice when it is approved again, and an edit meanwhile doesn't withdraw it to draft.
ALTER TABLE catalogue.offers ADD COLUMN revet_reasons text[] NOT NULL DEFAULT '{}';
ALTER TABLE catalogue.services ADD COLUMN revet_reasons text[] NOT NULL DEFAULT '{}';
