#!/usr/bin/env bash
# S-114: restores an environment's managed PostgreSQL into a NEW instance — point in time, a backup/snapshot, or the
# secondary-region copy — and points the environment at it. Never overwrites the source: the restored instance gets
# its own name, the switch is a reviewed change of DB_URL. docs/runbooks/backups-dr.md has the procedures, RPO/RTO and
# the drill; these commands have NOT been run against a real cloud account yet (none exists): --dry-run first.
#
#   scripts/dr/restore.sh pitr     --cloud aws|gcp|azure --env prod --time 2026-10-02T13:05:00Z [--name …]
#   scripts/dr/restore.sh pitr     --cloud … --env … --time latest
#   scripts/dr/restore.sh snapshot --cloud … --env … [--backup <id>|latest] [--name …]
#   scripts/dr/restore.sh regional --cloud … --env prod [--time …] [--subnet-group … --security-group …   (AWS)]
#                                                                  [--subnet-id … --private-dns-zone …     (Azure)]
#   scripts/dr/restore.sh endpoint --cloud … --env … --name <restored instance>     # host + DB_URL
#   scripts/dr/restore.sh delete   --cloud … --env … --name <restored instance>     # temporary copies only
#   scripts/dr/restore.sh point    --env staging --url 'jdbc:postgresql://…/northline?sslmode=require'
#
# The source instance comes from `terraform output -json backup` of infra/terraform/envs/<cloud>/<env> (needs the
# state), or --instance. Google Cloud also needs --project (default: gcloud's), Azure --resource-group
# (default rg-northline-<env>). --region overrides the primary region. --dry-run prints every command, runs none.
set -euo pipefail
# shellcheck source=scripts/dr/lib.sh
. "$(dirname "$0")/lib.sh"

action=${1:-}
[ $# -gt 0 ] && shift
cloud='' env='' time_='' name='' backup='latest' instance='' project='' rg='' region='' url=''
subnet_group='' security_group='' subnet_id='' dns_zone='' dry=0
while [ $# -gt 0 ]; do
  case $1 in
    --cloud) cloud=$2; shift 2 ;;
    --env) env=$2; shift 2 ;;
    --time) time_=$2; shift 2 ;;
    --name) name=$2; shift 2 ;;
    --backup) backup=$2; shift 2 ;;
    --instance) instance=$2; shift 2 ;;
    --project) project=$2; shift 2 ;;
    --resource-group) rg=$2; shift 2 ;;
    --region) region=$2; shift 2 ;;
    --url) url=$2; shift 2 ;;
    --subnet-group) subnet_group=$2; shift 2 ;;
    --security-group) security_group=$2; shift 2 ;;
    --subnet-id) subnet_id=$2; shift 2 ;;
    --private-dns-zone) dns_zone=$2; shift 2 ;;
    --dry-run) dry=1; shift ;;
    -h | --help) sed -n '2,21p' "$0"; exit 0 ;;
    *) dr_die "unknown argument $1 (see --help)" ;;
  esac
done

# run <cmd…>: prints, then runs unless --dry-run.
run() {
  printf '+ %s\n' "$*" >&2
  [ "$dry" = 1 ] || "$@"
}
# ask <placeholder> <cmd…>: a lookup; under --dry-run prints it and answers the placeholder.
ask() {
  local placeholder=$1
  shift
  if [ "$dry" = 1 ]; then printf '+ %s\n' "$*" >&2; echo "$placeholder"; else "$@"; fi
}

case $action in
  point)
    [ -n "$env" ] && [ -n "$url" ] || dr_die "point needs --env and --url (a jdbc:postgresql:// URL)"
    case $url in jdbc:postgresql://*) ;; *) dr_die "--url must be a jdbc:postgresql:// URL (the apps' DB_URL)" ;; esac
    file=$DR_ROOT/deploy/argocd/envs/$env/values.yaml
    [ -f "$file" ] || dr_die "no $file"
    if [ "$dry" = 0 ] && yq --version 2>/dev/null | grep -q mikefarah; then
      DB_URL=$url yq -i '.configEnv.DB_URL = strenv(DB_URL)' "$file"
      dr_log "wrote configEnv.DB_URL into ${file#"$DR_ROOT"/}: commit it through a PR, then sync northline-$env in Argo CD"
    else
      printf 'Add to %s (it layers over infra.yaml), then PR + Argo CD sync:\n\nconfigEnv:\n  DB_URL: %s\n\n' "${file#"$DR_ROOT"/}" "$url"
    fi
    dr_log "DB_PASSWORD stays: the restored database carries the same roles and passwords as its source"
    exit 0
    ;;
  pitr | snapshot | regional | endpoint | delete) ;;
  *) sed -n '2,21p' "$0"; exit 2 ;;
