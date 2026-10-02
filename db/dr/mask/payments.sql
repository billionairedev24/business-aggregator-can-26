-- payments: amounts, states and the ledger stay (they are what staging tests). Customer names, bank details (transit,
-- institution, last 4, holder), card last 4, dispute statements and evidence, staff notes and the raw Stripe payloads
-- go; idempotency records are deleted (transient). Stripe ids stay: they are live-mode ids staging's test keys cannot
-- reach.
update payments.escrows set customer_name = 'Customer ' || lower(right(coalesce(customer_id, id), 6)) where customer_name is not null;
update payments.refunds
   set customer_name = case when customer_name is null then null else 'Customer ' || lower(right(id, 6)) end,
       contest_reason = null;
update payments.disputes
   set customer_name = case when customer_name is null then null else 'Customer ' || lower(right(id, 6)) end,
       customer_statement = case when customer_statement is null then null else '[masked statement]' end,
       response = case when response is null then null else '[masked response]' end,
       subject = case when subject is null then null else 'Dispute ' || coalesce(case_number, lower(right(id, 6))) end,
       evidence = pg_temp.nl_scrub(evidence), decision_note = null;
update payments.agent_decisions set note = null, cosign_note = null;
update payments.payout_accounts
   set holder_name = 'Account holder', transit_number = case when transit_number is null then null else '00000' end,
       institution_number = case when institution_number is null then null else '000' end, last4 = '0000';
update payments.customer_cards set last4 = '4242';
update payments.stripe_events set payload = '{}'::jsonb, error = null;
update payments.reconciliation_days set resolved_note = null where resolved_note is not null;
delete from payments.idempotency_keys;
