#!/usr/bin/env bash
# S-119: load-test data for a LOCAL database — clones the dev seed's businesses, shops and customer (clone.sql), opens
# the cloned kitchens all day, and writes loadtest/.data/manifest.json (the ids the k6 scenarios use).
#
#   loadtest/seed/seed.sh            (re)seed: removes earlier clones first
#   loadtest/seed/seed.sh --clean    remove the clones only
#
# Sizes (defaults = the pilot launch assumptions of docs/perf/capacity.md, so a load run has the data it needs):
#   SEED_BUSINESSES=150   each: an owner, a technician, a bookkeeper; a provider, a seller and a kitchen (and their
#                         listings, menus, hours, finance history: about 12,000 rows)
#   SEED_SHOP_UNITS=10    each: design 06's eight shops (80 sellers with products)
#   SEED_CUSTOMERS=3000   copies of Amara Osei (profile, addresses, household, preferences)
# Connection: PGHOST (localhost), PGPORT (5432), PGDATABASE (northline), PGUSER / PGPASSWORD (northline).
#
# Refused unless the database is on this machine and holds the dev seed (Ravi Sandhu): staging and prod data are never
# cloned into — a staging load test uses the accounts described in docs/runbooks/load-testing.md § Staging.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
data="$here/../.data"
mkdir -p "$data"
export PGHOST="${PGHOST:-localhost}" PGPORT="${PGPORT:-5432}" PGDATABASE="${PGDATABASE:-northline}"
export PGUSER="${PGUSER:-northline}" PGPASSWORD="${PGPASSWORD:-northline}" PGOPTIONS="-c client_min_messages=warning"
psql=(psql -v ON_ERROR_STOP=1 -qAt)

case "$PGHOST" in
  localhost | 127.0.0.1 | ::1) ;;
  *) echo "seed.sh seeds local databases only (PGHOST=$PGHOST)" >&2; exit 1 ;;
esac
if [ "$("${psql[@]}" -c "select count(*) from identity.users where id = '01J9ZD3V00000000000000RAV1'")" != 1 ]; then
  echo "No dev seed in $PGDATABASE (Ravi Sandhu is missing): start the api with the local profile first" >&2
  exit 1
fi

"${psql[@]}" -c "drop schema if exists loadtest cascade"
"${psql[@]}" -f "$here/clone.sql"
echo "removed $("${psql[@]}" -c "select loadtest.clean()") rows of earlier clones"
if [ "${1:-}" = --clean ]; then
  "${psql[@]}" -c "drop schema loadtest cascade"
  exit 0
fi

businesses="${SEED_BUSINESSES:-150}" shops="${SEED_SHOP_UNITS:-10}" customers="${SEED_CUSTOMERS:-3000}"
clone() { # kind, count — in batches, so a big seed shows progress and no transaction gets huge
  local kind=$1 count=$2 from=1 to
  while [ "$from" -le "$count" ]; do
    to=$((from + 49 < count ? from + 49 : count))
    "${psql[@]}" -c "select loadtest.clone_units('$kind', $from, $to)"
    from=$((to + 1))
  done
}
clone business "$businesses"
clone shops "$shops"
clone customer "$customers"

# Cloned kitchens take orders around the clock (the load test runs at any hour), and nothing is paused or sold out.
"${psql[@]}" <<'SQL'
update food.opening_hours set ranges = '[["00:00", "23:59"]]' where merchant_id ~ '^7';
update food.menu_items set daily_limit = null, sold_today = 0, available = true, sold_out_on = null
 where merchant_id ~ '^7' and status = 'published';
analyze;
SQL

# The manifest: what the scenarios pick from. Kitchens with their dishes served whenever the kitchen is open that need
# no choice (no required modifier),
# providers with their instant-book services, sellers' live products, the business people, the customers.
"${psql[@]}" >"$data/manifest.json" <<'SQL'
select json_build_object(
  'generatedAt', now(),
  -- the market (city) and province the clones are in (the dev seed's; search's `market` is the province), the middle of their locations for searches "near me"
  'market', (select city from merchants.merchants where id ~ '^7' group by city order by count(*) desc limit 1),
  'province', (select coalesce(province, registry_jurisdiction) from merchants.merchants where id ~ '^7'
                group by 1 order by count(*) desc limit 1),
  'center', (select json_build_object('lat', round(avg(st_y(l.geom::geometry))::numeric, 5),
                                      'lng', round(avg(st_x(l.geom::geometry))::numeric, 5))
               from merchants.locations l where l.merchant_id ~ '^7'),
  'staff', (select coalesce(json_agg(distinct user_id), '[]') from identity.platform_roles),
  'kitchens', (select coalesce(json_agg(k), '[]') from (
      select m.id as "merchantId", mm.user_id as "ownerId",
             (select json_agg(i.id order by i.sort, i.id)
                from food.menu_items i join food.menu_sections ms on ms.id = i.section_id
                     join food.menus mn on mn.id = ms.menu_id
               where i.merchant_id = m.id and i.status = 'published' and i.available and i.availability = 'always'
                 and mn.status = 'live' and mn.schedule ->> 'mode' = 'open_hours'
                 and not exists (select 1 from food.item_modifiers im join food.modifier_groups g on g.id = im.group_id
                                  where im.item_id = i.id and (g.required or coalesce(g.min_select, 0) > 0))) as items
        from merchants.merchants m join merchants.merchant_members mm on mm.merchant_id = m.id and mm.role = 'owner'
       where m.id ~ '^7' and m.type = 'kitchen' and m.status = 'active' order by m.id) k),
  'providers', (select coalesce(json_agg(p), '[]') from (
      select m.id as "merchantId", mm.user_id as "ownerId", s.slug,
             (select json_agg(v.id order by v.id) from catalogue.services v
               where v.merchant_id = m.id and v.status = 'live' and v.vetting = 'approved' and v.instant_book
                 and v.pricing_mode = 'fixed') as services
        from merchants.merchants m join merchants.storefronts s on s.merchant_id = m.id
             join merchants.merchant_members mm on mm.merchant_id = m.id and mm.role = 'owner'
       where m.id ~ '^7' and m.type = 'provider' and m.status = 'active' and s.published_at is not null
       order by m.id) p),
  'sellers', (select coalesce(json_agg(s), '[]') from (
      select m.id as "merchantId",
             (select json_agg(o.id order by o.id) from catalogue.offers o
               where o.merchant_id = m.id and o.status = 'live' and o.vetting = 'approved' and coalesce(o.stock, 0) > 5
                 and coalesce(o.variant_theme, 'none') = 'none') as offers
        from merchants.merchants m
       where m.id ~ '^7' and m.type = 'seller' and m.status = 'active' order by m.id) s),
  'members', (select coalesce(json_agg(b), '[]') from (
      select mm.merchant_id as "merchantId", mm.user_id as "userId", mm.role, m.type
        from merchants.merchant_members mm join merchants.merchants m on m.id = mm.merchant_id
       where m.id ~ '^7' order by 1, 2) b),
  'customers', (select coalesce(json_agg(u.id order by u.id), '[]') from identity.users u
                 where u.id ~ '^7' and not exists (select 1 from merchants.merchant_members mm where mm.user_id = u.id))
)
SQL
"${psql[@]}" -c "drop schema loadtest cascade"
python3 - "$data/manifest.json" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))
print("manifest (%s, %s): %d kitchens, %d providers, %d sellers, %d business people, %d customers, %d staff" % (
    m["market"], m["province"], len(m["kitchens"]), len(m["providers"]), len(m["sellers"]), len(m["members"]), len(m["customers"]),
    len(m["staff"])))
PY
