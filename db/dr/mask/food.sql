-- food: menus are public. POS connections hold prod credentials (sealed with prod's key): they become "reconnect".
update food.pos_connections
   set credentials_enc = null, credentials_key = null, token_ref = null,
       account_label = case when account_label is null then null else 'Masked POS' end,
       account_id = case when account_id is null then null else 'masked-' || id end,
       status = case when status = 'connected' then 'reconnect' else status end;
delete from food.pos_oauth_requests;
