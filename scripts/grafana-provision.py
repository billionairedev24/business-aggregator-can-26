#!/usr/bin/env python3
"""Provision a Grafana through its HTTP API with Northline's data sources, folder and dashboards (idempotent).

    scripts/grafana-provision.py --url http://localhost:3001 --token glsa_…            # your own Grafana
    scripts/grafana-provision.py --url http://localhost:3001 --user admin --password …  # or basic auth
    make obs-grafana-provision GRAFANA_URL=http://localhost:3001 GRAFANA_TOKEN=glsa_…   # the same through make

What it does, every run (create or update, never duplicate):
  * data sources "Northline Prometheus", "Northline Loki", "Northline Tempo", "Northline Alertmanager" — uids
    northline-prometheus / -loki / -tempo / -alertmanager — linked to each other: trace → logs (Tempo → Loki by
    trace id), log → trace (Loki's trace_id → Tempo), exemplars and service graph (Prometheus), the Prometheus rules
    and the Alertmanager in Grafana's Alerting pages;
  * the folder "Northline" (uid northline);
  * every dashboard in deploy/observability/grafana/dashboards, its `datasource` variable set to Northline Prometheus;
  * with --alert-rules, the S-113 rule files as Grafana-managed alert rules in the folder (Grafana 12's Prometheus
    rule conversion API). Without it, Grafana still lists them read-only (Alerting → Alert rules → data source-managed)
    because Prometheus evaluates them — see docs/runbooks/observability.md § Your own Grafana.

The backend URLs are the ones *Grafana* uses, so they depend on where Grafana runs: on your machine the defaults
(http://localhost:<port> from PROMETHEUS_PORT, LOKI_PORT, TEMPO_PORT, ALERTMANAGER_PORT) work; a Grafana in Docker
needs --backend-host host.docker.internal (Docker Desktop; on Linux add --add-host=host.docker.internal:host-gateway
to its container); the bundled Grafana uses the compose service names (scripts/observability.sh passes them).
Credentials come from the flags or GRAFANA_TOKEN / GRAFANA_USER / GRAFANA_PASSWORD; a token needs the Admin role
(data sources) — a service account token is the usual choice. Standard library only (macOS's python3 works).
"""
import argparse
import base64
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
DASHBOARDS = ROOT / "deploy/observability/grafana/dashboards"
RULES = ROOT / "deploy/observability/prometheus/rules"
FOLDER_UID, FOLDER_TITLE = "northline", "Northline"
PROM, LOKI, TEMPO, AM = "northline-prometheus", "northline-loki", "northline-tempo", "northline-alertmanager"


class Grafana:
    def __init__(self, url, token=None, user=None, password=None):
        self.url = url.rstrip("/")
        self.headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if token:
            self.headers["Authorization"] = "Bearer " + token
        elif user:
            raw = f"{user}:{password or ''}".encode()
            self.headers["Authorization"] = "Basic " + base64.b64encode(raw).decode()

    def call(self, method, path, body=None, headers=None, raw=None):
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        req = urllib.request.Request(self.url + path, data=data, method=method, headers={**self.headers, **(headers or {})})
        try:
            with urllib.request.urlopen(req, timeout=30) as res:
                text = res.read().decode() or "{}"
                return res.status, json.loads(text) if text.lstrip().startswith(("{", "[")) else {"raw": text}
        except urllib.error.HTTPError as e:
            text = e.read().decode()
            try:
                return e.code, json.loads(text)
            except ValueError:
                return e.code, {"message": text}

    def must(self, method, path, body=None, **kw):
        code, res = self.call(method, path, body, **kw)
        if code >= 300:
            sys.exit(f"✗ {method} {path}: HTTP {code} {res.get('message', res)}")
        return res


