#!/usr/bin/env bash
# S-114: masks a restored copy of prod so it can become staging (docs/runbooks/backups-dr.md § Prod snapshot to
# staging). Applies db/dr/mask/*.sql in one transaction — all or nothing —, then db/dr/mask-check.sql, which fails
# if any personal data or credential is left. Never run it against a live database: it refuses one with other
# sessions connected.
#
#   scripts/dr/mask.sh --url postgresql://admin:…@restored-host:5432/northline?sslmode=require [--owner-role northline_app]
#   scripts/dr/mask.sh --url … --check-only        # only the check (e.g. on staging after a refresh)
#
# --owner-role: the role that owns the tables (the migrations ran as it: northline_app on the clouds, the database
# user locally). The masking runs as that role because the immutability triggers of the audit log and reviews are
# switched off for the transaction, which only the owner may do. Empty = the connecting user.
set -euo pipefail
# shellcheck source=scripts/dr/lib.sh
. "$(dirname "$0")/lib.sh"

url=${DR_MASK_URL:-} owner=${DR_OWNER_ROLE:-} check_only=0
while [ $# -gt 0 ]; do
  case $1 in
    --url) url=$2; shift 2 ;;
    --owner-role) owner=$2; shift 2 ;;
    --check-only) check_only=1; shift ;;
    -h | --help) sed -n '2,15p' "$0"; exit 0 ;;
    *) dr_die "unknown argument $1 (see --help)" ;;
  esac
done
[ -n "$url" ] || dr_die "--url is required"

allow=${DR_MASK_ALLOW_DOMAINS:-}
skip=${DR_MASK_CHECK_SKIP:-}
settings="set northline.mask_allow_domains = '${allow}'; set northline.mask_check_skip = '${skip}';"

if [ "$check_only" = 0 ]; then
  dr_require_idle "$url" "$(dr_redact "$url")"
  args=()
  for f in "$DR_SQL"/mask/00_*.sql; do args+=(-f "$f"); done
  for f in "$DR_SQL"/mask/*.sql; do
    case $(basename "$f") in 00_*) ;; *) args+=(-f "$f") ;; esac
  done
  dr_log "masking $(dr_redact "$url") ($(( ${#args[@]} / 2 )) files, one transaction${owner:+, as $owner})"
  t0=$(dr_now_ms)
  dr_psql "$url" --single-transaction ${owner:+-c "set local role \"$owner\""} "${args[@]}"
  dr_log "masked in $(dr_secs "$t0" "$(dr_now_ms)") s"
fi

dr_log "checking $(dr_redact "$url") for personal data and credentials"
t0=$(dr_now_ms)
dr_psql "$url" -c "$settings" -f "$DR_SQL/mask-check.sql"
dr_log "mask-check passed in $(dr_secs "$t0" "$(dr_now_ms)") s"
