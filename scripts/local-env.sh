# Sourced by scripts/local-all.sh and scripts/observability.sh: read a setting the way the tools that own it do.
#   cfg KEY [default]     the environment, else the root .env (docker compose's), else the default
#   appcfg KEY [default]  the environment, else server/.env (the apps'), else the default
#   byo NAME              true when NAME (db, cache, events, search, mail, storage, payments, grafana) is in
#                         BYO_SERVICES — the stand-ins you run yourself (BYO=… on the command line wins over .env)
# bash 3.2 (macOS), no GNU-only tools. The files are KEY=value lines; the last one wins, as in compose and Spring.
# Compose strips " # comment" from unquoted values, Spring's .env import does not: each reader does what its owner does.

_env_file_value() { # _env_file_value <file> <key> <strip-comments 0|1>
  [ -f "$1" ] || return 1
  local line key value found=1
  while IFS= read -r line || [ -n "$line" ]; do
    # forgiving like compose: Windows line ends, leading blanks, "export KEY=…", blanks around '='
    line="${line%$'\r'}"
    line="${line#"${line%%[![:space:]]*}"}"
    case "$line" in export[[:space:]]*) line="${line#export}"; line="${line#"${line%%[![:space:]]*}"}" ;; esac
    case "$line" in "$2"=* | "$2"[[:space:]]*=*) ;; *) continue ;; esac
    key="${line%%=*}"; key="${key%"${key##*[![:space:]]}"}"
    [ "$key" = "$2" ] || continue
    value="${line#*=}"; value="${value#"${value%%[![:space:]]*}"}"; found=0
  done <"$1"
  [ $found = 0 ] || return 1
  if [ "$3" = 1 ]; then value="${value%%[[:space:]]#*}"; fi
  value="${value%"${value##*[![:space:]]}"}"
  case "$value" in \"*\") value="${value#\"}"; value="${value%\"}" ;; \'*\') value="${value#\'}"; value="${value%\'}" ;; esac
  printf '%s' "$value"
}

cfg() {
  local v
  if eval "[ -n \"\${$1+set}\" ]"; then eval "printf '%s' \"\$$1\""; return; fi
  if v="$(_env_file_value "$ROOT/.env" "$1" 1)"; then printf '%s' "$v"; return; fi
  printf '%s' "${2:-}"
}

appcfg() {
  local v
  if eval "[ -n \"\${$1+set}\" ]"; then eval "printf '%s' \"\$$1\""; return; fi
  if v="$(_env_file_value "$ROOT/server/.env" "$1" 0)"; then printf '%s' "$v"; return; fi
  printf '%s' "${2:-}"
}

# appcfg_set KEY: true when the environment or server/.env decides KEY (so make up-all leaves it alone)
appcfg_set() {
  eval "[ -n \"\${$1+set}\" ]" && return 0
  _env_file_value "$ROOT/server/.env" "$1" 0 >/dev/null
}

BYO_LIST="$(printf '%s' "${BYO-$(cfg BYO_SERVICES)}" | tr ',' ' ')"
byo() { case " $BYO_LIST " in *" $1 "*) return 0 ;; esac; return 1; }
