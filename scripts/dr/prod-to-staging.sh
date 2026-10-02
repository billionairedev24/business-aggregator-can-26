#!/usr/bin/env bash
# S-114: refreshes staging from a restored prod copy, masking on the way (docs/runbooks/backups-dr.md § Prod snapshot
# to staging). Unmasked prod data never reaches staging: the masking and its check run on the restored copy inside
# prod's boundary, and only the masked dump crosses over.
#
#   1. restore prod into a temporary instance:  scripts/dr/restore.sh snapshot --cloud … --env prod --name northline-prod-pg-mask-<date>
#   2. scale staging's apps to zero (Argo CD / kubectl), then:
#      scripts/dr/prod-to-staging.sh --source-url postgresql://admin:…@<restored>:5432/northline?sslmode=require \
#                                    --target-url postgresql://admin:…@<staging>:5432/northline?sslmode=require \
#                                    [--owner-role northline_app] [--jobs 4] [--yes]
#   3. follow the printed steps (search reindex, Valkey flush, OAuth clients, scale up), delete the temporary instance.
#
# What it does: mask + mask-check on the source (fails → nothing touched in staging) → fingerprint → pg_dump → DROP
# and re-create the staging database → the source's extensions as the admin → pg_restore (objects owned by
# --owner-role) → fingerprint → compare (must be identical) → delete the dump. Prints the timing of each step.
set -euo pipefail
# shellcheck source=scripts/dr/lib.sh
. "$(dirname "$0")/lib.sh"

source_url='' target_url='' owner=${DR_OWNER_ROLE:-} jobs=4 yes=0
while [ $# -gt 0 ]; do
  case $1 in
    --source-url) source_url=$2; shift 2 ;;
    --target-url) target_url=$2; shift 2 ;;
    --owner-role) owner=$2; shift 2 ;;
    --jobs) jobs=$2; shift 2 ;;
    --yes) yes=1; shift ;;
    -h | --help) sed -n '2,17p' "$0"; exit 0 ;;
    *) dr_die "unknown argument $1 (see --help)" ;;
  esac
done
[ -n "$source_url" ] && [ -n "$target_url" ] || dr_die "--source-url and --target-url are required"
[ "$(dr_url_server "$source_url")" != "$(dr_url_server "$target_url")" ] || dr_die "source and target are the same database"

db=$(dr_url_server "$target_url" | sed -E 's#.*/##')
admin_url=$(dr_url_db "$target_url" postgres)
dr_require_idle "$source_url" "the source"
others=$(dr_psql "$admin_url" -Atc "select count(*) from pg_stat_activity where datname = '$db' and pid <> pg_backend_pid()")
[ "$others" = 0 ] || dr_die "staging database '$db' has $others session(s): scale staging's apps to zero first"
if [ "$yes" = 0 ]; then
  printf 'This DROPS database "%s" on %s and replaces it with a masked copy of %s.\nType the database name to go on: ' \
    "$db" "$(dr_url_server "$target_url")" "$(dr_url_server "$source_url")" >&2
  read -r answer
  [ "$answer" = "$db" ] || dr_die "aborted"
fi

mkdir -p "$DR_WORK"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
dump=$DR_WORK/masked-$stamp.dump
record=$DR_WORK/prod-to-staging-$stamp.tsv
trap 'rm -f "$dump" "$dump.list"' EXIT
step() { # name start
  local s
  s=$(dr_secs "$2" "$(dr_now_ms)")
  printf '%s\t%s\n' "$1" "$s" >>"$record"
  dr_log "$1: ${s} s"
}
t_all=$(dr_now_ms)

t=$(dr_now_ms)
"$(dirname "$0")/mask.sh" --url "$source_url" ${owner:+--owner-role "$owner"}
step "mask + mask-check (source)" "$t"

t=$(dr_now_ms)
dr_psql "$source_url" -f "$DR_SQL/fingerprint.sql" >"$DR_WORK/source-$stamp.tsv"
step "fingerprint (source)" "$t"

t=$(dr_now_ms)
dr_pg pg_dump -d "$source_url" -Fc --no-owner --no-privileges -f "$dump"
step "pg_dump ($(du -h "$dump" | cut -f1))" "$t"

t=$(dr_now_ms)
extensions=$(dr_psql "$source_url" -Atc "select string_agg(format('create extension if not exists %I cascade', extname), '; ') from pg_extension where extname <> 'plpgsql'")
dr_psql "$admin_url" -c "drop database if exists \"$db\" with (force)" \
  -c "create database \"$db\"${owner:+ owner \"$owner\"}"
dr_psql "$target_url" -c "$extensions"
step "re-create the staging database" "$t"

t=$(dr_now_ms)
# Extensions were created by the admin above (PostGIS is not a trusted extension): leave them, and the schemas they
# created (tiger, topology), out of the restore, which runs as the owner role.
existing=$(dr_psql "$target_url" -Atc "select string_agg(nspname, '|') from pg_namespace where nspname not like 'pg\_%' and nspname <> 'information_schema'")
dr_pg pg_restore -l "$dump" | grep -vE " EXTENSION - | COMMENT - EXTENSION | SCHEMA - ($existing) | COMMENT - SCHEMA ($existing) " >"$dump.list"
dr_pg pg_restore -d "$target_url" -L "$dump.list" --no-owner --no-privileges ${owner:+--role="$owner"} \
  --jobs="$jobs" --exit-on-error "$dump"
step "pg_restore (jobs $jobs)" "$t"

t=$(dr_now_ms)
dr_psql "$target_url" -f "$DR_SQL/fingerprint.sql" >"$DR_WORK/target-$stamp.tsv"
"$(dirname "$0")/verify.sh" compare "$DR_WORK/source-$stamp.tsv" "$DR_WORK/target-$stamp.tsv"
"$(dirname "$0")/mask.sh" --url "$target_url" --check-only
step "verify (fingerprints identical, mask-check on staging)" "$t"

step "total" "$t_all"
dr_log "timings in $record"
cat >&2 <<NEXT

Staging now holds masked prod data (Flyway $(awk -F'\t' '$1=="flyway" && $2=="version" { print $3 }' "$DR_WORK/target-$stamp.tsv")). Before scaling the apps up again:
  - Valkey: FLUSHALL on staging's cache (sessions and caches of the old staging data)
  - search: run the search-reindex Job (docs/runbooks/search.md § Full reindex) — the index still holds the old data
  - OAuth clients: sync the chart (the oauth-clients Job writes staging's client secrets; masking removed prod's)
  - Kafka: nothing — outbox rows that were pending in prod are re-published to staging's topics (no personal data)
  - object storage: not copied; masked rows point at prod object keys staging cannot read (images show as missing)
  - testers sign up again: masking removed every passkey, TOTP secret, session and federated link
Then delete the temporary instance: scripts/dr/restore.sh delete --cloud <cloud> --env prod --name <restored copy>
NEXT
