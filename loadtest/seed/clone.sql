-- S-119 load-test data: clones the dev seed's personas many times (LOCAL DATABASES ONLY — loadtest/seed/seed.sh
-- refuses anything that isn't a local, dev-seeded database).
--
-- A "business unit" is the dev seed's owner Ravi Sandhu with his team and businesses (Prairie Wrench provider,
-- Prairie Wrench Parts seller, Pho Dau Bo kitchen); a "shops unit" design 06's eight shops (Glenmore Bakery, …); a
-- "customer unit" Amara Osei. Every row that belongs to a unit — found by following the unit's ids through every text column of every
-- module schema until nothing new turns up — is copied once per clone with:
--   * every id of the unit replaced by a deterministic, valid ULID (loadtest.id(old, n): '7' + 25 hex digits — a
--     ULID far in the future, so clones never collide with real ids and are easy to find and delete);
--   * e-mails, phones, slugs and other unique texts made unique (lt<n>.ravi.sandhu@example.com, slug-lt<n>, …);
--   * locations (PostGIS columns, lat/lng) moved up to ~8 km so the market isn't one point;
--   * the businesses' display names suffixed with the clone number.
-- Rows that also name someone outside the unit (Amara's orders at Pho Dau Bo, …) are left out, so clones start with
-- no orders: the load test creates them. Northline staff (identity.platform_roles) are shared, not "someone else". Triggers and foreign keys are off while copying (session_replication_role =
-- replica): the copies are consistent because the originals were.
--
-- Usage (seed.sh does this): psql -v ON_ERROR_STOP=1 -f clone.sql, then
--   select loadtest.clone_units('business' | 'shops' | 'customer', <from>, <to>);

create schema if not exists loadtest;

create or replace function loadtest.id(old text, n int) returns text
    language sql immutable parallel safe as
$$ select '7' || upper(substr(encode(sha256(convert_to(old || ':' || n, 'UTF8')), 'hex'), 1, 25)) $$;

-- Tables that never belong to a unit: the outbox and logs, Flyway, PostGIS, the auth server's own tables (dev auth
-- needs none; a staging load test signs real users in instead, see docs/runbooks/load-testing.md).
create or replace function loadtest.tables() returns table (tbl regclass)
    language sql stable as
$$
select c.oid::regclass
  from pg_class c join pg_namespace s on s.oid = c.relnamespace
 where c.relkind in ('r', 'p')
   and not c.relispartition
   and s.nspname not in ('pg_catalog', 'information_schema', 'public', 'topology', 'tiger', 'tiger_data',
                         'events', 'auth', 'loadtest', 'i18n', 'region')
   and c.relname not like '%archive%'
   -- a background job's cursor (one row per source, after_id = the last row it screened), not a unit's data: cloning
   -- it made "listing-lt1" sources its CHECK refuses (engineering follow-ups; the table came after S-119)
   and (s.nspname, c.relname) not in (('trust', 'ai_screening_marks'))
$$;

-- The text (and text[]) columns of a table, and whether each is unique on its own (a one-column unique index; in a
-- composite one a remapped id already keeps the copy unique).
create or replace function loadtest.text_columns(t regclass)
    returns table (col name, is_array boolean, is_unique boolean)
    language sql stable as
$$
select a.attname,
       a.atttypid in ('text[]'::regtype, 'varchar[]'::regtype),
       exists (select 1 from pg_index i where i.indrelid = t and i.indisunique and i.indnkeyatts = 1
                                          and i.indkey[0] = a.attnum)
  from pg_attribute a
 where a.attrelid = t and a.attnum > 0 and not a.attisdropped
   and (a.atttypid in ('text'::regtype, 'varchar'::regtype, 'text[]'::regtype, 'varchar[]'::regtype)
        or format_type(a.atttypid, null) = 'citext')
$$;

-- "Does this row name an id of the set?" for a table, as a SQL condition over alias r.
create or replace function loadtest.mentions(t regclass, ids text) returns text
    language plpgsql stable as
$$
declare
    parts text[] := '{}';
    c record;
begin
    for c in select * from loadtest.text_columns(t) loop
        if c.is_array then
            parts := parts || format('coalesce(r.%I::text[] && (select array_agg(id) from %s), false)', c.col, ids);
        else
            parts := parts || format('coalesce(r.%I::text in (select id from %s), false)', c.col, ids);
        end if;
    end loop;
    if cardinality(parts) = 0 then
        return 'false';
    end if;
    return '(' || array_to_string(parts, ' or ') || ')';
end
$$;

