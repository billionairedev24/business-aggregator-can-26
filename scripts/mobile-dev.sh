#!/usr/bin/env bash
# The native apps against the stack on this machine (redesign phase 4; docs/runbooks/mobile-local.md), behind
# `make mobile-dev`:
#
#   scripts/mobile-dev.sh [consumer|courier]      Metro for the app, its API base URLs pointed at this machine
#
# Settings (environment, all optional):
#   TARGET   device   a phone on the same Wi-Fi: this machine's LAN address (default)
#            ios      the iOS Simulator on this Mac: localhost
#            android  the Android emulator: 10.0.2.2 (the emulator's name for this machine)
#   CLIENT   dev      a development build of the app (default; install it once with BUILD=1)
#            go       Expo Go (consumer only, with limits: docs/runbooks/mobile-local.md § Expo Go)
#   BUILD    1        build and install the development build first (expo run:ios / run:android; Xcode or the
#                     Android SDK), then start Metro
#   MOBILE_HOST  the address the phone uses for this machine, when the detected one is wrong (VPN, two networks)
#   FIXTURES 1        the app's in-app fixture backend: no server needed, nothing checked
#   CHECK    0        skip the checks that the api, auth and the consumer site answer on HOST
#   PLATFORM android  with BUILD=1 TARGET=device: build for a USB-connected Android phone (default: an iPhone on a Mac)
#   MOBILE_DEV_CHECK_ONLY 1  only the checks (make mobile-dev-check)
#
# It never changes your stack except one thing: when the consumer site (Vite, :3000) answers only on localhost and
# scripts/stack.sh runs it, it is restarted with NL_DEV_HOST=0.0.0.0 so the phone reaches the sign-in page and the
# app's /app/oauth2redirect. bash 3.2 compatible (macOS): no mapfile, no associative arrays, no ${var,,}.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="${1:-${APP:-consumer}}"
TARGET="${TARGET:-device}"
CLIENT="${CLIENT:-dev}"
BUILD="${BUILD:-0}"
FIXTURES="${FIXTURES:-0}"
CHECK="${CHECK:-1}"
API_PORT=8080
AUTH_PORT=9000
SITE_PORT=3000

c_dim=$'\033[2m'; c_ok=$'\033[32m'; c_bad=$'\033[31m'; c_warn=$'\033[33m'; c_bold=$'\033[1m'; c_off=$'\033[0m'
[ -t 1 ] || { c_dim=; c_ok=; c_bad=; c_warn=; c_bold=; c_off=; }
say() { printf '%s\n' "$*"; }
ok() { say "${c_ok}✓${c_off} $*"; }
warn() { say "${c_warn}!${c_off} $*"; }
die() { say "${c_bad}✗ $*${c_off}" >&2; exit 1; }

case "$APP" in
  consumer) PKG=@northline/consumer-app; DIR="$ROOT/mobile/apps/consumer" ;;
  courier) PKG=@northline/courier; DIR="$ROOT/mobile/apps/courier" ;;
  *) die "APP must be consumer or courier (got '$APP')" ;;
esac
case "$TARGET" in device | ios | android) ;; *) die "TARGET must be device, ios or android (got '$TARGET')" ;; esac
case "$CLIENT" in dev | go) ;; *) die "CLIENT must be dev or go (got '$CLIENT')" ;; esac
if [ "$CLIENT" = go ] && [ "$APP" = courier ]; then
  die "the courier app needs a development build (camera, background location, its sign-in redirect): drop CLIENT=go"
fi
if [ "$CLIENT" = go ] && [ "$BUILD" = 1 ]; then die "BUILD=1 builds the development build; Expo Go needs none (drop one of them)"; fi

