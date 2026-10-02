# shellcheck shell=bash
# Shared helpers of scripts/dr/*.sh (S-114, docs/runbooks/backups-dr.md). Sourced, not run.
#
# Connections are libpq URLs (postgresql://user:password@host:port/db?sslmode=require) so the same scripts work on
# every cloud and locally. pg_dump / pg_restore / pg_basebackup must be at least the server's major version (17): when
# the local ones are older, they run from the PostGIS image through Docker (DR_PG_IMAGE, DR_PG_TOOLS=docker|native).

DR_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
# shellcheck disable=SC2034 # used by the scripts that source this file
DR_SQL=$DR_ROOT/db/dr
DR_PG_IMAGE=${DR_PG_IMAGE:-postgis/postgis:17-3.5}
DR_PG_MAJOR=${DR_PG_MAJOR:-17}
DR_WORK=${DR_WORK:-${TMPDIR:-/tmp}/northline-dr}

dr_log() { printf '%s  %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
dr_die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# Milliseconds since the epoch (bash 3.2 / BSD date have no %N).
dr_now_ms() { python3 -c 'import time; print(int(time.time() * 1000))' 2>/dev/null || echo $(($(date +%s) * 1000)); }

# Seconds with one decimal between two dr_now_ms values.
dr_secs() { awk -v a="$1" -v b="$2" 'BEGIN { printf "%.1f", (b - a) / 1000 }'; }

# Hides the password of a URL for logs.
dr_redact() { sed -E 's#(://[^:/@]+):[^@]*@#\1:***@#' <<<"$1"; }

dr_tools_mode() {
  case "${DR_PG_TOOLS:-auto}" in
    native | docker) echo "$DR_PG_TOOLS" ;;
    auto)
      local v
      v=$(pg_dump --version 2>/dev/null | awk '{ print $NF }' | cut -d. -f1)
      if [ -n "$v" ] && [ "$v" -ge "$DR_PG_MAJOR" ]; then echo native; else echo docker; fi
      ;;
    *) dr_die "DR_PG_TOOLS must be auto, native or docker" ;;
  esac
}

# dr_pg <tool> <args…>: pg_dump, pg_restore, psql … at the server's major version. Docker mode mounts the repository
# and DR_WORK at the same paths and uses the host network (localhost URLs keep working).
dr_pg() {
  local tool=$1
  shift
  if [ "$(dr_tools_mode)" = native ]; then
    "$tool" "$@"
  else
    mkdir -p "$DR_WORK"
    docker run --rm -i --network host -v "$DR_ROOT:$DR_ROOT" -v "$DR_WORK:$DR_WORK" -w "$DR_ROOT" \
      --entrypoint "$tool" "$DR_PG_IMAGE" "$@"
  fi
}

# dr_psql <url> <psql args…>: psql that stops at the first error and reads no ~/.psqlrc.
dr_psql() {
  local url=$1
  shift
  dr_pg psql -X -q -v ON_ERROR_STOP=1 -d "$url" "$@"
}

# Refuses a database that applications are connected to: masking and restores only ever target a fresh copy.
dr_require_idle() {
  local url=$1 what=$2 n
  n=$(dr_psql "$url" -Atc "select count(*) from pg_stat_activity where datname = current_database() and pid <> pg_backend_pid() and backend_type = 'client backend'")
  [ "$n" = 0 ] || dr_die "$what has $n other client session(s): it looks live (an app or a person is connected). These scripts only touch a fresh restored copy."
}

# dr_url_db <url> <database>: the same server, another database (e.g. postgres, for DROP/CREATE DATABASE).
dr_url_db() { sed -E "s#^([a-z]+://[^/]+)/[^?]*#\1/$2#" <<<"$1"; }

# dr_url_server <url>: host:port/database without credentials, to compare two URLs.
dr_url_server() { sed -E 's#^[a-z]+://([^@/]*@)?([^?]*).*#\2#' <<<"$1"; }
