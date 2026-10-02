-- identity: people. E-mail and phone become unique, undeliverable values (example.invalid, +1 555 numbers by row);
-- names, pronouns and birthdays go; addresses keep city, province and the postal code's first three characters (the
-- delivery zone) and move ~1 km; sessions and passkeys are deleted.
update identity.users u
   set email = case when u.email is null then null else pg_temp.nl_email('u', u.id) end,
       phone = case when u.phone is null then null else '+1555' || lpad(n.rn::text, 7, '0') end,
       display_name = case when u.display_name is null then null else 'User ' || lower(right(u.id, 6)) end,
       first_name = case when u.first_name is null then null else 'Test' end,
       last_name = case when u.last_name is null then null else 'User ' || lower(right(u.id, 6)) end,
       pronouns = null, birthday_month = null, birthday_day = null
  from (select id, row_number() over (order by id) as rn from identity.users) n
 where n.id = u.id;
update identity.addresses
   set street = case when street is null then null else (1 + abs(hashtext(id)) % 9000)::text || ' Masked Street' end,
       unit = null, place_id = null, access_note = null,
       postal = case when postal is null then null else left(upper(postal), 3) || ' 0A0' end,
       geom = pg_temp.nl_coarse(geom);
update identity.households set name = 'Household ' || lower(right(id, 6)),
       stripe_subscription_id = case when stripe_subscription_id is null then null else 'sub_masked_' || id end;
delete from identity.sessions;
delete from identity.passkeys;
