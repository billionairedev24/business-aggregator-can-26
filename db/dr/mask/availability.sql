-- availability: calendar connections hold prod OAuth refresh tokens (sealed with prod's key) and the staff member's
-- account; they become "reconnect". Notification channels and pending OAuth requests go. Time-off reasons are personal.
update availability.calendar_links
   set refresh_token_enc = null, refresh_token_key = null, token_ref = null,
       account_label = case when account_label is null then null else pg_temp.nl_email('cal', id) end,
       external_account_id = case when external_account_id is null then null else 'masked-' || id end,
       state = 'reconnect';
delete from availability.calendar_channels;
delete from availability.calendar_oauth_requests;
update availability.calendar_sources set name = 'Calendar ' || lower(right(calendar_id, 4));
update availability.time_off set reason = null where reason is not null;
