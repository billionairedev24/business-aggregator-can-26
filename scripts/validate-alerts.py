#!/usr/bin/env python3
"""Offline checks of Northline's alerting as code (S-113, docs/runbooks/alerting.md). Needs Python 3 + PyYAML only, so
it runs where promtool, amtool, Sloth and Docker don't (scripts/observability.sh rules-check runs those too when present).

    python3 scripts/validate-alerts.py

1. Rule files (deploy/observability/prometheus/rules/**): every group is named; every rule is an alert or a recording
   rule with an expression; every alert has severity page|ticket, a summary, and a runbook_url that is the repository's
   docs/runbooks/alerts/<name>.md — and that file exists. No runbook page is orphaned.
2. SLO specs (deploy/observability/slo/*.yaml, Sloth prometheus/v1): objective in (0, 100), both queries windowed,
   page and ticket alerts with their severity, a runbook; the generated rules exist (rules/slo/<spec>) and carry the
   spec's alert names (Sloth generates them: make obs-slo).
3. The Helm chart's copies (deploy/helm/northline/files/alerting) equal the rule files.
4. The Alertmanager reference (deploy/observability/alertmanager/alertmanager.yml) routes severity="page" to the page
   receiver and severity="ticket" to the ticket receiver, and every receiver it names exists.
5. Region-neutral (DECISIONS 2026-09-30): no province, city or time zone in any of it.
"""
import pathlib
import re
import sys

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.exit("validate-alerts: needs PyYAML (pip install pyyaml)")

ROOT = pathlib.Path(__file__).resolve().parent.parent
OBS = ROOT / "deploy/observability"
RULES = OBS / "prometheus/rules"
SLO = OBS / "slo"
CHART = ROOT / "deploy/helm/northline/files/alerting"
RUNBOOKS = ROOT / "docs/runbooks/alerts"
RUNBOOK_BASE = "https://github.com/billionairedev24/business-aggregator-can-26/blob/main/docs/runbooks/alerts/"
SEVERITIES = {"page", "ticket"}
DURATION = re.compile(r"^([0-9]+(ms|s|m|h|d|w|y))+$")
PLACES = re.compile(
    r"\b(Alberta|Calgary|Edmonton|Ontario|Toronto|Quebec|Québec|Montreal|Montréal|Vancouver|British Columbia|"
    r"Manitoba|Saskatchewan|Winnipeg|Halifax|America/[A-Z][a-z_]+)\b"
)

problems: list[str] = []


def problem(where, what):
    problems.append(f"{where}: {what}")


def rel(path):
    return path.relative_to(ROOT)


def check_runbook(where, url, used):
    if not url:
        problem(where, "no runbook_url")
        return
    if not url.startswith(RUNBOOK_BASE) or not url.endswith(".md"):
        problem(where, f"runbook_url {url!r} is not {RUNBOOK_BASE}<name>.md")
        return
    page = RUNBOOKS / url[len(RUNBOOK_BASE):]
    if not page.is_file():
        problem(where, f"runbook {rel(page)} does not exist")
    used.add(page.name)


def rule_files():
    return sorted(p for p in RULES.rglob("*") if p.suffix in {".yml", ".yaml"})


def check_rules(used):
    alerts = {}
    for path in rule_files():
        doc = yaml.safe_load(path.read_text()) or {}
        groups = doc.get("groups")
        if not groups:
            problem(rel(path), "no groups")
            continue
        names = [g.get("name") for g in groups]
        if None in names or len(set(names)) != len(names):
            problem(rel(path), "every group needs a unique name")
        for g in groups:
            for r in g.get("rules") or []:
                where = f"{rel(path)} {g.get('name')}/{r.get('alert') or r.get('record')}"
                if ("alert" in r) == ("record" in r):
                    problem(where, "a rule is either an alert or a recording rule")
                if not str(r.get("expr", "")).strip():
                    problem(where, "no expr")
                if "for" in r and not DURATION.match(str(r["for"])):
                    problem(where, f"for: {r['for']!r} is not a duration")
                if "alert" not in r:
                    continue
                labels, annotations = r.get("labels") or {}, r.get("annotations") or {}
                if labels.get("severity") not in SEVERITIES:
                    problem(where, f"severity must be page or ticket, not {labels.get('severity')!r}")
                if not annotations.get("summary"):
                    problem(where, "no summary")
                if "runbook" in annotations:
                    problem(where, "use runbook_url (Alertmanager / Grafana convention), not runbook")
                check_runbook(where, annotations.get("runbook_url"), used)
                alerts.setdefault(r["alert"], set()).add(labels.get("severity"))
    return alerts


