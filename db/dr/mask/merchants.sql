-- merchants: the storefront (display name, profile, menus) is public and stays. Owners' and principals' legal names,
-- work e-mail, the business and GST numbers (unique, valid format), registered office, identity-check details,
-- registry matches, invitations, document file names and staff notes are masked; custom domains are dropped so
-- staging never claims a merchant's real domain; locations move ~1 km (home-based businesses).
update merchants.merchants m
   set legal_name = 'Legal name ' || lower(right(m.id, 6)),
       work_email = case when m.work_email is null then null else pg_temp.nl_email('m', m.id) end,
       business_number = case when m.business_number is null then null else '9' || lpad(n.rn::text, 8, '0') end,
       gst_number = case when m.gst_number is null then null else '9' || lpad(n.rn::text, 8, '0') || 'RT0001' end,
       legal_details = pg_temp.nl_scrub(m.legal_details) - 'business_number' - 'legal_corporate_name'
  from (select id, row_number() over (order by id) as rn from merchants.merchants) n
 where n.id = m.id;
update merchants.merchant_principals set legal_name = 'Principal ' || lower(right(id, 6));
update merchants.member_invitations
   set email = case when email is null then null else pg_temp.nl_email('inv', id) end,
       phone = case when phone is null then null else '+1555' || lpad((abs(hashtext(id)) % 10000000)::text, 7, '0') end,
       token_hash = 'masked:' || id;
update merchants.owner_identity_checks
   set email = case when email is null then null else pg_temp.nl_email('idv', id) end,
       last_error = null, review_note = null;
update merchants.registry_checks
   set expected_name = case when expected_name is null then null else 'Masked name' end,
       record_name = case when record_name is null then null else 'Masked name' end,
       query_number = '000000000', review_note = null;
update merchants.application_decisions set note = null where note is not null;
update merchants.documents set file_name = 'document-' || lower(right(id, 6));
update merchants.verifications set reference = 'masked-' || lower(right(id, 6)) where reference is not null;
update merchants.storefronts
   set custom_domain = null, custom_domain_status = null, custom_domain_status_at = null, custom_domain_token = null,
       custom_domain_verified_at = null, custom_domain_checked_at = null, custom_domain_next_check_at = null,
       custom_domain_problem = null, custom_domain_dns_lost_at = null, custom_domain_live_at = null,
       custom_domain_requested_at = null, custom_domain_failures = 0
 where custom_domain is not null;
update merchants.locations set geom = pg_temp.nl_coarse(geom);
