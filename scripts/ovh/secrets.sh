#!/usr/bin/env bash
# OVHcloud targets (docs/runbooks/ovh.md § Secrets): the apps' and the data services' secrets as plain Kubernetes
# Secrets, written from ONE local file the operator keeps — .ovh/<target>.env (git-ignored, mode 600). OVHcloud has no
# secrets manager External Secrets could read, so this replaces Secret Manager + External Secrets of Google Cloud.
#
#   scripts/ovh/secrets.sh init  ovh-vps|ovh-mks   create the file: every generated secret filled in (random), the
#                                                  third-party ones (Stripe test keys, Twilio, registry) empty to fill
#   scripts/ovh/secrets.sh check ovh-vps|ovh-mks   which required keys are still empty (names only, never values)
#   scripts/ovh/secrets.sh apply ovh-vps|ovh-mks   write the Secrets into the cluster (kubectl's current context):
#       northline-secrets       every app variable that has a value (+ STORAGE_* keys and, with Managed PostgreSQL,
#                               DB_PASSWORD from Terraform's state)
#       northline-pg-app        CloudNativePG's owner credentials (in-cluster Postgres)
#       northline-valkey, northline-meilisearch   the in-cluster services' passwords (the same values the apps get)
#       northline-pg-backup-s3  the Postgres backup user's S3 keys (in-cluster Postgres)
#       northline-registry      image pull credentials, when REGISTRY_SERVER/USERNAME/PASSWORD are set
#     then restarts nothing: pods read Secrets at start (scripts/ovh/deploy.sh rolls them).
#
# Values are never printed. Rotating one: edit the file, `apply`, then `kubectl -n northline-dryrun rollout restart
# deploy` (docs/runbooks/key-rotation.md for what each key protects). Losing the file loses nothing that can't be
# re-made, but TOTP_KEY and KMS_LOCAL_KEY changes invalidate enrolled authenticators' sealing and stored calendar tokens.
set -euo pipefail
. "$(dirname "$0")/common.sh"

cmd=${1:-}; ovh_target "${2:-}"

# Required by the dry-run values (secretEnv true) — must hold a value before `apply`.
REQUIRED="TOTP_KEY WEBHOOK_SECRET_KEY KMS_LOCAL_KEY STUDIO_BFF_SECRET STUDIO_BFF_SECRET_HASH CONSUMER_BFF_SECRET
CONSUMER_BFF_SECRET_HASH CONSOLE_BFF_SECRET CONSOLE_BFF_SECRET_HASH DB_PASSWORD MEILISEARCH_API_KEY
STRIPE_SECRET_KEY STRIPE_PUBLISHABLE_KEY STRIPE_WEBHOOK_SECRET STRIPE_CONNECT_WEBHOOK_SECRET"
# Settings kept in the same file that are not app secrets (never copied into northline-secrets).
NOT_APP="SMS_FROM SMS_ACCOUNT_ID REGISTRY_SERVER REGISTRY_USERNAME REGISTRY_PASSWORD"

rand_b64() { openssl rand -base64 32; }
rand_hex() { openssl rand -hex 20; }        # URL-, shell- and Valkey-ACL-safe
bcrypt() {
  if command -v htpasswd >/dev/null 2>&1; then htpasswd -bnBC 12 "" "$1"
  else docker run --rm httpd:2.4-alpine htpasswd -bnBC 12 "" "$1"
  fi | tr -d ':\n' | sed 's/^/{bcrypt}/'
}

