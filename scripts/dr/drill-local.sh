#!/usr/bin/env bash
# S-114: the disaster-recovery drill on your machine, with Docker only (docs/runbooks/backups-dr.md § Local drill).
# It never touches your own databases or containers: everything runs in containers it starts (nl-dr-*) and removes.
#
#   make dr-drill                                     # migrate + seed a fresh source with Flyway (Gradle)
#   make dr-drill DR_FROM_URL=postgresql://northline:northline@localhost:5432/northline   # copy an existing database
#   scripts/dr/drill-local.sh [--from-url URL] [--rows 200000] [--keep] [--out results.md]
#
# Steps, each timed:
#   1. source: PostGIS 17 with WAL archiving (archive_command → a local folder), loaded with the schema and data
#      (Flyway + seed, or a copy of --from-url) plus --rows synthetic ledger entries so the timings mean something
#   2. backups: a physical base backup (pg_basebackup, tar + WAL) and a logical dump (pg_dump -Fc)
#   3. a restore point T after a marker row, then a "disaster" after T (reviews truncated, a user renamed)
#   4. point-in-time restore into a second container: base backup + archived WAL replayed up to T, promoted
#   5. verify: the restored database's fingerprint (Flyway version, row counts, checksums) equals the source's at T,
#      the marker is there and the disaster is not
#   6. the logical dump restored into a third container, verified against the source at dump time
#   7. prod → staging: scripts/dr/prod-to-staging.sh from the PITR copy into that third container (masking, check,
#      dump/restore, verify)
# The results (timings, RPO/RTO measured, verdicts) go to --out (default: $DR_WORK/drill-<time>/results.md).
set -euo pipefail
# shellcheck source=scripts/dr/lib.sh
. "$(dirname "$0")/lib.sh"

from_url=${DR_FROM_URL:-} rows=${DR_DRILL_ROWS:-200000} keep=0 out=''
while [ $# -gt 0 ]; do
  case $1 in
    --from-url) from_url=$2; shift 2 ;;
    --rows) rows=$2; shift 2 ;;
    --keep) keep=1; shift ;;
    --out) out=$2; shift 2 ;;
    -h | --help) sed -n '2,22p' "$0"; exit 0 ;;
    *) dr_die "unknown argument $1 (see --help)" ;;
  esac
done
command -v docker >/dev/null || dr_die "docker is required"

stamp=$(date -u +%Y%m%dT%H%M%SZ)
id=$(date -u +%H%M%S)
W=$DR_WORK/drill-$stamp
out=${out:-$W/results.md}
mkdir -p "$W/archive" "$W/backup"
chmod 777 "$W/archive" "$W/backup"
port() { python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])'; }
P_SRC=$(port) P_PITR=$(port) P_STG=$(port)
SRC=nl-dr-src-$id PITR=nl-dr-pitr-$id STG=nl-dr-stg-$id VOL=nl-dr-pitr-$id
PW=drill-only-password
url() { echo "postgresql://northline:$PW@localhost:$1/${2:-northline}"; }

cleanup() {
  if [ "$keep" = 1 ]; then
    dr_log "kept: containers $SRC $PITR $STG (ports $P_SRC $P_PITR $P_STG), volume $VOL, files $W"
    return
  fi
  docker rm -f "$SRC" "$PITR" "$STG" >/dev/null 2>&1 || true
  docker volume rm "$VOL" >/dev/null 2>&1 || true
}
trap cleanup EXIT

results=$W/timings.tsv
: >"$results"
timed() { # label start
  local s
  s=$(dr_secs "$2" "$(dr_now_ms)")
  printf '%s\t%s\n' "$1" "$s" >>"$results"
  dr_log "$1: $s s"
}
wait_ready() { # container
  for _ in $(seq 1 120); do
    # Over TCP: the image's first-start initialisation runs a socket-only server that must not count as ready.
    if docker exec "$1" pg_isready -h 127.0.0.1 -U northline -q 2>/dev/null &&
      docker exec "$1" psql -h 127.0.0.1 -U northline -d northline -Atc 'select 1' >/dev/null 2>&1; then return 0; fi
    sleep 0.5
  done
  docker logs "$1" | tail -20 >&2
  dr_die "$1 did not become ready"
}
psql_in() { # container sql…
  local c=$1
  shift
  docker exec -i "$c" psql -X -q -v ON_ERROR_STOP=1 -U northline -d northline "$@"
}

# ---- 1. source ------------------------------------------------------------------------------------------------
t=$(dr_now_ms)
docker run -d --name "$SRC" -e POSTGRES_DB=northline -e POSTGRES_USER=northline -e POSTGRES_PASSWORD="$PW" \
  -p "127.0.0.1:$P_SRC:5432" -v "$W/archive:/archive" -v "$W/backup:/backup" "$DR_PG_IMAGE" \
  -c wal_level=replica -c archive_mode=on -c archive_timeout=60 \
  -c 'archive_command=test ! -f /archive/%f && cp %p /archive/%f' >/dev/null
