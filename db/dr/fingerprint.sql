-- S-114: a fingerprint of a Northline database, to prove a restore (scripts/dr/verify.sh). Read-only. One line per
-- fact, tab-separated: kind, name, value. Two databases that hold the same data print the same lines.
--
--   flyway    version / count / failed    the schema version (Flyway), how many migrations, failed ones
--   rows      <schema>.<table>            exact row count of every application table
--   checksum  <schema>.<table>            rows + an order-independent sum of row hashes of the key tables
--   asof      <schema>.<table>            the same, for append-only tables, over the rows written up to :as_of
--
-- `asof` lines are what a point-in-time restore is checked with: the restored copy and the live database agree on
-- every append-only row written before the restore time, whatever happened after it. Set with
-- `psql -v as_of="2026-10-02T13:00:00Z"`; default: everything.
\set QUIET on
\pset format unaligned
\pset fieldsep '\t'
\pset tuples_only on
\if :{?as_of}
\else
\set as_of infinity
\endif

select 'flyway', 'version', coalesce((select version from public.flyway_schema_history
                                       where success and version is not null
                                       order by installed_rank desc limit 1), 'none')
union all
select 'flyway', 'count', count(*)::text from public.flyway_schema_history where success
union all
select 'flyway', 'failed', count(*)::text from public.flyway_schema_history where not success;

-- Row counts of every application table (exact).
select 'rows', format('%s.%s', table_schema, table_name),
       (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name),
                                            false, true, '')))[1]::text
  from information_schema.tables
 where table_type = 'BASE TABLE'
   and table_schema not in ('pg_catalog', 'information_schema', 'public', 'tiger', 'tiger_data', 'topology')
   and table_schema not like 'pg\_%'
 order by 2;

-- Checksums: count + sum of the first 60 bits of md5(row as text). Order-independent and constant memory, so it runs
-- on prod-sized tables; any changed, missing or extra row changes it.
select 'checksum', t, (xpath('/row/c/text()', query_to_xml(
         format('select count(*) || '':'' || coalesce(sum((''x'' || left(md5(x::text), 15))::bit(60)::bigint::numeric), 0) as c from %s x', t),
         false, true, '')))[1]::text
  from unnest(array[
    'public.flyway_schema_history', 'identity.users', 'identity.addresses', 'merchants.merchants',
    'merchants.merchant_members', 'catalogue.categories', 'catalogue.offers', 'catalogue.services', 'food.menu_items',
    'booking.bookings', 'booking.quotes', 'orders.orders', 'orders.order_lines', 'payments.payment_intents',
    'payments.escrows', 'payments.ledger_entries', 'payments.refunds', 'payments.payouts', 'trust.reviews',
    'events.event_publication'
  ]) as t
 where to_regclass(t) is not null
 order by 2;

-- Append-only tables up to :as_of (point-in-time restores).
select 'asof', t || ' <= ' || :'as_of', (xpath('/row/c/text()', query_to_xml(
         format('select count(*) || '':'' || coalesce(sum((''x'' || left(md5(x::text), 15))::bit(60)::bigint::numeric), 0) as c from %s x where %I <= %L::timestamptz',
                t, col, :'as_of'),
         false, true, '')))[1]::text
  from (values ('payments.ledger_entries', 'at'), ('developer.audit_log', 'at'),
               ('public.flyway_schema_history', 'installed_on')) as a(t, col)
 where to_regclass(t) is not null
 order by 2;
