// Go-live readiness check (S-118): `make go-live-check ENV=… MARKET=…`. docs/runbooks/go-live.md § The readiness check.
//
// 1. Checks what lives in this repository and the machine running it — open critical/high security findings, open
//    critical/serious accessibility issues, the DR drill log, counsel sign-offs in the legal registry, French coverage
//    (`make i18n-check STRICT=1`, only where the market is French-first), the last e2e and load-test results, and the
//    deployed alert rules when a Prometheus answers.
// 2. Reads the market's checklist from the api (GET /api/v1/console/go-live/{market}) — every gate with its status.
// 3. RECORD=1: records the repository's results on the gates still recordable (source "script", who = the token's or the
//    dev user's staff member), then reads the checklist again.
// Prints one line per gate and exits 0 when no required gate blocks, 1 when one does, 2 on an error. Node 22, no deps.
//
// Environment: ENV (local|dev|staging|prod) · MARKET (a region market id) · RECORD=1 · GO_LIVE_API (default
// http://localhost:8080 for local) · GO_LIVE_TOKEN (a staff access token, required outside local) · GO_LIVE_STAFF (local
// dev user, default the seeded admin) · PROMETHEUS_URL (rules API; local default http://localhost:9090) ·
// E2E_RESULTS (Playwright JSON; default e2e-out/results.json) · GO_LIVE_RESULTS_MAX_DAYS (e2e/load age, default 7) ·
// GO_LIVE_DRILL_MAX_DAYS (default 92).
import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { a11yCriticals, backupDrill, e2eResults, legalSignoff, loadTest, securityFindings } from './parsers.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const ENV = process.env.ENV ?? 'local';
const MARKET = process.env.MARKET;
const RECORD = process.env.RECORD === '1';
const API = (process.env.GO_LIVE_API ?? (ENV === 'local' ? 'http://localhost:8080' : '')).replace(/\/+$/, '');
const TOKEN = process.env.GO_LIVE_TOKEN;
const STAFF = process.env.GO_LIVE_STAFF ?? '01J9ZD3V00000000000000PNA1';
const PROM = process.env.PROMETHEUS_URL ?? (ENV === 'local' ? 'http://localhost:9090' : '');

if (!MARKET) fail('MARKET is required: a region market id (the console’s Go-live screen lists them).');
if (!['local', 'dev', 'staging', 'prod'].includes(ENV)) fail(`ENV must be local, dev, staging or prod (got ${ENV}).`);
if (!API) fail('GO_LIVE_API is required outside local: the api base URL.');
if (ENV !== 'local' && !TOKEN) fail('GO_LIVE_TOKEN is required outside local: a staff access token with a second factor.');

function fail(message) { console.error(message); process.exit(2); }
const read = p => readFileSync(join(ROOT, p), 'utf8');

// ── what the repository says ─────────────────────────────────────────────────────────────────────────────────────
function repoChecks(frenchFirst) {
  const out = {};
  out.security_findings = securityFindings(read('docs/security/findings.md'));
  out.a11y_criticals = a11yCriticals(read('docs/a11y/audit.md'));
  out.backup_drill = backupDrill(read('docs/runbooks/backups-dr.md'), ENV);
  out.legal_signoff = legalSignoff(JSON.parse(read('web/packages/legal/registry.json')));
  const e2e = process.env.E2E_RESULTS ?? join(ROOT, 'e2e-out', 'results.json');
  if (existsSync(e2e)) out.e2e_results = e2eResults(JSON.parse(readFileSync(e2e, 'utf8')), statSync(e2e).mtimeMs);
  const results = join(ROOT, 'loadtest', 'results');
  const runs = existsSync(results) ? readdirSync(results).filter(d => d.includes(`-${ENV}-`) && existsSync(join(results, d, 'summary.json'))).sort() : [];
  if (runs.length) {
    const f = join(results, runs.at(-1), 'summary.json');
    out.load_test = loadTest(JSON.parse(readFileSync(f, 'utf8')), runs.at(-1), statSync(f).mtimeMs);
  }
  if (frenchFirst) {
    try {
      execFileSync('make', ['-s', '-C', ROOT, 'i18n-check', 'STRICT=1'], { stdio: 'pipe' });
      out.french_coverage = { status: 'pass', evidence: 'make i18n-check STRICT=1 passed: every customer-facing string has reviewed fr-CA.' };
    } catch (e) {
      out.french_coverage = { status: 'fail', evidence: `make i18n-check STRICT=1 failed: ${String(e.stdout ?? e.message).trim().split('\n').at(-1).slice(0, 300)}` };
    }
  }
  return out;
}