# ── this machine's address on the network ────────────────────────────────────────────────────────────────────────
lan_ip() {
  local ifc ip
  if command -v ipconfig >/dev/null 2>&1 && [ "$(uname -s)" = Darwin ]; then
    # the interface of the default route first (Wi-Fi or Ethernet), then the usual names
    ifc="$(route -n get default 2>/dev/null | awk '/interface:/ { print $2; exit }')"
    for i in $ifc en0 en1 en2 en3; do
      ip="$(ipconfig getifaddr "$i" 2>/dev/null || true)"
      [ -n "$ip" ] && { printf '%s' "$ip"; return 0; }
    done
  fi
  if command -v ip >/dev/null 2>&1; then
    ip="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{ for (i = 1; i < NF; i++) if ($i == "src") { print $(i + 1); exit } }')"
    [ -n "$ip" ] && { printf '%s' "$ip"; return 0; }
  fi
  if command -v hostname >/dev/null 2>&1; then
    ip="$(hostname -I 2>/dev/null | awk '{ print $1 }' || true)"
    [ -n "$ip" ] && { printf '%s' "$ip"; return 0; }
  fi
  return 1
}

LAN="$(lan_ip || true)"
HOST="${MOBILE_HOST:-}"
if [ -z "$HOST" ]; then
  case "$TARGET" in
    device) [ -n "$LAN" ] || die "couldn't find this machine's address on the network — set MOBILE_HOST=<its IP> (System Settings › Wi-Fi › Details)"; HOST="$LAN" ;;
    ios) HOST=localhost ;;
    android) HOST=10.0.2.2 ;;
  esac
fi

API_URL="http://$HOST:$API_PORT/api/v1"
AUTH_URL="http://$HOST:$AUTH_PORT"
SITE_URL="http://$HOST:$SITE_PORT"
# What this machine itself uses to check the services (10.0.2.2 only exists inside the emulator).
probe_host="$HOST"
[ "$TARGET" = android ] && probe_host=localhost

# ── checks ───────────────────────────────────────────────────────────────────────────────────────────────────────
answers() { # answers <url>: the HTTP status, 000 when nothing answers
  curl -s -o /dev/null -m 4 -w '%{http_code}' "$1" 2>/dev/null || true
}

firewall_hint() {
  say "   macOS: System Settings › Network › Firewall › Options — allow incoming connections for \"java\" (and \"node\")"
  say "   or turn the firewall off while testing. Then check from the phone's browser: $1"
}

check_one() { # check_one <name> <path> <port> <who runs it>
  local name="$1" path="$2" port="$3" who="$4" here there
  there="$(answers "http://$probe_host:$port$path")"
  case "$there" in 2* | 3*) ok "$name answers on http://$HOST:$port"; return 0 ;; esac
  here="$(answers "http://localhost:$port$path")"
  case "$here" in
    2* | 3*)
      [ "$probe_host" = localhost ] && return 0
      warn "$name answers on localhost:$port but not on $HOST:$port"
      return 1 ;;
    *) warn "$name isn't running (nothing on localhost:$port) — $who"; return 2 ;;
  esac
}

restart_site_on_lan() {
  if [ -f "$ROOT/.run/consumer.pid" ] && kill -0 "$(cat "$ROOT/.run/consumer.pid")" 2>/dev/null; then
    say "   restarting the consumer site so the phone can reach it (NL_DEV_HOST=0.0.0.0) …"
    NL_DEV_HOST=0.0.0.0 "$ROOT/scripts/stack.sh" up consumer
  else
    say "   the consumer site wasn't started by make up / make up-all: start it with NL_DEV_HOST=0.0.0.0, e.g."
    say "   NL_DEV_HOST=0.0.0.0 make up SERVICES=consumer   (or NL_DEV_HOST=0.0.0.0 in web/apps/consumer/.env)"
    return 1
  fi
}

