// The go-live check's readers of what lives in the repository (S-118): pure functions, tested by check.test.mjs.
// Each returns { status: 'pass' | 'fail', evidence } — what `make go-live-check RECORD=1` records on the gate.
const MAX_DAYS = Number(process.env.GO_LIVE_RESULTS_MAX_DAYS ?? 7);
const DRILL_DAYS = Number(process.env.GO_LIVE_DRILL_MAX_DAYS ?? 92);
const DAY = 86_400_000;

/** Markdown table rows under the first header that has every one of `columns` (lower-case), as objects. */
export function table(markdown, columns) {
  const lines = markdown.split('\n');
  for (let i = 0; i < lines.length - 1; i++) {
    const head = cells(lines[i]).map(c => c.toLowerCase());
    if (!columns.every(c => head.includes(c)) || !/^\|[\s:|-]+\|$/.test(lines[i + 1].trim())) continue;
    const rows = [];
    for (let j = i + 2; j < lines.length && lines[j].trim().startsWith('|'); j++) {
      const v = cells(lines[j]);
      rows.push(Object.fromEntries(head.map((h, k) => [h, v[k] ?? ''])));
    }
    return rows;
  }
  return [];
}
const cells = line => line.trim().replace(/^\||\|$/g, '').split('|').map(c => c.trim());

export function securityFindings(md) {
  const open = table(md, ['id', 'severity', 'status']).filter(r => /^(critical|high)/i.test(r.severity) && !/^fixed/i.test(r.status));
  return open.length
    ? { status: 'fail', evidence: `Open critical/high findings in docs/security/findings.md: ${open.map(r => r.id).join(', ')}.` }
    : { status: 'pass', evidence: 'No open critical or high finding in docs/security/findings.md (internal review; the external pentest is its own gate).' };
}

export function a11yCriticals(md) {
  const open = table(md, ['severity', 'status']).filter(r => /^(critical|serious)/i.test(r.severity) && !/^fixed/i.test(r.status));
  return open.length
    ? { status: 'fail', evidence: `Open critical/serious accessibility issues in docs/a11y/audit.md: ${open.length}.` }
    : { status: 'pass', evidence: 'No open critical or serious issue in docs/a11y/audit.md (WCAG 2.2 AA audit).' };
}

export function backupDrill(md, env, now = Date.now()) {
  const rows = table(md, ['date', 'where', 'result']).filter(r => /^\d{4}-\d{2}-\d{2}/.test(r.date));
  const counted = rows.filter(r => env === 'local' || !/^local/i.test(r.where));
  const last = counted.sort((a, b) => b.date.localeCompare(a.date))[0];
  if (!last) return { status: 'fail', evidence: `No ${env === 'local' ? '' : 'cloud '}drill in the drill log (docs/runbooks/backups-dr.md); only local drills ran.` };
  const days = Math.floor((now - Date.parse(last.date)) / DAY);
  const passed = /passed/i.test(last.result);
  return passed && days <= DRILL_DAYS
    ? { status: 'pass', evidence: `Last drill ${last.date} (${days} days ago, ${last.where.split(',')[0]}): passed.` }
    : { status: 'fail', evidence: `Last drill ${last.date} (${days} days ago): ${passed ? `older than ${DRILL_DAYS} days` : 'did not pass'}.` };
}

export function legalSignoff(registry) {
  const missing = registry.documents.filter(d => !d.versions.at(-1)?.signOff).map(d => d.id);
  return missing.length
    ? { status: 'fail', evidence: `No counsel sign-off on the current version of: ${missing.join(', ')} (make legal-status).` }
    : { status: 'pass', evidence: 'Every legal text’s current version has a counsel sign-off (web/packages/legal/registry.json).' };
}

export function e2eResults(json, mtime, now = Date.now()) {
  const s = json.stats ?? {};
  const at = Date.parse(s.startTime ?? '') || mtime;
  const age = Math.floor((now - at) / DAY);
  if (age > MAX_DAYS) return { status: 'fail', evidence: `The last e2e run is ${age} days old (more than ${MAX_DAYS}).` };
  return (s.unexpected ?? 1) === 0 && (s.expected ?? 0) > 0
    ? { status: 'pass', evidence: `e2e: ${s.expected} passed, ${s.flaky ?? 0} flaky, 0 failed (${new Date(at).toISOString().slice(0, 10)}).` }
    : { status: 'fail', evidence: `e2e: ${s.unexpected ?? '?'} failed of ${(s.expected ?? 0) + (s.unexpected ?? 0)} (${new Date(at).toISOString().slice(0, 10)}).` };
}

export function loadTest(summary, name, mtime, now = Date.now()) {
  const crossed = Object.entries(summary.metrics ?? {})
    .flatMap(([m, v]) => Object.entries(v.thresholds ?? {}).filter(([, c]) => c === true).map(([t]) => `${m} ${t}`));
  const age = Math.floor((now - mtime) / DAY);
  if (age > MAX_DAYS) return { status: 'fail', evidence: `The last load test (${name}) is ${age} days old (more than ${MAX_DAYS}).` };
  return crossed.length
    ? { status: 'fail', evidence: `Load test ${name}: SLO thresholds crossed: ${crossed.slice(0, 5).join('; ')}.` }
    : { status: 'pass', evidence: `Load test ${name}: every SLO threshold held (loadtest/results).` };
}