-- Collects a unit's ids into loadtest.unit_ids, and the ids of everyone else into loadtest.other_ids.
create or replace function loadtest.collect(kind text) returns int
    language plpgsql as
$$
declare
    t regclass;
    added int;
    total int := 0;
    has_id boolean;
begin
    drop table if exists loadtest.unit_ids;
    drop table if exists loadtest.other_ids;
    create table loadtest.unit_ids (id text primary key);
    create table loadtest.other_ids (id text primary key);
    if kind = 'business' then
        insert into loadtest.unit_ids
        select id from identity.users where id in ('01J9ZD3V00000000000000RAV1', '01J9ZD3V000000000000000JAS',
                                                   '01J9ZD3V00000000000000PR1Y')
        union select id from merchants.merchants
               where id in ('01J9ZD3V00000000000000PWM1', '01J9ZD3V00000000000000PWP1', '01J9ZD3V00000000000000PDB1');
    elsif kind = 'shops' then
        insert into loadtest.unit_ids select id from merchants.merchants where id like '01J9ZD3V000000000000SHPM%';
    elsif kind = 'customer' then
        insert into loadtest.unit_ids select id from identity.users where id = '01J9ZD3V0000000000000C0001';
    else
        raise exception 'unit kind must be business, shops or customer, not %', kind;
    end if;
    insert into loadtest.other_ids
    select id from identity.users where id not in (select id from loadtest.unit_ids) and id !~ '^7'
                                    and id not in (select user_id from identity.platform_roles) -- staff are shared
    union select id from merchants.merchants where id not in (select id from loadtest.unit_ids) and id !~ '^7'
    -- orders and bookings belong to their customer: a clone's kitchen tickets, order lines, escrows … of someone
    -- else's order would point at the original order
    union select id from orders.orders where id !~ '^7'
    union select id from booking.bookings where id !~ '^7'
    union select id from booking.quotes where id !~ '^7';
    loop
        added := 0;
        for t in select tbl from loadtest.tables() loop
            select exists (select 1 from pg_attribute where attrelid = t and attname = 'id' and not attisdropped
                                                       and atttypid in ('text'::regtype, 'varchar'::regtype))
              into has_id;
            continue when not has_id;
            execute format('insert into loadtest.unit_ids select r.id from %s r where %s and not %s on conflict do nothing',
                           t, loadtest.mentions(t, 'loadtest.unit_ids'), loadtest.mentions(t, 'loadtest.other_ids'));
            get diagnostics total = row_count;
            added := added + total;
        end loop;
        exit when added = 0;
    end loop;
    select count(*) into total from loadtest.unit_ids;
    return total;
end
$$;

-- A text that isn't one of the unit's ids, in clone n: e-mails and phones made unique; other unique texts (a one-column
-- unique index, or a slug/handle/domain) suffixed, or — when a CHECK constraint fixes their format (a business number's
-- nine digits) — their last five digits replaced by the clone number; the businesses' display names numbered.
create or replace function loadtest.other(tbl text, col text, v text, n int, uniq text) returns text
    language sql immutable as
$$
select case
           when v is null then null
           when col ~ '(^|_)e?mail$' then 'lt' || n || '.' || v
           when col ~ '(^|_)phone$' then '+1587' || lpad(((abs(hashtext(v)) % 1000) * 10000 + n % 10000)::text, 7, '0')
           when uniq = 'format' and v ~ '[0-9]{5}[^0-9]*$' then
               regexp_replace(v, '[0-9]{5}([^0-9]*)$', lpad((n % 100000)::text, 5, '0') || '\1')
           when uniq <> 'no' or col ~ '(^|_)(slug|handle|domain|host)$' then v || '-lt' || n
           when tbl = 'merchants.merchants' and col = 'display_name' then v || ' ' || n
           else v
       end
$$;

-- The value of one column in clone n.
create or replace function loadtest.expr(t regclass, col name, typ regtype, uniq text) returns text
    language plpgsql stable as
$$
declare
    typname text := format_type(typ, null);
    q text := format('r.%I', col);