wait_ready "$SRC"
timed "start the source (PostGIS 17, WAL archiving)" "$t"

t=$(dr_now_ms)
if [ -n "$from_url" ]; then
  dr_log "loading a copy of $(dr_redact "$from_url")"
  docker exec "$SRC" psql -U northline -d postgres -q -c 'drop database northline' -c 'create database northline'
  dr_pg pg_dump -d "$from_url" -Fc | docker exec -i "$SRC" pg_restore -U northline -d northline --no-owner --exit-on-error
  how="copy of a local database"
else
  dr_log "migrating with Flyway and seeding (Gradle)"
  (cd "$DR_ROOT" && make db-migrate db-seed DB_URL="jdbc:postgresql://localhost:$P_SRC/northline" DB_USER=northline DB_PASSWORD="$PW")
  how="Flyway migrate + dev seed"
fi
psql_in "$SRC" -c "insert into payments.ledger_entries (id, account, debit_cents, credit_cents, ref_type, ref_id, at)
  select 'DRILL' || lpad(g::text, 12, '0'), 'drill', 1 + g % 5000, 0, 'drill', null, now() - g * interval '1 second'
    from generate_series(1, $rows) g"
psql_in "$SRC" -c 'checkpoint'
size=$(psql_in "$SRC" -Atc "select pg_size_pretty(pg_database_size('northline'))")
flyway=$(psql_in "$SRC" -Atc "select version from flyway_schema_history where success and version is not null order by installed_rank desc limit 1")
timed "load the source ($how, +$rows ledger rows; $size, Flyway $flyway)" "$t"

# ---- 2. backups -----------------------------------------------------------------------------------------------
t=$(dr_now_ms)
docker exec "$SRC" pg_basebackup -U northline -D /backup/base -Ft -z -X stream -c fast
timed "physical base backup (pg_basebackup -Ft -z -X stream; $(du -sh "$W/backup/base" | cut -f1))" "$t"

t=$(dr_now_ms)
docker exec "$SRC" pg_dump -U northline -d northline -Fc -f /backup/northline.dump
dump_fp=$W/source-at-dump.tsv
dr_psql "$(url "$P_SRC")" -f "$DR_SQL/fingerprint.sql" >"$dump_fp"
timed "logical dump (pg_dump -Fc; $(du -h "$W/backup/northline.dump" | cut -f1))" "$t"

# ---- 3. restore point and disaster ----------------------------------------------------------------------------
psql_in "$SRC" -c "create table if not exists public.dr_drill_marker (label text primary key, at timestamptz not null default clock_timestamp())" \
  -c "insert into public.dr_drill_marker (label) values ('before-T')"
T=$(psql_in "$SRC" -Atc "select to_char(clock_timestamp() at time zone 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') || '+00'")
source_fp=$W/source-at-T.tsv
dr_psql "$(url "$P_SRC")" -f "$DR_SQL/fingerprint.sql" >"$source_fp"
sleep 1
reviews=$(psql_in "$SRC" -Atc 'select count(*) from trust.reviews')
psql_in "$SRC" -c 'truncate trust.reviews' -c "update identity.users set display_name = 'oops'" \
  -c "insert into public.dr_drill_marker (label) values ('after-T')"
last_wal=$(psql_in "$SRC" -Atc 'select pg_walfile_name(pg_switch_wal())')
for _ in $(seq 1 60); do [ -f "$W/archive/$last_wal" ] && break; sleep 0.5; done
[ -f "$W/archive/$last_wal" ] || dr_die "WAL $last_wal was not archived"
dr_log "restore point T = $T; disaster after T ($reviews reviews truncated, every user renamed); WAL archived up to $last_wal"

# ---- 4. point-in-time restore ---------------------------------------------------------------------------------
t=$(dr_now_ms)
docker volume create "$VOL" >/dev/null
docker run --rm -v "$VOL:/var/lib/postgresql/data" -v "$W/backup:/backup:ro" --entrypoint bash "$DR_PG_IMAGE" -c "
  set -e; d=/var/lib/postgresql/data
  tar -xzf /backup/base/base.tar.gz -C \$d
  tar -xzf /backup/base/pg_wal.tar.gz -C \$d/pg_wal
  touch \$d/recovery.signal
  printf \"restore_command = 'cp /archive/%%f %%p'\nrecovery_target_time = '%s'\nrecovery_target_action = 'promote'\n\" '$T' >> \$d/postgresql.auto.conf
  chown -R postgres:postgres \$d; chmod 700 \$d"
docker run -d --name "$PITR" -p "127.0.0.1:$P_PITR:5432" -v "$VOL:/var/lib/postgresql/data" -v "$W/archive:/archive:ro" \
  "$DR_PG_IMAGE" >/dev/null