init() {
  need openssl
  [ ! -e "$OVH_ENV_FILE" ] || die "$OVH_ENV_FILE exists — edit it, or move it away to start over"
  mkdir -p "$(dirname "$OVH_ENV_FILE")"
  umask 077
  local s_studio s_consumer s_console h_studio h_consumer h_console h
  s_studio=$(rand_b64); s_consumer=$(rand_b64); s_console=$(rand_b64)
  h_studio=$(bcrypt "$s_studio"); h_consumer=$(bcrypt "$s_consumer"); h_console=$(bcrypt "$s_console")
  for h in "$h_studio" "$h_consumer" "$h_console"; do
    case "$h" in ('{bcrypt}$2'*) ;; (*) die "bcrypt failed (htpasswd, or Docker with httpd:2.4-alpine, is needed)" ;; esac
  done
  {
    echo "# Northline $OVH_TARGET secrets (docs/runbooks/ovh.md § Secrets). KEY=value, taken literally; never commit."
    echo "# Generated:"
    echo "TOTP_KEY=$(rand_b64)"
    echo "WEBHOOK_SECRET_KEY=$(rand_b64)"
    echo "EMAIL_UNSUBSCRIBE_KEY=$(rand_b64)"
    echo "KMS_LOCAL_KEY=$(rand_b64)"
    echo "STUDIO_BFF_SECRET=$s_studio"
    echo "STUDIO_BFF_SECRET_HASH=$h_studio"
    echo "CONSUMER_BFF_SECRET=$s_consumer"
    echo "CONSUMER_BFF_SECRET_HASH=$h_consumer"
    echo "CONSOLE_BFF_SECRET=$s_console"
    echo "CONSOLE_BFF_SECRET_HASH=$h_console"
    echo "# In-cluster data services (with Managed PostgreSQL, DB_PASSWORD comes from Terraform instead; an external"
    echo "# service: its provider's credential here)."
    echo "DB_PASSWORD=$(rand_hex)"
    echo "REDIS_PASSWORD=$(rand_hex)"
    echo "MEILISEARCH_API_KEY=$(rand_hex)"
    echo "# Stripe TEST mode (docs/runbooks/stripe.md): sk_test_…, pk_test_…; the webhook secrets after the webhooks step."
    echo "STRIPE_SECRET_KEY="
    echo "STRIPE_PUBLISHABLE_KEY="
    echo "STRIPE_WEBHOOK_SECRET="
    echo "STRIPE_CONNECT_WEBHOOK_SECRET="
    echo "# Twilio for northline-auth's SMS codes (needed to register anyone under the dev profile — demo.md § 2):"
    echo "# all three set = SMS_PROVIDER=twilio."
    echo "SMS_AUTH_TOKEN="
    echo "SMS_ACCOUNT_ID="
    echo "SMS_FROM="
    echo "# Optional: SendGrid key (EMAIL_PROVIDER=sendgrid when set; emails are logged otherwise)."
    echo "EMAIL_API_KEY="
    echo "# External Kafka with SASL only: the whole JAAS line."
    echo "KAFKA_SASL_JAAS_CONFIG="
    echo "# Private image registry (e.g. ghcr.io + a read:packages token); empty = public images."
    echo "REGISTRY_SERVER="
    echo "REGISTRY_USERNAME="
    echo "REGISTRY_PASSWORD="
  } >"$OVH_ENV_FILE"
  chmod 600 "$OVH_ENV_FILE"
  echo "wrote $OVH_ENV_FILE (mode 600). Fill in the Stripe test keys (and Twilio), then: make ovh-secrets TARGET=$OVH_TARGET"
}

managed_pg() {
  [ "$OVH_TARGET" = ovh-mks ] || return 1
  [ "$(tf_out postgres_credentials 'if . == null then "no" else "yes" end' 2>/dev/null || echo no)" = yes ]
}

check() {
  [ -f "$OVH_ENV_FILE" ] || die "no $OVH_ENV_FILE — run: scripts/ovh/secrets.sh init $OVH_TARGET"
  local k missing=
  for k in $REQUIRED; do
    if [ "$k" = DB_PASSWORD ] && [ "${MANAGED_PG:-no}" = yes ]; then continue; fi
    [ -n "$(env_get "$k")" ] || missing="$missing $k"
  done
  case "$(env_get STRIPE_SECRET_KEY)" in sk_live_* | rk_live_*) die "STRIPE_SECRET_KEY is a live key: the dry run uses Stripe test mode only" ;; esac
  if [ -n "$missing" ]; then echo "empty in $OVH_ENV_FILE:$missing" >&2; return 1; fi
  echo "$OVH_ENV_FILE: every required key has a value"
}