esac

case $cloud in aws | gcp | azure) ;; *) dr_die "--cloud must be aws, gcp or azure" ;; esac
case $env in dev | staging | prod) ;; *) dr_die "--env must be dev, staging or prod" ;; esac
[ "$action" != pitr ] || [ -n "$time_" ] || dr_die "pitr needs --time (RFC 3339, UTC, e.g. 2026-10-02T13:05:00Z) or latest"

tf_dir=$DR_ROOT/infra/terraform/envs/$cloud/$env
tf_backup() { # jq filter on `terraform output -json backup`
  [ -n "${_tf_backup:-}" ] || _tf_backup=$(terraform -chdir="$tf_dir" output -json backup 2>/dev/null || echo '{}')
  jq -r "$1 // empty" <<<"$_tf_backup"
}
if [ -z "$instance" ] && [ "$action" != endpoint ] && [ "$action" != delete ]; then
  if [ "$dry" = 1 ]; then instance=$(tf_backup .postgres.instance); instance=${instance:-"<source instance>"}
  else instance=$(tf_backup .postgres.instance); fi
  [ -n "$instance" ] || dr_die "no source instance: pass --instance or run with access to $tf_dir's state"
fi
[ -n "$region" ] || region=$(tf_backup .primary_region)
secondary=$(tf_backup .secondary_region)
stamp=$(date -u +%Y%m%d%H%M)
[ -n "$name" ] || case $action in
  pitr | snapshot) name="northline-$env-pg-restore-$stamp" ;;
  regional) name="northline-$env-pg-dr-$stamp" ;;
  *) dr_die "--name (the restored instance) is required" ;;
esac
case $env in prod) ;; *) [ "$action" != regional ] || dr_die "regional restores are for prod (only prod keeps a secondary-region copy)" ;; esac

aws_restore() {
  local reg=${region:-ca-central-1} sg subnets params
  read -r subnets sg params < <(ask "<subnet-group> <security-group> <parameter-group>" aws rds describe-db-instances \
    --region "$reg" --db-instance-identifier "$instance" --output text \
    --query 'DBInstances[0].[DBSubnetGroup.DBSubnetGroupName, VpcSecurityGroups[0].VpcSecurityGroupId, DBParameterGroups[0].DBParameterGroupName]')
  # shellcheck disable=SC2054 # Key=…,Value=… is one AWS CLI argument
  local common=(--db-subnet-group-name "$subnets" --vpc-security-group-ids "$sg" --db-parameter-group-name "$params"
    --no-publicly-accessible --copy-tags-to-snapshot --tags Key=purpose,Value=dr-restore Key=source,Value="$instance")
  case $action in
    pitr)
      if [ "$time_" = latest ]; then
        run aws rds restore-db-instance-to-point-in-time --region "$reg" --source-db-instance-identifier "$instance" \
          --target-db-instance-identifier "$name" --use-latest-restorable-time "${common[@]}"
      else
        run aws rds restore-db-instance-to-point-in-time --region "$reg" --source-db-instance-identifier "$instance" \
          --target-db-instance-identifier "$name" --restore-time "$time_" "${common[@]}"
      fi
      ;;
    snapshot)
      local id=$backup
      [ "$id" != latest ] || id=$(ask "<latest automated snapshot>" aws rds describe-db-snapshots --region "$reg" \
        --db-instance-identifier "$instance" --snapshot-type automated --output text \
        --query 'reverse(sort_by(DBSnapshots, &SnapshotCreateTime))[0].DBSnapshotIdentifier')
      run aws rds restore-db-instance-from-db-snapshot --region "$reg" --db-instance-identifier "$name" \
        --db-snapshot-identifier "$id" "${common[@]}"
      ;;
  esac
  run aws rds wait db-instance-available --region "$reg" --db-instance-identifier "$name"
}