for _ in $(seq 1 240); do
  [ "$(docker exec "$PITR" psql -U northline -d northline -Atc 'select pg_is_in_recovery()' 2>/dev/null)" = f ] && break
  sleep 0.5
done
[ "$(docker exec "$PITR" psql -U northline -d northline -Atc 'select pg_is_in_recovery()')" = f ] || dr_die "PITR copy did not promote"
timed "point-in-time restore to T (base backup + WAL replay, promoted) — the database RTO" "$t"

# ---- 5. verify the PITR copy ----------------------------------------------------------------------------------
t=$(dr_now_ms)
pitr_fp=$W/pitr.tsv
dr_psql "$(url "$P_PITR")" -f "$DR_SQL/fingerprint.sql" >"$pitr_fp"
pitr_verdict=FAILED
if "$(dirname "$0")/verify.sh" compare "$source_fp" "$pitr_fp" &&
  [ "$(psql_in "$PITR" -Atc "select string_agg(label, ',' order by label) from public.dr_drill_marker")" = before-T ] &&
  [ "$(psql_in "$PITR" -Atc 'select count(*) from trust.reviews')" = "$reviews" ] &&
  [ "$(psql_in "$PITR" -Atc "select count(*) from identity.users where display_name = 'oops'")" = 0 ]; then
  pitr_verdict=passed
fi
last_commit=$(psql_in "$PITR" -Atc "select max(at) from public.dr_drill_marker")
rpo=$(psql_in "$PITR" -Atc "select round(extract(epoch from ('$T'::timestamptz - '$last_commit'::timestamptz))::numeric, 3)")
timed "verify the PITR copy ($pitr_verdict: fingerprint = source at T, marker kept, disaster undone)" "$t"
[ "$pitr_verdict" = passed ] || dr_die "PITR verification failed (fingerprints in $W)"

# ---- 6. logical dump restore ----------------------------------------------------------------------------------
t=$(dr_now_ms)
docker run -d --name "$STG" -e POSTGRES_DB=northline -e POSTGRES_USER=northline -e POSTGRES_PASSWORD="$PW" \
  -p "127.0.0.1:$P_STG:5432" -v "$W/backup:/backup:ro" "$DR_PG_IMAGE" >/dev/null
wait_ready "$STG"
docker exec "$STG" psql -U northline -d postgres -q -c 'create database northline_dump'
docker exec "$STG" pg_restore -U northline -d northline_dump --no-owner --exit-on-error -j 4 /backup/northline.dump
dump_restored_fp=$W/dump-restored.tsv
dr_psql "$(url "$P_STG" northline_dump)" -f "$DR_SQL/fingerprint.sql" >"$dump_restored_fp"
dump_verdict=FAILED
"$(dirname "$0")/verify.sh" compare "$dump_fp" "$dump_restored_fp" && dump_verdict=passed
timed "restore the logical dump into a fresh instance and verify ($dump_verdict)" "$t"
[ "$dump_verdict" = passed ] || dr_die "dump restore verification failed (fingerprints in $W)"

# ---- 7. prod → staging with masking ---------------------------------------------------------------------------
t=$(dr_now_ms)
DR_WORK=$W "$(dirname "$0")/prod-to-staging.sh" --source-url "$(url "$P_PITR")" --target-url "$(url "$P_STG")" --yes
mask_rows=$(psql_in "$STG" -Atc "select count(*) from identity.users where email::text like '%@example.invalid'")
timed "prod → staging from the PITR copy (mask, mask-check, dump, restore, verify; $mask_rows users masked)" "$t"

# ---- results --------------------------------------------------------------------------------------------------
{
  printf '# Local DR drill %s\n\n' "$stamp"
  printf -- '- host: %s, %s CPU, Docker %s, image %s\n' "$(uname -sm)" "$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo '?')" \
    "$(docker version --format '{{.Server.Version}}' 2>/dev/null)" "$DR_PG_IMAGE"
  printf -- '- source: %s, %s, Flyway %s\n' "$how" "$size" "$flyway"
  printf -- '- restore point T: %s; last commit before T recovered: %s (%s s before T)\n' "$T" "$last_commit" "$rpo"
  printf -- '- verdicts: PITR %s, dump restore %s, prod → staging masking passed\n\n' "$pitr_verdict" "$dump_verdict"
  printf '| step | seconds |\n|---|---:|\n'
  awk -F'\t' '{ printf "| %s | %s |\n", $1, $2 }' "$results"
  if ls "$W"/prod-to-staging-*.tsv >/dev/null 2>&1; then
    printf '\nprod → staging, by step:\n\n| step | seconds |\n|---|---:|\n'
    awk -F'\t' '{ printf "| %s | %s |\n", $1, $2 }' "$W"/prod-to-staging-*.tsv
  fi
} >"$out"
dr_log "drill passed; results in $out"
cat "$out"
