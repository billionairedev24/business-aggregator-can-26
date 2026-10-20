-- S-152: Plus member prices, only where the seller opts in. A shop's offer may give Northline Plus members a percentage
-- off its price (and every variant's), 1–50 %; NULL = no member price. The seller funds it (it sells for less: tax is on
-- the member price). Product cards and checkout show it; the cart prices a member's lines with it.
ALTER TABLE catalogue.offers
  ADD COLUMN member_discount_pct integer CHECK (member_discount_pct IS NULL OR member_discount_pct BETWEEN 1 AND 50);