async function alertChecks() {
  if (!PROM) return {};
  try {
    const res = await fetch(`${PROM.replace(/\/+$/, '')}/api/v1/rules?type=alert`, { signal: AbortSignal.timeout(3000) });
    const body = await res.json();
    const rules = body.data.groups.filter(g => g.name.startsWith('northline')).flatMap(g => g.rules).filter(r => r.type === 'alerting');
    const firing = rules.filter(r => r.state === 'firing' && r.labels?.severity === 'page').map(r => r.name);
    return {
      alert_rules: rules.length
        ? { status: 'pass', evidence: `${rules.length} Northline alerting rules loaded in ${PROM}.` }
        : { status: 'fail', evidence: `No Northline alerting rules in ${PROM}.` },
      slo_alerts: firing.length
        ? { status: 'fail', evidence: `Paging alerts firing in ${PROM}: ${firing.join(', ')}.` }
        : { status: 'pass', evidence: `No paging alert firing in ${PROM}.` },
    };
  } catch {
    return {};
  }
}

// ── the api ──────────────────────────────────────────────────────────────────────────────────────────────────────
async function api(method, path, body) {
  const headers = { accept: 'application/json' };
  if (TOKEN) headers.authorization = `Bearer ${TOKEN}`;
  else headers['X-Dev-User'] = STAFF;
  if (body) headers['content-type'] = 'application/json';
  const res = await fetch(API + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const text = await res.text();
  if (!res.ok) fail(`${method} ${path} → ${res.status}: ${text.slice(0, 400)}`);
  return JSON.parse(text);
}

function print(c, repo) {
  const pad = (s, n) => String(s ?? '').slice(0, n).padEnd(n);
  console.log(`\nGo-live checklist — ${c.market.city} (${c.market.id}, ${c.market.province}) · stage ${c.market.stage} · ENV ${ENV}`);
  console.log(`${pad('gate', 20)}${pad('status', 15)}${pad('kind', 15)}${pad('owner', 17)}evidence`);
  for (const g of c.gates) {
    const evidence = g.evidence ?? `${g.code}${Object.keys(g.params).length ? ' ' + JSON.stringify(g.params) : ''}`;
    const local = repo[g.key] && g.status === 'pending' ? `  [repo: ${repo[g.key].status} — ${repo[g.key].evidence}]` : '';
    console.log(`${pad(g.key, 20)}${pad(g.status + (g.required ? '' : ' (opt)'), 15)}${pad(g.kind, 15)}${pad(g.owner, 17)}${evidence}${g.recordedBy ? ` — ${g.recordedBy.name}, ${g.recordedAt?.slice(0, 16)}` : ''}${local}`);
  }
  console.log(c.ready ? '\nREADY: no required gate blocks.' : `\nNOT READY: ${c.blocking.length} required gate(s) block: ${c.blocking.join(', ')}`);
}

const path = `/api/v1/console/go-live/${encodeURIComponent(MARKET)}`;
let checklist = await api('GET', path);
const repo = { ...repoChecks(checklist.market.frenchFirst), ...(await alertChecks()) };
if (RECORD) {
  for (const g of checklist.gates.filter(g => g.recordable && repo[g.key])) {
    await api('POST', `${path}/gates/${g.key}`, { ...repo[g.key], source: 'script' });
    console.log(`recorded ${g.key}: ${repo[g.key].status}`);
  }
  checklist = await api('GET', path);
}
print(checklist, repo);
process.exitCode = checklist.ready ? 0 : 1;
