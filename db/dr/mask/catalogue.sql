-- catalogue: listings are the merchants' public content and stay. Store connections hold prod credentials (sealed with
-- prod's key) and the shop owner's account: they become "reconnect". Staff notes on vetting decisions can name people.
update catalogue.integrations
   set credentials_enc = null, credentials_key = null, token_ref = null,
       account_label = case when account_label is null then null else 'Masked store' end,
       external_account_id = case when external_account_id is null then null else 'masked-' || id end,
       connection_state = 'reconnect';
delete from catalogue.commerce_oauth_requests;
update catalogue.vetting_decisions set note = null where note is not null;
