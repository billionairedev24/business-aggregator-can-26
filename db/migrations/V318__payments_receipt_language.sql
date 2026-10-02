-- 2026-10-02 (S-116, Loi 96 readiness): the language of the receipts Stripe sends a customer. Additive only.
-- Stripe writes its receipt e-mails in the first supported language of the Customer's `preferred_locales`. Checkout sets
-- it from the buyer's language and the merchant's place (a French-first place — region configuration, V315 — gives
-- fr-CA) and remembers what it last sent, so Stripe is only called when it changes. NULL = never set.
ALTER TABLE payments.stripe_customers
  ADD COLUMN receipt_locale text CHECK (receipt_locale IN ('en-CA', 'fr-CA'));
