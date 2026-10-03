// Go-live rehearsal (S-118) — the driver; run through rehearsal.sh (make go-live-rehearsal). Two seeded admins
// (dev seed V191 and V334), dev auth (`local` profile). Everything here is FAKE: no real business, no Stripe live mode.
//
// Environment: API · MARKET (region market id) · PSQL (psql command line for the same database) · OUT.
import { execFileSync, execSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const { API, MARKET, PSQL, OUT = '.' } = process.env;
if (!API || !MARKET || !PSQL) throw new Error('API, MARKET and PSQL are required (run scripts/go-live/rehearsal.sh)');
const ADMIN_A = '01J9ZD3V00000000000000PNA1'; // dev seed V191
const ADMIN_B = '01J9ZD3V00000000000000MRC1'; // dev seed V334 (S-118): the second admin
const sql = q => execSync(PSQL, { input: q, encoding: 'utf8' }).trim();
const quote = s => `'${String(s).replace(/'/g, "''")}'`;
const log = (...a) => console.log(...a);
const started = Date.now();
const steps = [];
const step = (name, ok, detail) => { steps.push({ name, ok, detail }); log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`); };

async function call(method, path, { user = ADMIN_A, body, expect = [200, 201, 204] } = {}) {
  const headers = { 'X-Dev-User': user, accept: 'application/json' };
  if (body !== undefined) headers['content-type'] = 'application/json';
  const res = await fetch(API + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  const json = text ? JSON.parse(text) : undefined;
  if (!expect.includes(res.status)) throw new Error(`${method} ${path} → ${res.status}: ${text.slice(0, 500)}`);
  return { status: res.status, json };
}
const base = `/api/v1/console/go-live/${encodeURIComponent(MARKET)}`;
const checklist = async () => (await call('GET', base)).json;

const [CITY, PROVINCE, LAT, LNG] = sql(`select city || '|' || province || '|' || ST_Y(center::geometry) || '|' || ST_X(center::geometry) from region.regions where id = ${quote(MARKET)}`).split('|');
/** What a visitor finds: the market's stage, what an address at its centre resolves to, its searchable businesses. */
async function discovery() {
  const markets = (await call('GET', '/api/v1/geo/markets', { user: undefined })).json;
  const stage = markets.items.flatMap(p => p.markets).find(m => m.id === MARKET)?.stage;
  const resolved = (await call('GET', `/api/v1/geo/resolve?lat=${LAT}&lng=${LNG}`, { user: undefined })).json;
  const searchable = Number(sql(`select count(*) from merchants.merchants where province = ${quote(PROVINCE)} and lower(city) = lower(${quote(CITY)}) and status = 'active' and search_hidden_at is null`));
  const hidden = Number(sql(`select count(*) from merchants.merchants where province = ${quote(PROVINCE)} and lower(city) = lower(${quote(CITY)}) and status = 'active' and search_hidden_cause = 'pilot'`));
  return { stage, waitlist: resolved.waitlist ? resolved.waitlist.stage : null, searchable, hidden };
}
const fmt = d => `stage ${d.stage}, an address at the centre → ${d.waitlist ? `waitlist (${d.waitlist})` : 'delivered'}, ${d.searchable} businesses searchable, ${d.hidden} hidden (pilot)`;

function check(record) {
  try {
    return { code: 0, out: execFileSync('node', [join(HERE, 'check.mjs')], { env: { ...process.env, ENV: 'local', GO_LIVE_API: API, MARKET, RECORD: record ? '1' : '' }, encoding: 'utf8' }) };
  } catch (e) {
    return { code: e.status, out: String(e.stdout) + String(e.stderr ?? '') };
  }
}

log(`Go-live rehearsal — ${CITY} (${MARKET}, ${PROVINCE})`);

// 0. production starts with the market in pilot: put it there (the dry run used a live market)
await call('POST', `${base}/rollback`, { body: { reason: 'Rehearsal: production starts with the market in pilot.', confirm: CITY } });
let d = await discovery();
step('market back to pilot, hidden from new discovery', d.stage === 'pilot' && d.waitlist && d.searchable === 0, fmt(d));

// 1. the go-live check: manual gates pending, the launch blocked
let run = check(false);
writeFileSync(join(OUT, 'check-1.txt'), run.out);
let c = await checklist();
const pending = c.gates.filter(g => g.status === 'pending').map(g => g.key);
step('go-live check before anything is recorded: not ready', run.code === 1 && !c.ready, `${c.blocking.length} blocking; pending: ${pending.join(', ')}`);
const refused = await call('POST', `${base}/launch-requests`, { body: {}, expect: [409] });
step('a launch request is refused while gates block', refused.json.code === 'not_ready');

// 2. record: the repository's checks by script, then the rest by people (rehearsal stand-ins), and the on-call rota
run = check(true);
writeFileSync(join(OUT, 'check-2-record.txt'), run.out);
c = await checklist();
const byScript = c.gates.filter(g => g.source === 'script').map(g => `${g.key}=${g.status}`);
step('make go-live-check RECORD=1 recorded the repository checks', byScript.length >= 3, byScript.join(', '));
for (const g of c.gates.filter(g => g.recordable && g.status !== 'pass' && g.status !== 'not_applicable')) {
  await call('POST', `${base}/gates/${g.key}`, { user: ADMIN_B, body: { status: 'pass', evidence: `REHEARSAL STAND-IN, not real: ${g.key} would be signed off by its owner (${g.owner}) here.` } });
}
const now = Date.now();
for (let i = 0; i < 3; i++) {
  await call('POST', '/api/v1/console/oncall/shifts', { body: { userId: i % 2 ? ADMIN_B : ADMIN_A, startsAt: new Date(now - 3600_000 + i * 6 * 86_400_000).toISOString(), endsAt: new Date(now - 3600_000 + (i + 1) * 6 * 86_400_000).toISOString(), duty: 'Platform · rehearsal' } });
}
c = await checklist();
const left = c.blocking;
step('manual gates recorded with who and when; what still blocks is automatic', c.gates.filter(g => g.recordable).every(g => g.recordedBy && g.recordedAt), `still blocking: ${left.join(', ') || 'nothing'}`);

// 3. the two-person switch (an override for what a laptop can't have: Stripe live keys, real UAT sign-offs)
const override = left.length ? `Rehearsal on a local stack: ${left.join(', ')} can't pass without production.` : null;
const asked = (await call('POST', `${base}/launch-requests`, { body: { note: 'Go/no-go: go (rehearsal).', overrideReason: override } })).json;
const self = await call('POST', `${base}/launch-requests/${asked.request.id}/approve`, { body: { confirm: CITY }, expect: [409] });
step('the requester cannot approve their own launch', self.json.code === 'same_person');
const t0 = Date.now();
c = (await call('POST', `${base}/launch-requests/${asked.request.id}/approve`, { user: ADMIN_B, body: { confirm: CITY } })).json;
d = await discovery();
step('second admin approved: the market is live and public discovery shows it', c.market.stage === 'live' && d.stage === 'live' && !d.waitlist && d.hidden === 0 && d.searchable >= 10, `${fmt(d)}; ${Date.now() - t0} ms`);

// 4. rollback, then again
const t1 = Date.now();
c = (await call('POST', `${base}/rollback`, { user: ADMIN_B, body: { reason: 'Rehearsal: checkout errors over the rollback threshold.', confirm: CITY } })).json;
d = await discovery();
step('rollback: pilot again, hidden from new discovery, nothing cancelled', c.market.stage === 'pilot' && d.waitlist && d.searchable === 0, `${fmt(d)}; ${Date.now() - t1} ms`);
const again = (await call('POST', `${base}/launch-requests`, { user: ADMIN_B, body: { overrideReason: override } })).json;
c = (await call('POST', `${base}/launch-requests/${again.request.id}/approve`, { user: ADMIN_A, body: { confirm: CITY } })).json;
d = await discovery();
step('switched to live again (requested by the other admin this time)', c.market.stage === 'live' && !d.waitlist && d.searchable >= 10, fmt(d));

// 5. hypercare on top of the on-call rota
c = (await call('POST', `${base}/hypercare`, { body: { primaries: [ADMIN_A, ADMIN_B], secondaries: [ADMIN_B, ADMIN_A], businessContacts: [ADMIN_A] } })).json;
const shifts = Number(sql(`select count(*) from identity.oncall_shifts where duty like 'Hypercare%'`));
step('hypercare: 14 days, the paged people on the on-call rota', c.hypercare?.days.length === 14 && shifts === 28, `${c.hypercare?.startsOn} → ${c.hypercare?.endsOn}, ${shifts} on-call shifts`);
const audit = sql(`select string_agg(action || ' ' || n, ', ') from (select action, count(*) n from developer.audit_log where target_id = ${quote(MARKET)} and (action like 'golive.%' or action = 'region.stage_changed') group by action order by action) a`);
step('every step audited', audit.includes('golive.launch_approved 2') && audit.includes('golive.rolled_back 2'), audit);

run = check(false);
writeFileSync(join(OUT, 'check-3-final.txt'), run.out);
const failed = steps.filter(s => !s.ok);
log(`\n${steps.length - failed.length} of ${steps.length} steps passed in ${Math.round((Date.now() - started) / 1000)} s. Check outputs: ${OUT}/check-*.txt`);
process.exitCode = failed.length ? 1 : 0;