begin
    if typname in ('text', 'character varying', 'citext') then
        return format($f$case when %1$s::text in (select id from loadtest.unit_ids) then loadtest.id(%1$s::text, n)
                              else loadtest.other(%2$L, %3$L, %1$s::text, n, %4$L) end$f$, q, t::text, col, uniq);
    elsif typname in ('text[]', 'character varying[]') then
        return format($f$case when %1$s is null then null else coalesce(
                             (select array_agg(case when e in (select id from loadtest.unit_ids) then loadtest.id(e, n) else e end
                                               order by o) from unnest(%1$s::text[]) with ordinality u(e, o)), '{}') end$f$, q);
    elsif typname in ('geography', 'geometry') then
        return format($f$st_translate(%1$s::geometry, ((abs(hashtext('x' || n)) %% 2000) - 1000) / 8000.0,
                                      ((abs(hashtext('y' || n)) %% 2000) - 1000) / 14000.0)::%2$s$f$, q, typname);
    elsif col in ('lng', 'longitude') and typname in ('double precision', 'numeric', 'real') then
        return format('%s + ((abs(hashtext(''x'' || n)) %% 2000) - 1000) / 8000.0', q);
    elsif col in ('lat', 'latitude') and typname in ('double precision', 'numeric', 'real') then
        return format('%s + ((abs(hashtext(''y'' || n)) %% 2000) - 1000) / 14000.0', q);
    end if;
    return q;
end
$$;

-- Copies the collected unit's rows once per clone number in [first, last]. Returns the rows written.
create or replace function loadtest.clone(kind text, first int, last int) returns bigint
    language plpgsql as
$$
declare
    t regclass;
    cols text;
    vals text;
    n_rows bigint;
    wanted bigint;
    total bigint := 0;
begin
    set local session_replication_role = replica;
    for t in select tbl from loadtest.tables() loop
        -- a customer is the account (profile, addresses, household, preferences), not their order history
        continue when kind = 'customer'
                  and (select n.nspname from pg_class c join pg_namespace n on n.oid = c.relnamespace where c.oid = t)
                      not in ('identity', 'account');
        select string_agg(format('%I', a.attname), ', ' order by a.attnum),
               string_agg(loadtest.expr(t, a.attname, a.atttypid,
                                        case when not exists (select 1 from pg_index i
                                                               where i.indrelid = t and i.indisunique
                                                                 and i.indnkeyatts = 1 and i.indkey[0] = a.attnum)
                                             then 'no'
                                             when exists (select 1 from pg_constraint k
                                                           where k.conrelid = t and k.contype = 'c'
                                                             and a.attnum = any (k.conkey))
                                             then 'format'
                                             else 'free' end),
                          ', ' order by a.attnum)
          into cols, vals
          from pg_attribute a
         where a.attrelid = t and a.attnum > 0 and not a.attisdropped
           and a.attidentity = '' and a.attgenerated = ''
           and coalesce(pg_get_expr((select adbin from pg_attrdef d where d.adrelid = t and d.adnum = a.attnum), t), '')
               not like 'nextval(%';
        continue when cols is null;
        execute format('select count(*) * %s from %s r where %s and not %s', last - first + 1, t,
                       loadtest.mentions(t, 'loadtest.unit_ids'), loadtest.mentions(t, 'loadtest.other_ids'))
           into wanted;
        continue when wanted = 0;
        -- a copy that would break a unique key no id of the unit is part of (one review per customer and booking …)
        -- is left out, and said
        execute format('insert into %s (%s) select %s from %s r cross join generate_series(%s, %s) n where %s and not %s'
                       || ' on conflict do nothing',
                       t, cols, vals, t, first, last,
                       loadtest.mentions(t, 'loadtest.unit_ids'), loadtest.mentions(t, 'loadtest.other_ids'));
        get diagnostics n_rows = row_count;
        if n_rows < wanted then
            raise warning '%: % of % copies left out (unique key)', t, wanted - n_rows, wanted;
        end if;
        total := total + n_rows;
    end loop;
    return total;
end
$$;

create or replace function loadtest.clone_units(kind text, first int, last int) returns text
    language plpgsql as
$$
declare
    ids int;
    written bigint;
begin
    ids := loadtest.collect(kind);
    written := loadtest.clone(kind, first, last);
    return format('%s unit: %s ids, clones %s..%s, %s rows written', kind, ids, first, last, written);
end
$$;

-- Removes every clone (ids starting with 7) — `seed.sh --clean`.
create or replace function loadtest.clean() returns bigint
    language plpgsql as
$$
declare
    t regclass;
    n_rows bigint;
    total bigint := 0;
    parts text[];
    c record;
begin
    set local session_replication_role = replica;
    for t in select tbl from loadtest.tables() loop
        parts := '{}';
        for c in select * from loadtest.text_columns(t) where not is_array loop
            parts := parts || format('%I::text ~ ''^7[0-9A-F]{25}$''', c.col);
        end loop;
        continue when cardinality(parts) = 0;
        execute format('delete from %s where %s', t, array_to_string(parts, ' or '));
        get diagnostics n_rows = row_count;
        total := total + n_rows;
    end loop;
    return total;
end
$$;