def datasources(a):
    tempo_tags = [{"key": "service.name", "value": "service_name"}]
    return [
        {"uid": PROM, "name": "Northline Prometheus", "type": "prometheus", "url": a.prometheus_url, "isDefault": False,
         "jsonData": {"httpMethod": "POST", "prometheusType": "Prometheus", "prometheusVersion": "3.5.0",
                      "manageAlerts": True, "alertmanagerUid": AM, "timeInterval": "15s",
                      "exemplarTraceIdDestinations": [{"name": "trace_id", "datasourceUid": TEMPO}]}},
        {"uid": LOKI, "name": "Northline Loki", "type": "loki", "url": a.loki_url,
         "jsonData": {"maxLines": 1000,
                      # OTLP logs keep the trace id as structured metadata `trace_id`: one click to the trace.
                      "derivedFields": [{"name": "Trace", "matcherType": "label", "matcherRegex": "trace_id",
                                         "datasourceUid": TEMPO, "url": "${__value.raw}", "urlDisplayLabel": "Trace"}]}},
        {"uid": TEMPO, "name": "Northline Tempo", "type": "tempo", "url": a.tempo_url,
         "jsonData": {"tracesToLogsV2": {"datasourceUid": LOKI, "spanStartTimeShift": "-5m", "spanEndTimeShift": "5m",
                                         "tags": tempo_tags, "filterByTraceID": True, "customQuery": True,
                                         "query": '{${__tags}} | trace_id="${__trace.traceId}"'},
                      "tracesToMetrics": {"datasourceUid": PROM, "tags": tempo_tags},
                      "serviceMap": {"datasourceUid": PROM}, "nodeGraph": {"enabled": True},
                      "lokiSearch": {"datasourceUid": LOKI}, "search": {"hide": False}}},
        {"uid": AM, "name": "Northline Alertmanager", "type": "alertmanager", "url": a.alertmanager_url,
         "jsonData": {"implementation": "prometheus", "handleGrafanaManagedAlerts": False}},
    ]


def upsert_datasource(g, ds):
    body = {"access": "proxy", "basicAuth": False, **ds}
    code, found = g.call("GET", f"/api/datasources/uid/{ds['uid']}")
    if code == 404:  # one of ours under another uid (made by hand)? update that one rather than fail on the name
        code, found = g.call("GET", "/api/datasources/name/" + urllib.parse.quote(ds["name"]))
    if code == 200:
        body["uid"] = found["uid"]
        g.must("PUT", f"/api/datasources/uid/{found['uid']}", body)
        print(f"  updated  data source {ds['name']}  ({ds['url']})")
        return found["uid"]
    g.must("POST", "/api/datasources", body)
    print(f"  created  data source {ds['name']}  ({ds['url']})")
    return ds["uid"]


def ensure_folder(g):
    code, folder = g.call("GET", f"/api/folders/{FOLDER_UID}")
    if code == 200:
        return folder["uid"]
    for f in g.must("GET", "/api/folders?limit=1000"):
        if f.get("title") == FOLDER_TITLE:
            return f["uid"]
    print(f"  created  folder {FOLDER_TITLE}")
    return g.must("POST", "/api/folders", {"uid": FOLDER_UID, "title": FOLDER_TITLE})["uid"]


def import_dashboards(g, folder, prom_uid):
    n = 0
    for path in sorted(DASHBOARDS.glob("*.json")):
        board = json.loads(path.read_text())
        board.pop("id", None)
        for var in board.get("templating", {}).get("list", []):
            if var.get("type") == "datasource" and var.get("query") == "prometheus":
                var["current"] = {"selected": True, "text": "Northline Prometheus", "value": prom_uid}
        g.must("POST", "/api/dashboards/db", {"dashboard": board, "folderUid": folder, "overwrite": True,
                                              "message": "scripts/grafana-provision.py"})
        n += 1
    print(f"  imported {n} dashboards into the {FOLDER_TITLE} folder")


def rule_groups(path):
    """Split a Prometheus rule file into (group name, YAML text) without a YAML library: the files are written by
    Sloth or by hand in one shape — `groups:` then `- name:` items at a fixed indent."""
    lines = path.read_text().splitlines()
    groups, current, indent = [], None, None
    for line in lines:
        stripped = line.lstrip()
        if stripped.startswith("- name:") and (indent is None or len(line) - len(stripped) == indent):
            indent = len(line) - len(stripped)
            current = [line[indent + 2:]]
            groups.append(current)
        elif current is not None and (not stripped or len(line) - len(line.lstrip()) > indent):
            current.append(line[indent + 2:] if len(line) > indent + 2 else "")
    return [(g[0].split(":", 1)[1].strip().strip("'\""), "\n".join(g) + "\n") for g in groups]


