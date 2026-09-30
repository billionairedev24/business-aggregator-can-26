-- Finance workstream (V060–V069) · S-24 Bank linking via Stripe Financial Connections. Additive only; see
-- docs/DECISIONS.md. (V064 is S-21's.)

-- A bank account linked through Financial Connections keeps only the institution's name and the last 4 digits (plus
-- Stripe's references); institution_number / transit_number stay empty for it (they remain for typed details).
ALTER TABLE payments.payout_accounts
  ADD COLUMN financial_connections_account text,                          -- fca_… the owner picked (instant only)
  ADD COLUMN disconnected_at timestamptz;                                 -- the bank connection ended at Stripe
CREATE INDEX payout_accounts_fc_account ON payments.payout_accounts (financial_connections_account)
  WHERE financial_connections_account IS NOT NULL;