if [ "$FIXTURES" != 1 ] && [ "$CHECK" != 0 ]; then
  command -v curl >/dev/null 2>&1 || die "curl is needed for the checks (or CHECK=0)"
  say "${c_bold}Checking the stack on $HOST${c_off} ${c_dim}(make up-all; CHECK=0 skips this)${c_off}"
  problems=0
  check_one "api" /actuator/health $API_PORT "make up-all (or make up SERVICES=\"auth api bff-consumer consumer\")" || { [ $? = 1 ] && firewall_hint "http://$HOST:$API_PORT/actuator/health"; problems=1; }
  check_one "auth" /.well-known/openid-configuration $AUTH_PORT "make up-all" || { [ $? = 1 ] && firewall_hint "http://$HOST:$AUTH_PORT/.well-known/openid-configuration"; problems=1; }
  # both apps sign in through the consumer site: the consumer app lands on its /app/oauth2redirect, the courier app's
  # system browser shows its sign-in page
  rc=0; check_one "consumer site" /app/oauth2redirect $SITE_PORT "make up-all" || rc=$?
  if [ $rc = 1 ]; then
    if restart_site_on_lan; then
      rc=0; check_one "consumer site" /app/oauth2redirect $SITE_PORT "make up-all" || rc=$?
      [ $rc = 1 ] && firewall_hint "$SITE_URL/app/oauth2redirect"
    fi
  fi
  [ $rc = 0 ] || problems=1
  if [ "$TARGET" = device ]; then
    # the auth server must be the local profile (it lets the LAN address sign in: LocalNetworkDevices)
    issuer="$(curl -s -m 4 "http://$probe_host:$AUTH_PORT/.well-known/openid-configuration" 2>/dev/null | sed -n 's/.*"issuer":"\([^"]*\)".*/\1/p' || true)"
    [ -n "$issuer" ] && say "   ${c_dim}auth issuer: $issuer (tokens carry it; the api checks it — the phone may use another address)${c_off}"
  fi
  if [ "${MOBILE_DEV_CHECK_ONLY:-0}" = 1 ]; then exit $problems; fi
  [ $problems = 0 ] || warn "the app will start, but sign-in or data won't work until the items above answer (docs/runbooks/mobile-local.md § Troubleshooting)"
fi

# ── Metro ────────────────────────────────────────────────────────────────────────────────────────────────────────
[ -d "$ROOT/mobile/node_modules" ] || (cd "$ROOT/mobile" && pnpm install --frozen-lockfile)

export APP_VARIANT=development
export EXPO_PUBLIC_API_URL="$API_URL"
export EXPO_PUBLIC_AUTH_ISSUER="$AUTH_URL"
export EXPO_PUBLIC_SITE_ORIGIN="$SITE_URL"
[ "$FIXTURES" = 1 ] && export EXPO_PUBLIC_FIXTURES=1
# The address in Metro's QR code / dev-client link: the phone loads the JavaScript from here too.
if [ "$TARGET" = device ]; then export REACT_NATIVE_PACKAGER_HOSTNAME="$HOST"; fi

say ""
say "${c_bold}$APP app${c_off} · target ${c_bold}$TARGET${c_off} · $( [ "$CLIENT" = go ] && echo "Expo Go" || echo "development build" )$( [ "$FIXTURES" = 1 ] && echo " · fixture backend" )"
if [ "$FIXTURES" != 1 ]; then
  say "  api   $API_URL"
  say "  auth  $AUTH_URL"
  say "  site  $SITE_URL"
fi
case "$APP" in
  consumer) say "  sign in: amara.osei@example.com (browse, orders) or jas.gill@example.com (pays: authenticator key NORTHLINEJASDEVTOTPSECRET2345672);" ;;
  courier) say "  sign in: priya.nair@example.com (a seeded courier with a run);" ;;
esac
say "  the 6-digit code is in the auth log: grep 'Verification code' $ROOT/.run/logs/auth.log | tail -1"
say ""

cd "$DIR"
if [ "$BUILD" = 1 ]; then
  case "$TARGET" in
    ios) exec npx expo run:ios ;;
    device)
      say "Building for a device: iOS with a USB-connected iPhone (Xcode signs it), else Android over adb."
      if [ "$(uname -s)" = Darwin ] && command -v xcodebuild >/dev/null 2>&1 && [ "${PLATFORM:-ios}" = ios ]; then exec npx expo run:ios --device; fi
      exec npx expo run:android --device ;;
    android) exec npx expo run:android ;;
  esac
fi
args="start"
[ "$CLIENT" = go ] && args="$args --go" || args="$args --dev-client"
case "$TARGET" in
  device) args="$args --lan" ;;
  ios) args="$args --localhost --ios" ;;
  android) args="$args --localhost --android" ;;
esac
# shellcheck disable=SC2086 # args is a list of flags
exec npx expo $args