aws_regional() {
  local sec=${secondary:-ca-west-1} arn
  [ -n "$subnet_group" ] && [ -n "$security_group" ] || dr_die "regional (AWS) needs --subnet-group and --security-group in $sec: apply the prod env root with region=$sec first (docs/runbooks/backups-dr.md § Regional disaster)"
  arn=$(ask "<replicated automated backups ARN>" aws rds describe-db-instance-automated-backups --region "$sec" \
    --db-instance-identifier "$instance" --output text --query 'DBInstanceAutomatedBackups[0].DBInstanceAutomatedBackupsArn')
  local when=(--use-latest-restorable-time)
  [ -z "$time_" ] || [ "$time_" = latest ] || when=(--restore-time "$time_")
  run aws rds restore-db-instance-to-point-in-time --region "$sec" --source-db-instance-automated-backups-arn "$arn" \
    --target-db-instance-identifier "$name" "${when[@]}" --db-subnet-group-name "$subnet_group" \
    --vpc-security-group-ids "$security_group" --no-publicly-accessible --multi-az --deletion-protection \
    --tags Key=purpose,Value=dr-regional Key=source,Value="$instance"
  run aws rds wait db-instance-available --region "$sec" --db-instance-identifier "$name"
}

gcp_project() { [ -n "$project" ] || project=$(ask "<project>" gcloud config get-value project 2>/dev/null); echo "$project"; }

gcp_restore() {
  local p
  p=$(gcp_project)
  case $action in
    pitr)
      if [ "$time_" = latest ]; then
        run gcloud sql instances clone "$instance" "$name" --project "$p"
      else
        run gcloud sql instances clone "$instance" "$name" --project "$p" --point-in-time "$time_"
      fi
      ;;
    snapshot)
      local id=$backup src
      [ "$id" != latest ] || id=$(ask "<latest backup id>" gcloud sql backups list --instance "$instance" --project "$p" \
        --filter 'status=SUCCESSFUL' --sort-by ~windowStartTime --limit 1 --format 'value(id)')
      # A backup restores into an existing instance: create an empty one shaped like the source (tier, network,
      # region, key), then restore into it.
      src=$(ask '{"region":"<region>","settings":{"tier":"<tier>","ipConfiguration":{"privateNetwork":"<network>"}},"diskEncryptionConfiguration":{"kmsKeyName":"<key>"}}' \
        gcloud sql instances describe "$instance" --project "$p" --format json)
      run gcloud sql instances create "$name" --project "$p" --database-version POSTGRES_17 --edition enterprise \
        --region "$(jq -r .region <<<"$src")" --tier "$(jq -r .settings.tier <<<"$src")" \
        --network "$(jq -r .settings.ipConfiguration.privateNetwork <<<"$src")" --no-assign-ip \
        --disk-encryption-key "$(jq -r '.diskEncryptionConfiguration.kmsKeyName // empty' <<<"$src")" \
        --labels purpose=dr-restore
      run gcloud sql backups restore "$id" --restore-instance "$name" --backup-instance "$instance" --project "$p" --quiet
      ;;
  esac
}

gcp_regional() {
  local p replica
  p=$(gcp_project)
  replica=$(tf_backup .postgres.copy_id)
  replica=${replica:-"$instance-dr"}
  [ -z "$time_" ] || dr_log "Google Cloud: the DR replica is promoted as it is (seconds behind); --time is ignored"
  run gcloud sql instances promote-replica "$replica" --project "$p" --quiet
  run gcloud sql instances patch "$replica" --project "$p" --backup-start-time 07:00 \
    --enable-point-in-time-recovery --retained-backups-count 35 --retained-transaction-log-days 7 --quiet
  name=$replica
}

azure_rg() { echo "${rg:-rg-northline-$env}"; }