# put_secret <name> <env file> [kubectl args…] — create or update a Secret from an env file without printing it.
put_secret() {
  local name=$1 file=$2; shift 2
  kubectl -n "$OVH_NAMESPACE" create secret generic "$name" --from-env-file="$file" "$@" --dry-run=client -o yaml \
    | kubectl -n "$OVH_NAMESPACE" apply -f - >/dev/null
  echo "secret/$name written ($(grep -c . "$file") keys)"
}

apply() {
  need kubectl terraform jq
  MANAGED_PG=no; if managed_pg; then MANAGED_PG=yes; fi
  check
  local in_pg in_cache in_search
  tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
  umask 077
  in_pg=$(tf_out in_cluster .postgres); in_cache=$(tf_out in_cluster .cache); in_search=$(tf_out in_cluster .search)
  kubectl get namespace "$OVH_NAMESPACE" >/dev/null 2>&1 || kubectl create namespace "$OVH_NAMESPACE" >/dev/null

  # northline-secrets: every non-empty app key from the file, then Terraform's.
  awk -F= -v skip=" $NOT_APP " '/^[A-Z][A-Z0-9_]*=./ { if (index(skip, " " $1 " ") == 0) print }' "$OVH_ENV_FILE" >"$tmp/app.env"
  if [ "$MANAGED_PG" = yes ]; then
    grep -v '^DB_PASSWORD=' "$tmp/app.env" >"$tmp/app2.env" || true; mv "$tmp/app2.env" "$tmp/app.env"
    printf 'DB_PASSWORD=%s\n' "$(tf_out postgres_credentials .app_password)" >>"$tmp/app.env"
  fi
  printf 'STORAGE_ACCESS_KEY=%s\nSTORAGE_SECRET_KEY=%s\n' "$(tf_out s3_credentials '.uploads.access_key_id')" \
    "$(tf_out s3_credentials '.uploads.secret_access_key')" >>"$tmp/app.env"
  put_secret northline-secrets "$tmp/app.env"

  if [ "$in_pg" = true ]; then
    printf 'username=%s\npassword=%s\n' "$(tf_out config_env .DB_USER)" "$(env_get DB_PASSWORD)" >"$tmp/pg.env"
    put_secret northline-pg-app "$tmp/pg.env" --type=kubernetes.io/basic-auth
    printf 'ACCESS_KEY_ID=%s\nACCESS_SECRET_KEY=%s\nREGION=%s\n' "$(tf_out s3_credentials '.["pg-backups"].access_key_id')" \
      "$(tf_out s3_credentials '.["pg-backups"].secret_access_key')" "$(tf_out s3_credentials '.["pg-backups"].region')" >"$tmp/s3.env"
    put_secret northline-pg-backup-s3 "$tmp/s3.env"
  fi
  if [ "$in_cache" = true ]; then
    printf 'REDIS_PASSWORD=%s\n' "$(env_get REDIS_PASSWORD)" >"$tmp/valkey.env"; put_secret northline-valkey "$tmp/valkey.env"
  fi
  if [ "$in_search" = true ]; then
    printf 'MEILISEARCH_API_KEY=%s\n' "$(env_get MEILISEARCH_API_KEY)" >"$tmp/meili.env"; put_secret northline-meilisearch "$tmp/meili.env"
  fi

  if [ -n "$(env_get REGISTRY_SERVER)" ] && [ -n "$(env_get REGISTRY_PASSWORD)" ]; then
    kubectl -n "$OVH_NAMESPACE" create secret docker-registry northline-registry --docker-server="$(env_get REGISTRY_SERVER)" \
      --docker-username="$(env_get REGISTRY_USERNAME)" --docker-password="$(env_get REGISTRY_PASSWORD)" \
      --dry-run=client -o yaml | kubectl -n "$OVH_NAMESPACE" apply -f - >/dev/null
    echo "secret/northline-registry written"
  fi
}

case "$cmd" in
  init) init ;;
  check) MANAGED_PG=no; if managed_pg; then MANAGED_PG=yes; fi; check ;;
  apply) apply ;;
  *) die "usage: $0 init|check|apply ovh-vps|ovh-mks" ;;
esac
