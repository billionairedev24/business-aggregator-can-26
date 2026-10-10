#!/usr/bin/env sh
# Creates the Kafka topics listed in deploy/kafka/topics.yaml (S-25): every `<module>.<aggregate>` topic, its `.dlq`
# and the consumers' `.retry-<n>` topics, with the catalogue's partitions, retention and cleanup policy.
# Idempotent: existing topics are left alone and compared with the catalogue (DRIFT lines); nothing is ever deleted.
#
#   scripts/topics.sh                      # through the compose `kafka` container (--profile events)
#   KAFKA_TOPICS_CMD=kafka-topics.sh KAFKA_TOPICS_BOOTSTRAP=localhost:9092 scripts/topics.sh   # your own Kafka
#   scripts/topics.sh --list               # print the derived topics (name partitions retention.ms cleanup.policy)
#
# The compose `kafka-topics` one-shot service runs this automatically with `--profile events`. Deployed environments
# use the provisioning Job (the same catalogue through the Kafka admin API, which also corrects retention drift):
# docs/runbooks/infrastructure.md § 5.3. KAFKA_TOPICS_STRICT=1 makes drift an error (exit 3).
set -e
CATALOGUE=${KAFKA_TOPICS_CATALOGUE:-"$(dirname "$0")/../deploy/kafka/topics.yaml"}
CMD=${KAFKA_TOPICS_CMD:-"docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh"}
BOOTSTRAP=${KAFKA_TOPICS_BOOTSTRAP:-kafka:29092}
RF=${KAFKA_REPLICATION_FACTOR:-1}

[ -r "$CATALOGUE" ] || { echo "topics.sh: catalogue not found: $CATALOGUE" >&2; exit 2; }

# Derives "name partitions retention.ms cleanup.policy" lines from the catalogue. POSIX awk (busybox in the Kafka
# image): the catalogue format is flat on purpose (see its header). TopicCatalogueTest checks this output equals the
# Java derivation.
derive() {
  awk '
    function trim(s) { sub(/^[ \t]+/, "", s); sub(/[ \t]+$/, "", s); return s }
    function value(line) { sub(/[ \t]+#.*$/, "", line); sub(/^[^:]*:/, "", line); return trim(line) }
    function key(line) { line = trim(line); sub(/^- /, "", line); sub(/:.*$/, "", line); return trim(line) }
    function items(v, arr) { gsub(/[][]/, "", v); gsub(/[ \t]/, "", v); return v == "" ? 0 : split(v, arr, ",") }
    function need(n, v, what) {
      if (v !~ /^[0-9]+$/) { print "topics.sh: " what " of " n " must be a number: " v > "/dev/stderr"; bad = 1 }
    }
    /^[ \t]*(#.*)?$/ { next }
    /^[A-Za-z]/ { section = key($0); next }
    /^  - / { item++; kind[item] = section }
    {
      k = key($0); v = value($0)
      if (section == "defaults" || section == "dlq" || section == "retry") conf[section "." k] = v
      else if (item > 0) field[item "." k] = v
    }
    END {
      dp = conf["defaults.partitions"]; dr = conf["defaults.retentionHours"]; dc = conf["defaults.cleanupPolicy"]
      for (i = 1; i <= item; i++) {
        if (kind[i] != "topics") continue
        n = field[i ".name"]
        p = ((i ".partitions") in field) ? field[i ".partitions"] : dp
        r = ((i ".retentionHours") in field) ? field[i ".retentionHours"] : dr
        c = ((i ".cleanupPolicy") in field) ? field[i ".cleanupPolicy"] : dc
        need(n, p, "partitions"); need(n, r, "retentionHours")
        parts[n] = p
        out = out sprintf("%s %s %.0f %s\n", n, p, r * 3600000, c)
        out = out sprintf("%s.dlq %s %.0f %s\n", n, conf["dlq.partitions"], conf["dlq.retentionHours"] * 3600000, dc)
      }
      for (i = 1; i <= item; i++) {
        if (kind[i] != "consumers") continue
        g = field[i ".group"]
        nt = items(field[i ".topics"], ts); nd = items(field[i ".retryDelaysSeconds"], ds)
        for (t = 1; t <= nt; t++) {
          if (!(ts[t] in parts)) { print "topics.sh: consumer " g " reads unknown topic " ts[t] > "/dev/stderr"; bad = 1 }
          for (d = 0; d < nd; d++)
            out = out sprintf("%s.%s.retry-%d %s %.0f %s\n", ts[t], g, d, parts[ts[t]], conf["retry.retentionHours"] * 3600000, dc)
        }
      }
      if (bad) exit 2
      printf "%s", out
    }
  ' "$CATALOGUE"
}

DESIRED=$(derive)
if [ "$1" = "--list" ]; then
  printf '%s\n' "$DESIRED"
  exit 0
fi

# One --describe for everything that exists (one JVM start), then a --create per missing topic only.
EXISTING=$($CMD --bootstrap-server "$BOOTSTRAP" --describe --exclude-internal |
  awk '/PartitionCount/ {
         n = ""; p = ""; cfg = ""
         for (i = 1; i <= NF; i++) {
           if ($i == "Topic:") n = $(i + 1)
           if ($i == "PartitionCount:") p = $(i + 1)
           if ($i == "Configs:") cfg = $(i + 1)
         }
         print n, p, cfg
       }')

drift=0 ok=0
TMP=$(mktemp)
MISSING=$(mktemp)
trap 'rm -f "$TMP" "$MISSING"' EXIT
printf '%s\n' "$DESIRED" > "$TMP"
while read -r name partitions retention cleanup; do
  have=$(printf '%s\n' "$EXISTING" | awk -v n="$name" '$1 == n { print $2 " " $3; exit }')
  if [ -z "$have" ]; then
    echo "$name $partitions $retention $cleanup" >> "$MISSING"
    continue
  fi
  have_partitions=${have%% *}
  have_retention=$(printf '%s' "${have#* }" | tr ',' '\n' | sed -n 's/^retention\.ms=//p')
  if [ "$have_partitions" != "$partitions" ]; then
    echo "DRIFT  $name partitions: $have_partitions, catalogue $partitions (never changed automatically)"
    drift=$((drift + 1))
  elif [ "$have_retention" != "$retention" ]; then
    echo "DRIFT  $name retention.ms: ${have_retention:-broker default}, catalogue $retention (the provisioning command's apply corrects it)"
    drift=$((drift + 1))
  else
    ok=$((ok + 1))
  fi
done < "$TMP"

# Missing topics, a few CLI calls at a time (each is a JVM start). --if-not-exists: a concurrent run is harmless.
# Only when some are missing: xargs runs its command once even on empty input, and a --create without a topic fails.
export CMD BOOTSTRAP RF
[ ! -s "$MISSING" ] || xargs -n 4 -P "${KAFKA_TOPICS_PARALLEL:-4}" sh -c '
  $CMD --bootstrap-server "$BOOTSTRAP" --create --if-not-exists --topic "$0" --partitions "$1" \
    --replication-factor "$RF" --config "retention.ms=$2" --config "cleanup.policy=$3" >/dev/null
  echo "CREATE $0 (partitions $1, retention.ms $2)"' < "$MISSING"
created=$(wc -l < "$MISSING" | tr -d ' ')

total=$(wc -l < "$TMP" | tr -d ' ')
echo "topics.sh: $total topics in the catalogue: $created created, $ok unchanged, $drift with drift"
if [ "$drift" -gt 0 ] && [ "${KAFKA_TOPICS_STRICT:-0}" = "1" ]; then exit 3; fi