azure_restore() {
  local g
  g=$(azure_rg)
  case $action in
    pitr)
      if [ "$time_" = latest ]; then
        run az postgres flexible-server restore -g "$g" -n "$name" --source-server "$instance"
      else
        run az postgres flexible-server restore -g "$g" -n "$name" --source-server "$instance" --restore-time "$time_"
      fi
      ;;
    snapshot)
      # Flexible Server has no user snapshots: a backup (automatic or `az postgres flexible-server backup create`) is
      # restored as the point in time it completed at.
      local at
      if [ "$backup" = latest ]; then
        at=$(ask "<completedTime of the latest backup>" az postgres flexible-server backup list -g "$g" -n "$instance" \
          --query 'sort_by(@, &completedTime)[-1].completedTime' -o tsv)
      else
        at=$(ask "<completedTime of $backup>" az postgres flexible-server backup show -g "$g" -n "$instance" \
          --backup-name "$backup" --query completedTime -o tsv)
      fi
      run az postgres flexible-server restore -g "$g" -n "$name" --source-server "$instance" --restore-time "$at"
      ;;
  esac
}

azure_regional() {
  local g sec=${secondary:-canadaeast} id
  g=$(azure_rg)
  [ -n "$subnet_id" ] && [ -n "$dns_zone" ] || dr_die "regional (Azure) needs --subnet-id and --private-dns-zone in $sec: apply the prod env root with region=$sec first (docs/runbooks/backups-dr.md § Regional disaster)"
  id=$(ask "<source server id>" az postgres flexible-server show -g "$g" -n "$instance" --query id -o tsv)
  run az postgres flexible-server geo-restore -g "$g" -n "$name" --source-server "$id" --location "$sec" \
    --subnet "$subnet_id" --private-dns-zone "$dns_zone"
}

endpoint() {
  local host
  case $cloud in
    aws) host=$(ask "<host>" aws rds describe-db-instances --region "${region:-ca-central-1}" --db-instance-identifier "$name" --output text --query 'DBInstances[0].Endpoint.Address') ;;
    gcp) host=$(ask "<private ip>" gcloud sql instances describe "$name" --project "$(gcp_project)" --format 'value(ipAddresses[0].ipAddress)') ;;
    azure) host=$(ask "<fqdn>" az postgres flexible-server show -g "$(azure_rg)" -n "$name" --query fullyQualifiedDomainName -o tsv) ;;
  esac
  printf 'instance  %s\nhost      %s\nDB_URL    jdbc:postgresql://%s:5432/northline?sslmode=require\n' "$name" "$host" "$host"
}

delete() {
  case $name in *restore* | *mask* | *drill*) ;; *) dr_die "refusing to delete $name: only temporary copies (names with restore, mask or drill) — delete anything else by hand" ;; esac
  case $cloud in
    aws) run aws rds delete-db-instance --region "${region:-ca-central-1}" --db-instance-identifier "$name" --skip-final-snapshot --delete-automated-backups ;;
    gcp) run gcloud sql instances delete "$name" --project "$(gcp_project)" --quiet ;;
    azure) run az postgres flexible-server delete -g "$(azure_rg)" -n "$name" --yes ;;
  esac
}

t0=$(dr_now_ms)
case $action in
  pitr | snapshot) "${cloud}_restore" ;;
  regional) "${cloud}_regional" ;;
  endpoint) endpoint; exit 0 ;;
  delete) delete; exit 0 ;;
esac
dr_log "$action restore of $instance → $name took $(dr_secs "$t0" "$(dr_now_ms)") s$([ "$dry" = 1 ] && echo ' (dry run: nothing ran)')"
endpoint
cat >&2 <<NEXT

Next (docs/runbooks/backups-dr.md):
  1. scripts/dr/verify.sh check --source-url <live source> --target-url <restored> --only flyway,asof --as-of <time>
     (a snapshot/backup: compare with that backup's own time)
  2. for staging: scripts/dr/prod-to-staging.sh --source-url <restored> --target-url <staging>   (masks first)
     to switch an environment: scripts/dr/restore.sh point --env <env> --url <DB_URL above>, PR, Argo CD sync
  3. scripts/dr/restore.sh delete --cloud $cloud --env $env --name $name   when a temporary copy is done
NEXT