def check_slos(alerts, used):
    specs = sorted(SLO.glob("*.yaml"))
    if not specs:
        problem(rel(SLO), "no SLO specs")
    for spec in specs:
        doc = yaml.safe_load(spec.read_text())
        if doc.get("version") != "prometheus/v1" or not doc.get("service"):
            problem(rel(spec), "a Sloth prometheus/v1 spec with a service")
        generated = RULES / "slo" / spec.name
        if not generated.is_file():
            problem(rel(spec), f"not generated: {rel(generated)} is missing (make obs-slo)")
        for slo in doc.get("slos") or []:
            where = f"{rel(spec)} {slo.get('name')}"
            objective = slo.get("objective")
            if not isinstance(objective, (int, float)) or not 0 < objective < 100:
                problem(where, f"objective {objective!r} must be a percentage in (0, 100)")
            events = (slo.get("sli") or {}).get("events") or {}
            for q in ("error_query", "total_query"):
                if "{{.window}}" not in str(events.get(q, "")):
                    problem(where, f"{q} must use [{{{{.window}}}}]")
            alerting = slo.get("alerting") or {}
            name = alerting.get("name")
            if not name:
                problem(where, "alerting.name missing")
                continue
            for kind, severity in (("page_alert", "page"), ("ticket_alert", "ticket")):
                got = ((alerting.get(kind) or {}).get("labels") or {}).get("severity")
                if got != severity:
                    problem(where, f"{kind} must carry severity: {severity}")
            check_runbook(where, (alerting.get("annotations") or {}).get("runbook_url"), used)
            if alerts.get(name) != SEVERITIES:
                problem(where, f"the generated rules lack {name} (page and ticket): run make obs-slo")


def check_chart_copies():
    sources = {p.relative_to(RULES): p for p in rule_files()}
    copies = {p.relative_to(CHART): p for p in CHART.rglob("*") if p.suffix in {".yml", ".yaml"}}
    if set(sources) != set(copies):
        problem(rel(CHART), f"files differ from {rel(RULES)}: run make obs-slo")
    for name in set(sources) & set(copies):
        if sources[name].read_bytes() != copies[name].read_bytes():
            problem(rel(copies[name]), f"differs from {rel(sources[name])}: run make obs-slo")


def route_for(route, labels):
    """The receiver Alertmanager picks for a label set (first matching child, recursively; equality matchers)."""
    for child in route.get("routes") or []:
        matchers = child.get("matchers") or []
        if all(_matches(m, labels) for m in matchers):
            return route_for(child, labels) or child.get("receiver")
    return route.get("receiver")


def _matches(matcher, labels):
    m = re.fullmatch(r'\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*"?([^"]*)"?\s*', matcher)
    return bool(m) and labels.get(m.group(1)) == m.group(2)


def check_alertmanager():
    path = OBS / "alertmanager/alertmanager.yml"
    doc = yaml.safe_load(path.read_text())
    receivers = {r["name"] for r in doc.get("receivers") or []}
    for severity in SEVERITIES:
        got = route_for(doc["route"], {"severity": severity, "alertname": "Any"})
        if got != severity:
            problem(rel(path), f'severity="{severity}" goes to {got!r}, not the {severity} receiver')

    def walk(route):
        if route.get("receiver") and route["receiver"] not in receivers:
            problem(rel(path), f"route names unknown receiver {route['receiver']!r}")
        for child in route.get("routes") or []:
            walk(child)

    walk(doc["route"])


def check_region_neutral():
    files = [*rule_files(), *SLO.glob("*.yaml"), OBS / "alertmanager/alertmanager.yml", *RUNBOOKS.glob("*.md")]
    for path in files:
        for n, line in enumerate(path.read_text().splitlines(), 1):
            if m := PLACES.search(line):
                problem(f"{rel(path)}:{n}", f"names a place ({m.group(0)}): take it from the region model")


def main():
    used: set[str] = set()
    alerts = check_rules(used)
    check_slos(alerts, used)
    check_chart_copies()
    check_alertmanager()
    check_region_neutral()
    orphans = sorted(p.name for p in RUNBOOKS.glob("*.md") if p.name != "README.md" and p.name not in used)
    for name in orphans:
        problem(f"docs/runbooks/alerts/{name}", "no alert links to this runbook")
    if problems:
        print("\n".join(f"FAIL {p}" for p in problems))
        sys.exit(1)
    print(f"ok   {len(alerts)} alerts in {len(rule_files())} rule files, every one with a severity and a runbook; "
          f"{len(list(SLO.glob('*.yaml')))} SLO specs generated; chart copies current; routing page/ticket; no place names")


if __name__ == "__main__":
    main()
