-- S-114: proves a masked copy holds no prod personal data or credentials before it may be used as staging. Raises an
-- error (psql exits non-zero) listing every finding; prints "mask-check: ok" otherwise. Read-only.
--
-- 1. Invariants the mask/*.sql files promise, table by table.
-- 2. A scan of every text, varchar, citext, text[], json and jsonb column of the application schemas for e-mail
--    addresses (outside the allowed domains), North American phone numbers (outside the +1 555 masked range) and full
--    Canadian postal codes (masked ones end in 0A0) — a safety net for columns a later migration adds without a mask.
--
-- Settings (set by scripts/dr/mask.sh; defaults here):
--   northline.mask_allow_domains  e-mail domains that may remain (platform and documentation addresses)
--   northline.mask_check_skip     schema.table.column entries the scan skips: public business content
do $$
declare
  findings text[] := '{}';
  allow text[] := string_to_array(coalesce(nullif(current_setting('northline.mask_allow_domains', true), ''),
                    'example.invalid,example.com,example.org,example.net,northline.ca'), ',');
  skip text[] := string_to_array(coalesce(nullif(current_setting('northline.mask_check_skip', true), ''),
                    'merchants.merchants.profile,messaging.help_articles.body_i18n,messaging.macros.body_i18n,messaging.status_components.note_i18n,catalogue.offers.returns_policy,merchants.merchants.cancellation_policy,region.regions.registries,region.regions.privacy_law'), ',');
  -- capture groups: e-mail → 2 = domain; phone → 2 = area code; postal code → 1 = the code
  email_re constant text := '([A-Za-z0-9._%+-]+@((?:[A-Za-z0-9-]+\.)+[A-Za-z]{2,}))';
  phone_re constant text := '(?:^|[^0-9A-Za-z])((?:\+?1[ .-]?)?\(?([2-9][0-9]{2})\)?[ .-]?[2-9][0-9]{2}[ .-]?[0-9]{4})(?:[^0-9A-Za-z]|$)';
  postal_re constant text := '(?:^|[^0-9A-Za-z])([ABCEGHJ-NPRSTVXY][0-9][ABCEGHJ-NPRSTV-Z] ?[0-9][ABCEGHJ-NPRSTV-Z][0-9])(?:[^0-9A-Za-z]|$)';
  n bigint;
  c record;
  inv record;
begin
  -- 1. invariants: (label, query returning the number of offending rows)
  for inv in select * from (values
    ('identity.users e-mail not masked', $q$select count(*) from identity.users where email is not null and email::text !~ '@example\.invalid$'$q$),
    ('identity.users phone not masked', $q$select count(*) from identity.users where phone is not null and phone !~ '^\+1555[0-9]{7}$'$q$),
    ('identity.users birthday kept', $q$select count(*) from identity.users where birthday_month is not null$q$),
    ('identity.addresses street not masked', $q$select count(*) from identity.addresses where street is not null and street !~ ' Masked Street$'$q$),
    ('identity.addresses postal code not masked', $q$select count(*) from identity.addresses where postal is not null and postal !~ ' 0A0$'$q$),
    ('identity.sessions left', $q$select count(*) from identity.sessions$q$),
    ('identity.passkeys left', $q$select count(*) from identity.passkeys$q$),
    ('auth tokens left', $q$select (select count(*) from auth.oauth2_authorization) + (select count(*) from auth.issued_refresh_tokens) + (select count(*) from auth.authorization_sessions)$q$),
    ('auth credentials left', $q$select (select count(*) from auth.totp_secrets) + (select count(*) from auth.user_credentials) + (select count(*) from auth.backup_codes) + (select count(*) from auth.federated_identities)$q$),
    ('auth client secrets left', $q$select count(*) from auth.oauth2_registered_client where client_secret is not null$q$),
    ('merchants work e-mail not masked', $q$select count(*) from merchants.merchants where work_email is not null and work_email !~ '@example\.invalid$'$q$),
    ('merchants custom domains left', $q$select count(*) from merchants.storefronts where custom_domain is not null$q$),
    ('merchants invitations not masked', $q$select count(*) from merchants.member_invitations where (email is not null and email::text !~ '@example\.invalid$') or token_hash !~ '^masked:'$q$),
    ('messaging push devices left', $q$select count(*) from messaging.push_devices$q$),
    ('booking access notes left', $q$select count(*) from booking.access_notes$q$),
    ('developer webhook endpoints live', $q$select count(*) from developer.webhook_endpoints where active or url !~ '^https://webhooks\.example\.invalid/'$q$),
    ('developer API keys usable', $q$select count(*) from developer.api_keys where revoked_at is null$q$),
    ('sealed third-party credentials left', $q$select (select count(*) from availability.calendar_links where refresh_token_enc is not null) + (select count(*) from catalogue.integrations where credentials_enc is not null) + (select count(*) from food.pos_connections where credentials_enc is not null) + (select count(*) from developer.webhook_endpoints where secret_enc is not null)$q$),
    ('payments bank details left', $q$select count(*) from payments.payout_accounts where last4 <> '0000' or holder_name <> 'Account holder'$q$),
    ('payments Stripe payloads left', $q$select count(*) from payments.stripe_events where payload <> '{}'::jsonb$q$),
    ('payments idempotency records left', $q$select count(*) from payments.idempotency_keys$q$),
    ('trust review text left', $q$select count(*) from trust.reviews where text is not null and text <> '[masked review]'$q$),
    ('messaging message bodies left', $q$select count(*) from messaging.messages where body is not null and body <> '[masked message]'$q$)
  ) as t(label, q) loop
    execute inv.q into n;
    if n > 0 then findings := findings || format('%s: %s row(s)', inv.label, n); end if;
  end loop;

  -- 2. scan
  for c in
    select col.table_schema as s, col.table_name as t, col.column_name as col
      from information_schema.columns col
      join information_schema.tables tab using (table_schema, table_name)
     where tab.table_type = 'BASE TABLE'
       and col.table_schema not in ('pg_catalog', 'information_schema', 'public', 'tiger', 'tiger_data', 'topology', 'i18n')
       and col.table_schema not like 'pg\_%'
       and (col.data_type in ('text', 'character varying', 'json', 'jsonb')
            or col.udt_name in ('citext', '_text', '_varchar'))
       and not (col.table_schema || '.' || col.table_name || '.' || col.column_name = any (skip))
     order by 1, 2, 3
  loop
    execute format(
      $q$select count(*) from %1$I.%2$I x
          where exists (select 1 from regexp_matches(x.%3$I::text, %4$L, 'g') m
                         where not exists (select 1 from unnest(%7$L::text[]) d
                                            where lower(m[2]) = d or lower(m[2]) like '%%.' || d))
             or exists (select 1 from regexp_matches(x.%3$I::text, %5$L, 'g') m where m[2] <> '555')
             or exists (select 1 from regexp_matches(x.%3$I::text, %6$L, 'g') m where m[1] !~ '0A0$')$q$,
      c.s, c.t, c.col, email_re, phone_re, postal_re, allow)
      into n;
    if n > 0 then findings := findings || format('%s.%s.%s: %s row(s) look personal (e-mail, phone or postal code)', c.s, c.t, c.col, n); end if;
  end loop;

  if cardinality(findings) > 0 then
    raise exception E'mask-check FAILED — prod personal data or credentials remain:\n  %', array_to_string(findings, E'\n  ')
      using errcode = 'check_violation';
  end if;
  raise notice 'mask-check: ok';
end $$;
