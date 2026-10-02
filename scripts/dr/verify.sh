#!/usr/bin/env bash
# S-114: proves a restore — Flyway version, row counts and checksums of the key tables (db/dr/fingerprint.sql).
#
#   scripts/dr/verify.sh fingerprint --url URL [--as-of 2026-10-02T13:00:00Z] [--out file.tsv]
#   scripts/dr/verify.sh compare a.tsv b.tsv [--only flyway,asof]
#   scripts/dr/verify.sh check --source-url URL --target-url URL [--as-of T] [--only kinds]
#
# compare / check exit 1 and print the differing lines when the two databases differ in the kinds compared (all by
# default: flyway, rows, checksum, asof). A point-in-time restore is compared with the live source on
# `--only flyway,asof --as-of <restore time>`: the append-only tables agree up to that time whatever happened since.
# A snapshot or a dump restore is compared on everything.
set -euo pipefail
# shellcheck source=scripts/dr/lib.sh
. "$(dirname "$0")/lib.sh"

fingerprint() { # url as_of
  dr_psql "$1" ${2:+-v as_of="$2"} -f "$DR_SQL/fingerprint.sql"
}

compare() { # a b only
  local a=$1 b=$2 only=${3:-} filter
  filter=$(tr ',' '|' <<<"${only:-flyway,rows,checksum,asof}")
  if diff <(grep -E "^($filter)	" "$a" | sort) <(grep -E "^($filter)	" "$b" | sort) >"$DR_WORK/verify.diff"; then
    echo "verify: identical ($(grep -cE "^($filter)	" "$a") facts: ${only:-all kinds})"
  else
    echo "verify: DIFFERENT (< $(basename "$a")  > $(basename "$b"))"
    cat "$DR_WORK/verify.diff"
    return 1
  fi
}

mkdir -p "$DR_WORK"
cmd=${1:-}
[ $# -gt 0 ] && shift
url='' source='' target='' as_of='' out='' only=''
files=()
while [ $# -gt 0 ]; do
  case $1 in
    --url) url=$2; shift 2 ;;
    --source-url) source=$2; shift 2 ;;
    --target-url) target=$2; shift 2 ;;
    --as-of) as_of=$2; shift 2 ;;
    --out) out=$2; shift 2 ;;
    --only) only=$2; shift 2 ;;
    -h | --help) sed -n '2,13p' "$0"; exit 0 ;;
    -*) dr_die "unknown argument $1 (see --help)" ;;
    *) files+=("$1"); shift ;;
  esac
done

case $cmd in
  fingerprint)
    [ -n "$url" ] || dr_die "--url is required"
    if [ -n "$out" ]; then fingerprint "$url" "$as_of" >"$out"; else fingerprint "$url" "$as_of"; fi
    ;;
  compare)
    [ ${#files[@]} -eq 2 ] || dr_die "compare needs two fingerprint files"
    compare "${files[0]}" "${files[1]}" "$only"
    ;;
  check)
    [ -n "$source" ] && [ -n "$target" ] || dr_die "--source-url and --target-url are required"
    t0=$(dr_now_ms)
    fingerprint "$source" "$as_of" >"$DR_WORK/source.tsv"
    fingerprint "$target" "$as_of" >"$DR_WORK/target.tsv"
    dr_log "fingerprints taken in $(dr_secs "$t0" "$(dr_now_ms)") s (flyway $(awk -F'\t' '$1=="flyway" && $2=="version" { print $3 }' "$DR_WORK/target.tsv"))"
    compare "$DR_WORK/source.tsv" "$DR_WORK/target.tsv" "$only"
    ;;
  *) sed -n '2,13p' "$0"; exit 2 ;;
esac