def import_rules(g, prom_uid):
    files = sorted(RULES.glob("*.yml")) + sorted((RULES / "slo").glob("*.yaml"))
    n = 0
    for f in files:
        for name, text in rule_groups(f):
            code, res = g.call("POST", "/api/convert/prometheus/config/v1/rules/" + urllib.parse.quote(FOLDER_TITLE),
                               raw=text.encode(), headers={"Content-Type": "application/yaml",
                                                            "X-Grafana-Alerting-Datasource-UID": prom_uid,
                                                            "X-Grafana-Alerting-Folder-UID": FOLDER_UID})
            if code == 404:
                sys.exit("✗ this Grafana has no Prometheus rule conversion API (Grafana 12+, feature "
                         "alertingConversionAPI): skip --alert-rules — Grafana lists the Prometheus-evaluated rules anyway")
            if code >= 300:
                sys.exit(f"✗ rule group {name} ({f.name}): HTTP {code} {res.get('message', res)}")
            n += 1
    print(f"  imported {n} rule groups as Grafana-managed alert rules (folder {FOLDER_TITLE})")


def main():
    env = os.environ.get
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--url", default=env("GRAFANA_URL", "http://localhost:3000"))
    p.add_argument("--token", default=env("GRAFANA_TOKEN"))
    p.add_argument("--user", default=env("GRAFANA_USER"))
    p.add_argument("--password", default=env("GRAFANA_PASSWORD"))
    p.add_argument("--backend-host", default=env("GRAFANA_BACKEND_HOST", "localhost"),
                   help="host name Grafana reaches the backends' host ports on (default localhost)")
    p.add_argument("--prometheus-url")
    p.add_argument("--loki-url")
    p.add_argument("--tempo-url")
    p.add_argument("--alertmanager-url")
    p.add_argument("--alert-rules", action="store_true", default=env("GRAFANA_ALERT_RULES") in ("1", "true", "yes"),
                   help="also import the S-113 rules as Grafana-managed alert rules")
    p.add_argument("--wait", type=int, default=0, help="seconds to wait for Grafana to answer")
    a = p.parse_args()
    h = a.backend_host
    a.prometheus_url = a.prometheus_url or f"http://{h}:{env('PROMETHEUS_PORT', '9090')}"
    a.loki_url = a.loki_url or f"http://{h}:{env('LOKI_PORT', '3110')}"
    a.tempo_url = a.tempo_url or f"http://{h}:{env('TEMPO_PORT', '3210')}"
    a.alertmanager_url = a.alertmanager_url or f"http://{h}:{env('ALERTMANAGER_PORT', '9093')}"
    if not a.token and not a.user:
        sys.exit("✗ no credentials: GRAFANA_TOKEN=… (a service account token with the Admin role) or "
                 "GRAFANA_USER=… GRAFANA_PASSWORD=…")

    g = Grafana(a.url, a.token, a.user, a.password)
    deadline = time.time() + a.wait
    while True:
        code, health = g.call("GET", "/api/health")
        if code == 200:
            break
        if time.time() >= deadline:
            sys.exit(f"✗ Grafana does not answer on {a.url} (HTTP {code}) — is GRAFANA_URL right?")
        time.sleep(2)
    code, me = g.call("GET", "/api/datasources")
    if code in (401, 403):
        sys.exit(f"✗ {a.url} refused the credentials (HTTP {code}): the token or user needs the Admin role")
    print(f"Grafana {health.get('version', '?')} at {a.url}")

    uids = {ds["uid"]: upsert_datasource(g, ds) for ds in datasources(a)}
    folder = ensure_folder(g)
    import_dashboards(g, folder, uids[PROM])
    if a.alert_rules:
        import_rules(g, uids[PROM])
    print(f"Done: {a.url}/dashboards/f/{folder}/ · Explore → Northline Tempo / Northline Loki · Alerting → Alert rules")


if __name__ == "__main__":
    main()
