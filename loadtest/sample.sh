#!/usr/bin/env bash
# S-119: every 15 s, what the local api process holds — for the soak's leak check and the stress run's saturation
# (the api's /actuator/prometheus is closed to callers; on staging read the same series in Grafana, runbook § Staging).
#   loadtest/sample.sh <api pid> <out.csv>      stops when the process does (or is killed)
# Columns: time, heap used (MB, jstat), old generation used (MB — after a GC this is the live set), GC count,
# threads, RSS (MB), connections to Postgres in use (pg_stat_activity, when psql reaches PGPORT).
set -uo pipefail
pid=$1 out=$2
echo "time,heap_used_mb,old_used_mb,gc_count,threads,rss_mb,db_active" >"$out"
while kill -0 "$pid" 2>/dev/null; do
  gc=$(jstat -gc "$pid" 2>/dev/null | tail -1)
  # S0U S1U EU OU are columns 3 4 6 8 (KB); YGC 13, FGC 15 (JDK 25 layout: S0C S1C S0U S1U EC EU OC OU MC MU CCSC CCSU YGC YGCT FGC FGCT CGC CGCT GCT)
  heap=$(echo "$gc" | awk '{printf "%.0f", ($3+$4+$6+$8)/1024}')
  old=$(echo "$gc" | awk '{printf "%.0f", $8/1024}')
  gcs=$(echo "$gc" | awk '{print $13+$15+$17}')
  threads=$(ls "/proc/$pid/task" 2>/dev/null | wc -l)
  rss=$(awk '/VmRSS/ {printf "%.0f", $2/1024}' "/proc/$pid/status" 2>/dev/null)
  db=$(PGPASSWORD=${PGPASSWORD:-northline} psql -h localhost -p "${LOAD_PG_PORT:-55432}" -U northline -d northline -qAtc \
    "select count(*) from pg_stat_activity where datname = 'northline' and state <> 'idle' and pid <> pg_backend_pid()" 2>/dev/null)
  echo "$(date -u +%H:%M:%S),$heap,$old,$gcs,$threads,$rss,${db:-}" >>"$out"
  sleep 15
done
