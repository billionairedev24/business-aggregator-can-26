#!/usr/bin/env python3
"""S-119: turns one run's k6 output into report.md — per scenario (rate, p95, p99, server errors, failed flows, the SLO
thresholds that apply and whether they held), the SLI metrics, a per-minute timeline (where a stress run's knee is),
and for a local run the api's memory, threads and connections over time (a soak's leak check).

    python3 loadtest/report.py loadtest/results/<run>      (run.sh does it)
"""
import csv
import gzip
import json
import math
import sys
from collections import defaultdict
from pathlib import Path

run = Path(sys.argv[1])
summary = json.loads((run / "summary.json").read_text())
metrics = summary.get("metrics", {})

# which thresholds decide each scenario (lib/slo.js)
OWN = {
    "search": ["http_req_duration{flow:search}", "search_rate_limited"],
    "checkout_food": ["slo_checkout_latency", "slo_checkout_errors"],
    "checkout_goods": ["slo_checkout_latency", "slo_checkout_errors"],
    "checkout_booking": ["slo_checkout_latency", "slo_checkout_errors"],
    "kds": ["kds_ticket_delivery", "kds_stream_ready"],
    "badges_studio": ["http_req_duration{flow:badges}"],
    "badges_consumer": ["http_req_duration{flow:badges}"],
    "badges_console": ["http_req_duration{flow:badges}"],
}
GLOBAL = ["server_errors", "flow_failures", "checks"]


def pct(values, p):
    if not values:
        return float("nan")
    values = sorted(values)
    k = (len(values) - 1) * p / 100
    lo, hi = math.floor(k), math.ceil(k)
    return values[lo] + (values[hi] - values[lo]) * (k - lo)


def ms(v):
    return "–" if v != v else (f"{v:.0f} ms" if v < 10_000 else f"{v / 1000:.1f} s")


def held(names):
    """'pass' / 'FAIL' / '–' for the thresholds on these metrics (summary-export: true = crossed)."""
    seen, failed = False, []
    for name in names:
        for expr, crossed in (metrics.get(name, {}).get("thresholds") or {}).items():
            seen = True
            if crossed:
                failed.append(f"{name} {expr}")
    return ("FAIL: " + "; ".join(failed)) if failed else ("pass" if seen else "–")


durations = defaultdict(list)
errors = defaultdict(int)
first = {}
last = {}
minutes = defaultdict(lambda: {"n": 0, "err": 0, "d": []})
flows = defaultdict(lambda: [0, 0])  # scenario → [flows, failed]
custom = defaultdict(list)
t0 = None
t_end = 0
with gzip.open(run / "timeline.csv.gz", "rt", newline="") as f:
    for row in csv.DictReader(f):
        name, ts, value = row["metric_name"], int(row["timestamp"]), float(row["metric_value"])
        scenario = row.get("scenario") or ""
        t0 = ts if t0 is None else min(t0, ts)
        t_end = max(t_end, ts)
        if name == "http_req_duration":
            durations[scenario].append(value)
            status = row.get("status") or "0"
            bad = status == "0" or status.startswith("5")
            errors[scenario] += bad
            first[scenario] = min(first.get(scenario, ts), ts)
            last[scenario] = max(last.get(scenario, ts), ts)
            m = minutes[ts]
            m["n"] += 1
            m["err"] += bad
            m["d"].append(value)
        elif name == "flow_failures":
            flows[scenario][0] += 1
            flows[scenario][1] += value > 0
        elif name in ("kds_ticket_delivery", "kds_stream_ready", "slo_checkout_latency"):
            custom[name].append(value)

duration = t_end - (t0 or t_end)  # the arrival-rate scenarios run (nearly) throughout
out = [f"# Load test {run.name}", ""]
out += ["Thresholds: S-113 SLOs (loadtest/lib/slo.js). `pass`/`FAIL` = every threshold on the scenario's metrics held.",
        ""]
out += ["| scenario | requests | req/s | p95 | p99 | 5xx / no answer | failed flows | SLO thresholds |",
        "|---|---:|---:|---:|---:|---:|---:|---|"]
for scenario in sorted(durations):
    d = durations[scenario]
    span = max(1, last[scenario] - first[scenario], duration * 0.9 if duration else 0)
    fl = flows.get(scenario, [0, 0])
    out.append(f"| {scenario} | {len(d)} | {len(d) / span:.1f} | {ms(pct(d, 95))} | {ms(pct(d, 99))} | "
               f"{errors[scenario]} ({100 * errors[scenario] / max(1, len(d)):.2f} %) | "
               f"{fl[1]:.0f} of {fl[0]} | {held(OWN.get(scenario, []))} |")
out += ["", f"Whole run: {held(GLOBAL)} (server_errors < 0.1 %, failed flows < 1 %, checks > 99 %).", ""]

out += ["## SLIs", "", "| metric | count | p95 | p99 | p99.5 | max |", "|---|---:|---:|---:|---:|---:|"]
for name, values in sorted(custom.items()):
    out.append(f"| {name} | {len(values)} | {ms(pct(values, 95))} | {ms(pct(values, 99))} | {ms(pct(values, 99.5))} | "
               f"{ms(max(values))} |")
out.append("")

out += ["## Per minute (all requests)", "", "| minute | req/s | p95 | 5xx / no answer |", "|---:|---:|---:|---:|"]
per_minute = defaultdict(lambda: {"n": 0, "err": 0, "d": []})
for ts, m in minutes.items():
    b = per_minute[(ts - t0) // 60]
    b["n"] += m["n"]
    b["err"] += m["err"]
    b["d"] += m["d"]
for minute in sorted(per_minute):
    m = per_minute[minute]
    out.append(f"| {minute} | {m['n'] / 60:.1f} | {ms(pct(m['d'], 95))} | {m['err']} |")
out.append("")

server = run / "server.csv"
if server.exists():
    rows = list(csv.DictReader(server.open()))
    if rows:
        out += ["## The api process (every 15 s)", ""]
        cols = [c for c in rows[0] if c != "time"]
        out += ["| | " + " | ".join(cols) + " |", "|---|" + "---:|" * len(cols)]

        def num(r, c):
            try:
                return float(r[c])
            except (TypeError, ValueError):
                return float("nan")

        quarter = max(1, len(rows) // 4)
        for label, pick in (("first", rows[0]), ("last", rows[-1])):
            out.append(f"| {label} | " + " | ".join(pick[c] for c in cols) + " |")
        out.append("| max | " + " | ".join(f"{max(num(r, c) for r in rows):.0f}" for c in cols) + " |")
        early = min(num(r, "old_used_mb") for r in rows[:quarter])
        late = min(num(r, "old_used_mb") for r in rows[-quarter:])
        out += ["", f"Old generation, lowest in the first quarter of the run: {early:.0f} MB; in the last quarter: "
                    f"{late:.0f} MB (a live set that keeps rising between those is a leak).", ""]

print("\n".join(out))
