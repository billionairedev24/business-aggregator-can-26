#!/usr/bin/env bash
# Read-only health report of a deployed OVHcloud target, for the pipeline's `diagnose` action (or from a Mac with
# KUBECONFIG pointing at the cluster): pods and restarts, the api's event outbox, the worker's search indexing, Kafka's
# search-indexer consumer group, Meilisearch's document counts. Changes nothing. Prints no secrets: event payloads, Secret
# values and environment variables are never read; logs are filtered to search / Kafka / error lines (fake data only on
# the dry-run targets).
#
#   scripts/ovh/diagnose.sh TARGET
set -uo pipefail
. "$(dirname "$0")/common.sh"
ovh_target "${1:-}"
need kubectl
NS=${OVH_NAMESPACE:-northline-dryrun}
SINCE=${DIAGNOSE_SINCE:-48h}

section() { printf '\n==== %s\n' "$*"; }
psql_q() { kubectl -n "$NS" exec -i "$PG" -- psql -d northline -P pager=off -X -c "$1" 2>&1; }

section "Pods (restarts, status)"
kubectl get pods -A -o wide 2>&1 | grep -vE "kube-system" || true

section "Worker: last termination (OOMKilled / errors)"
for p in $(kubectl -n "$NS" get pods -o name 2>/dev/null | grep -E "worker|api"); do
  echo "-- $p"
  kubectl -n "$NS" get "$p" -o jsonpath='{range .status.containerStatuses[*]}{.name}{" restarts="}{.restartCount}{" last="}{.lastState.terminated.reason}{" exit="}{.lastState.terminated.exitCode}{"\n"}{end}' 2>&1
done

PG=$(kubectl -n "$NS" get pods -o name 2>/dev/null | grep -E "pod/northline-pg-[0-9]+$" | head -1 | sed 's#pod/##')
if [ -n "$PG" ]; then
  section "Outbox: events the api has not handed to Kafka yet (by type)"
  psql_q "select event_type, count(*) as stuck, min(publication_date) as oldest
            from events.event_publication where completion_date is null group by 1 order by 2 desc limit 20;"
  section "Outbox: the latest listing events"
  psql_q "select regexp_replace(event_type, '.*\.', '') as event, publication_date, completion_date
            from events.event_publication where event_type like '%Listing%' order by publication_date desc limit 15;"
  section "Search sweep watermark (search.sync_state)"
  psql_q "select * from search.sync_state;"
  section "Latest products and their sellers (what search needs: approved + live, seller active with a province)"
  psql_q "select o.id, left(p.name, 30) as product, o.vetting, o.status, o.updated_at,
                 m.status as seller, m.province, m.search_hidden_at is not null as hidden
            from catalogue.offers o join catalogue.catalog_products p on p.id = o.product_id
            join merchants.merchants m on m.id = o.merchant_id order by o.updated_at desc limit 10;"
else
  section "Postgres pod not found in $NS (managed database?): outbox checks skipped"
fi

section "Worker log: search / Kafka / errors (last $SINCE)"
kubectl -n "$NS" logs deploy/northline-worker --since="$SINCE" --all-containers 2>&1 \
  | grep -iE "search|reconcile|meili|kafka|listener|partition|dlq|dead|error|exception|warn|started" | tail -120

section "Worker log: previous container, if it restarted"
kubectl -n "$NS" logs deploy/northline-worker --previous --all-containers 2>&1 | tail -40

section "Api log: event externalization / Kafka (last $SINCE)"
kubectl -n "$NS" logs deploy/northline-api --since="$SINCE" --all-containers 2>&1 \
  | grep -iE "kafka|externaliz|publication|catalogue\.listing|outbox" | tail -60

section "Kafka: topics and the search-indexer consumer group"
KAFKA=$(kubectl get pods -A --no-headers 2>/dev/null | awk '$2 ~ /kafka/ && $2 !~ /operator|exporter|ui/ { print $1" "$2; exit }')
if [ -n "$KAFKA" ]; then
  set -- $KAFKA
  echo "kafka pod: $1/$2"
  kubectl -n "$1" exec "$2" -- sh -c '
    for d in /opt/kafka/bin /opt/bitnami/kafka/bin /usr/bin; do [ -x "$d/kafka-topics.sh" ] && B=$d && break; done
    [ -n "${B:-}" ] || { echo "kafka CLI not found in the pod"; exit 0; }
    echo "-- topics (catalogue / merchants / search-indexer)"
    $B/kafka-topics.sh --bootstrap-server localhost:9092 --list | grep -E "catalogue|merchants|search-indexer|dlq" | sort
    echo "-- consumer groups"
    $B/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list
    echo "-- search-indexer"
    $B/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group search-indexer' 2>&1 | tail -60
else
  echo "no Kafka pod found"
fi

section "Meilisearch: documents per index"
MEILI=$(kubectl get pods -A --no-headers 2>/dev/null | awk '$2 ~ /meili/ { print $1" "$2; exit }')
if [ -n "$MEILI" ]; then
  set -- $MEILI
  # The master key from the pod's own environment, used inside the pod only (never printed).
  kubectl -n "$1" exec "$2" -- sh -c '
    K=${MEILI_MASTER_KEY:-}
    for i in listings_en listings_fr; do
      printf "%s: " "$i"
      (command -v wget >/dev/null && wget -qO- --header="Authorization: Bearer $K" "http://localhost:7700/indexes/$i/stats") \
        || (command -v curl >/dev/null && curl -fsS -H "Authorization: Bearer $K" "http://localhost:7700/indexes/$i/stats") || echo "unreachable"
      echo
    done
    printf "failed tasks: "
    (command -v wget >/dev/null && wget -qO- --header="Authorization: Bearer $K" "http://localhost:7700/tasks?statuses=failed&limit=5") \
      || (command -v curl >/dev/null && curl -fsS -H "Authorization: Bearer $K" "http://localhost:7700/tasks?statuses=failed&limit=5") || echo "?"
    echo' 2>&1 | cut -c1-2000
else
  echo "no Meilisearch pod found"
fi
exit 0
