// Demo marketplace (docs/runbooks/demo.md) — the driver behind scripts/demo-data.sh. Builds a believable marketplace
// THROUGH THE PRODUCT: people register at northline-auth, owners onboard their businesses in the Studio's API, staff
// approve them in the console's API, customers buy and book through the consumer site's API — every call through the
// app's BFF with a real session, as the apps do. Never the dev seed. Every person, business and listing is FAKE.
//
// Re-runs are idempotent: what exists (by the state file's ids, the listings' DEMO- SKUs, the businesses' names) is
// reused and brought back to the plan; activity (orders, bookings, reviews) is made once. `--reset` takes demo-tagged
// data off the marketplace (see reset()). Node 22, no dependencies. Configuration: environment variables set by
// scripts/demo-data.sh (its header lists them).
import { execFileSync } from 'node:child_process';
import { chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { createInterface } from 'node:readline/promises';
import { AppSession, HttpError, request, CookieJar } from './lib/http.mjs';
import { demoPdf, listingPicture, logoPicture } from './lib/images.mjs';
import { freshTotp } from './lib/totp.mjs';

const env = process.env;
const ENV = env.DEMO_ENV ?? 'local';
const LOCAL = ENV === 'local';
const URLS = {
  studio: need('DEMO_STUDIO_URL'), consumer: need('DEMO_CONSUMER_URL'), console: need('DEMO_CONSOLE_URL'), auth: need('DEMO_AUTH_URL'),
};
const STATE_FILE = need('DEMO_STATE_FILE');
const PLAN = JSON.parse(readFileSync(env.DEMO_PLAN ?? new URL('./data/marketplace.json', import.meta.url), 'utf8'));
const RESET = env.DEMO_RESET === '1';
const ONLY = env.DEMO_MERCHANTS ? env.DEMO_MERCHANTS.split(',').map(s => s.trim()) : null;
const QUIET = env.CI === 'true' || env.DEMO_QUIET === '1';
const AREA = env.DEMO_AREA_CODE ?? '555';
const PERISHABLE = /\.(bakery|produce|dairy-and-eggs|butcher|frozen)$/; // final sale is for perishables only
const SKU = (m, l) => `DEMO-${m}-${l}`.toUpperCase();

function need(name) {
  const v = env[name];
  if (!v) { console.error(`${name} is not set — run scripts/demo-data.sh (make demo-data ENV=…)`); process.exit(2); }
  return v;
}

const log = (...a) => console.log(...a);
const step = s => log(`\n▸ ${s}`);
const sleep = ms => new Promise(r => setTimeout(r, ms));
const fill = s => String(s).replaceAll('{city}', market.city).replaceAll('{province}', market.province);
const todo = []; // steps a person has to do (a deployed environment's hosted Stripe pages, a missing code)

// ── state (the operator's file: identifiers, authenticator keys and backup codes — never committed, never printed) ──
let state = existsSync(STATE_FILE) ? JSON.parse(readFileSync(STATE_FILE, 'utf8')) : {};
state = { env: ENV, accounts: {}, merchants: {}, activity: {}, ...state };
function save() {
  mkdirSync(dirname(STATE_FILE), { recursive: true });
  writeFileSync(STATE_FILE, JSON.stringify(state, null, 2) + '\n', { mode: 0o600 });
  chmodSync(STATE_FILE, 0o600);
}

// ── the market: the configured default (GET /api/v1/geo/markets → fallback) unless DEMO_MARKET names one ─────────────
let market;
async function loadMarket() {
  const { body } = await request(new CookieJar(), 'GET', `${URLS.consumer}/api/v1/geo/markets`);
  const all = body.items.flatMap(p => p.markets);
  market = env.DEMO_MARKET ? all.find(m => m.id === env.DEMO_MARKET || m.city.toLowerCase() === env.DEMO_MARKET.toLowerCase()) : body.fallback;
  if (!market) throw new Error(`DEMO_MARKET=${env.DEMO_MARKET}: not a market of the region model (${all.map(m => m.id).join(', ')})`);
  if (!['live', 'pilot'].includes(market.stage)) log(`  ! ${market.id} is at stage ${market.stage}: customers can't order there until it is live`);
  if (market.stage === 'pilot') log(`  ! ${market.id} is a pilot market: it takes no orders until staff switch it live (console › Provinces)`);
  log(`  market ${market.id} — ${market.city}, ${market.province} (${market.stage})`);
}

/** Real-looking street addresses in the market from the product's own address search (places provider). */
let addressPool;
async function addresses() {
  if (addressPool) return addressPool;
  const jar = new CookieJar();
  const found = new Map();
  for (const q of [market.city, `${market.city} ${market.province}`, '1', 'Ave', 'St']) {
    const { body } = await request(jar, 'GET', `${URLS.consumer}/api/v1/geo/autocomplete?${new URLSearchParams({ q, lat: market.lat, lng: market.lng })}`, { expect: [200, 429] });
    for (const s of body?.items ?? []) {
      if (found.has(s.placeId)) continue;
      const place = await request(jar, 'GET', `${URLS.consumer}/api/v1/geo/places/${encodeURIComponent(s.placeId)}`, { expect: [200, 404, 429] });
      const a = place.body;
      if (place.status === 200 && a?.city?.toLowerCase() === market.city.toLowerCase()) found.set(s.placeId, a);
    }
  }
  addressPool = [...found.values()];
  if (!addressPool.length) log(`  ! the address search found no address in ${market.city}: businesses get "<n> Main St"; customers need DEMO_ADDRESS_STREET/DEMO_ADDRESS_POSTAL`);
  return addressPool;
}
const streetOf = a => a.street ?? a.line1 ?? [a.number, a.route].filter(Boolean).join(' ');
const postalOf = a => a.postal ?? a.postalCode ?? a.postal_code;

/** The authenticator QR code next to the state file (scan it into the presenter's authenticator app). */
function saveQr(key, dataUri) {
  const m = /^data:image\/png;base64,(.+)$/.exec(dataUri ?? '');
  if (!m) return;
  const dir = STATE_FILE.replace(/\.json$/, '') + '-qr';
  mkdirSync(dir, { recursive: true, mode: 0o700 });
  writeFileSync(join(dir, `${key}.png`), Buffer.from(m[1], 'base64'), { mode: 0o600 });
}

/** Pictures for the listing the presenter adds live (each unique: vetting flags a reused image). */
async function presenterPhotos() {
  const dir = STATE_FILE.replace(/\.json$/, '') + '-photos';
  if (existsSync(dir)) return dir;
  mkdirSync(dir, { recursive: true });
  for (const [i, title] of ['Autumn bouquet', 'Sunflower bunch', 'Potted fern', 'Gift card, $25', 'Holiday wreath', 'Succulent trio'].entries()) {
    writeFileSync(join(dir, `${i + 1}-${title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/-$/, '')}.png`), Buffer.from(await listingPicture(title, 'Demo listing', ['#8E3B46', '#B8860B', '#2E7D32', '#1565C0', '#6D4C41', '#00897B'][i]).arrayBuffer()));
  }
  return dir;
}

// ── people ────────────────────────────────────────────────────────────────────────────────────────────────────────
function phoneFor(key, suffix) {
  const override = env[`DEMO_PHONE_${key.toUpperCase()}`];
  if (override) return override;
  if (!LOCAL) throw new Error(`DEMO_PHONE_${key.toUpperCase()} is not set: outside local, every new account needs a phone that receives SMS (docs/runbooks/demo.md § Accounts)`);
  return `+1 ${AREA} 555 ${suffix}`;
}
const e164 = p => `+${p.replace(/\D/g, '').replace(/^(?=\d{10}$)/, '1')}`;

async function smsCode(phone, since) {
  if (LOCAL) {
    for (let n = 0; n < 30; n++) {
      const { body } = await request(new CookieJar(), 'GET', `${URLS.auth}/api/auth/dev/outbox?to=${encodeURIComponent(e164(phone))}`);
      const code = body.items.find(c => Date.parse(c.at) >= since - 2000)?.code;
      if (code) return code;
      await sleep(500);
    }
    throw new Error(`no SMS code for ${phone} in the local outbox`);
  }
  if (env.DEMO_SMS_CODE_CMD) return execFileSync('sh', ['-c', env.DEMO_SMS_CODE_CMD, 'sms-code', e164(phone)], { encoding: 'utf8' }).trim();
  if (!process.stdin.isTTY) throw new Error(`the code texted to ${phone} is needed: run in a terminal, or set DEMO_SMS_CODE_CMD`);
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  try { return (await rl.question(`  6-digit code texted to ${phone}: `)).trim(); } finally { rl.close(); }
}

/** An account: registered once (phone code + authenticator app), then signed in with its authenticator key. */
async function account(key, person, app, { open = true } = {}) {
  const s = new AppSession(URLS[app], URLS.auth);
  const saved = state.accounts[key];
  if (saved?.totpSecret) {
    if (!open) return null; // exists; sign-ins are rate limited per address (30 per 10 min), so only when needed
    await signIn(s, saved);
  } else {
    const phone = phoneFor(key, person.phoneSuffix);
    const email = env[`DEMO_EMAIL_${key.toUpperCase()}`] ?? person.email;
    const since = Date.now();
    try {
      await s.auth('POST', '/api/auth/register', { firstName: person.firstName, lastName: person.lastName, phone, email, terms: true, termsLanguage: 'en' });
    } catch (e) {
      if (e instanceof HttpError && [409, 422].includes(e.status)) {
        throw new Error(`${key}: ${email} / ${phone} is already registered but not in ${STATE_FILE} (its authenticator key is lost) — give this account other details (DEMO_EMAIL_${key.toUpperCase()}, DEMO_PHONE_${key.toUpperCase()}) or restore the file. ${e.message}`);
      }
      throw e;
    }
    await s.auth('POST', '/api/auth/register/verify', { code: await smsCode(phone, since) });
    const totp = await s.auth('POST', '/api/auth/register/totp');
    const created = await s.auth('POST', '/api/auth/register/totp/verify', { code: await freshTotp(totp.secret) });
    state.accounts[key] = { identifier: email, phone, name: `${person.firstName} ${person.lastName}`, role: person.role, userId: created.user.id, totpSecret: totp.secret, otpauthUri: totp.otpauthUri };
    save();
    saveQr(key, totp.qrCode);
    try {
      const codes = await s.auth('POST', '/api/auth/backup-codes');
      state.accounts[key].backupCodes = codes.codes ?? codes.items ?? codes;
      save();
    } catch { /* optional */ }
    log(`  + ${key}: registered ${email}`);
  }
  await s.openApp();
  return s;
}

async function signIn(s, a) {
  await s.auth('POST', '/api/auth/sign-in', { identifier: a.identifier });
  if (a.totpSecret) {
    for (let attempt = 0; ; attempt++) {
      try { await s.auth('POST', '/api/auth/sign-in/totp', { code: await freshTotp(a.totpSecret) }); return 'totp'; } catch (e) {
        if (attempt >= 1 || !(e instanceof HttpError)) throw e;
        await sleep(31_000); // the step was used by an earlier run moments ago (replay protection)
      }
    }
  }
  const used = new Set(a.usedBackupCodes ?? []);
  for (const code of (a.backupCodes ?? []).filter(c => !used.has(c))) {
    a.usedBackupCodes = [...used, code];
    used.add(code);
    save();
    try { await s.auth('POST', '/api/auth/sign-in/backup-code', { code }); return 'backup_code'; } catch (e) { if (!(e instanceof HttpError)) throw e; }
  }
  throw new Error(`${a.identifier}: no authenticator key and no unused backup code`);
}

/** Staff: an existing console account (the dry run's first admin; locally the dev seed's reviewer), never created here. */
async function staffSession() {
  const s = new AppSession(URLS.console, URLS.auth);
  const saved = state.accounts.staff ?? {};
  const identifier = env.DEMO_STAFF_IDENTIFIER ?? saved.identifier;
  if (!identifier) throw new Error('DEMO_STAFF_IDENTIFIER (+ DEMO_STAFF_TOTP_SECRET or DEMO_STAFF_BACKUP_CODES) is needed: the console account that approves the demo businesses');
  const same = saved.identifier === identifier;
  const a = {
    identifier,
    totpSecret: env.DEMO_STAFF_TOTP_SECRET ?? (same ? saved.totpSecret : undefined),
    // codes this script regenerated (local) win over the ones given: those are spent
    backupCodes: same && saved.backupCodes?.length ? saved.backupCodes : env.DEMO_STAFF_BACKUP_CODES?.split(',').map(c => c.trim()).filter(Boolean),
    usedBackupCodes: same ? saved.usedBackupCodes : [],
    role: 'console approver (decides applications, vets listings and licences)',
  };
  state.accounts.staff = a;
  save();
  const factor = await signIn(s, a);
  if (factor === 'backup_code' && LOCAL) {
    // single-use codes: locally, a fresh set for the next run (in this database the given ones are then replaced)
    const fresh = await s.auth('POST', '/api/auth/backup-codes');
    a.backupCodes = fresh.codes;
    a.usedBackupCodes = [];
    save();
  }
  await s.openApp();
  return s;
}

// ── a business, from nothing to approved and published ────────────────────────────────────────────────────────────
async function myBusinesses(owner) {
  const r = await owner.get('/api/v1/me/businesses');
  return r.items ?? r;
}

async function ensureMerchant(owner, staff, reviewer, plan, index) {
  const saved = state.merchants[plan.key];
  let id = saved?.id;
  if (!id) {
    id = (await myBusinesses(owner)).find(b => b.displayName === plan.name)?.id;
  }
  if (!id) {
    const created = await owner.post('/api/v1/merchants', { type: plan.type, province: market.province, businessTermsAccepted: true });
    id = created.merchantId;
    log(`  + ${plan.name}: application ${id}`);
  }
  state.merchants[plan.key] = { ...(saved ?? {}), id, name: plan.name, type: plan.type };
  save();
  const m = `/api/v1/merchants/${id}`;
  let ob = await owner.get(`${m}/onboarding`);
  const pool = await addresses();
  const a = pool[index % Math.max(pool.length, 1)];
  const address = a ? `${streetOf(a)}, ${market.city} ${market.province} ${postalOf(a) ?? ''}`.trim() : `${100 + index * 12} Main St, ${market.city} ${market.province}`;
  const ownerName = state.accounts.owner.name;

  if (ob.status === 'applicant') {
    await owner.put(`${m}/onboarding/business`, {
      displayName: plan.name, legalName: ownerName, structure: 'sole',
      legalDetails: { owner_legal_name: ownerName, sin_collected_by_stripe: true, address },
      principals: [], categoryIds: plan.categories, suggestedCategories: [],
      profile: {
        description: fill(plan.about), languages: ['en', 'fr'], yearsOperating: '3-5',
        ...(plan.type === 'seller' ? { pickupAddress: address, productCount: '1-20', inventorySources: ['manual'] } : { serviceArea: `${market.city} and nearby`, workLocations: ['customer'], teamSize: '2-5' }),
      },
    });
    ob = await owner.get(`${m}/onboarding`);
    await completeChecklist(owner, id, ob);
    ob = await owner.get(`${m}/onboarding`);
    const open = ob.checklist.filter(c => !['submitted', 'verified'].includes(c.status));
    if (open.length) {
      todo.push(`${plan.name}: finish ${open.map(c => c.key).join(', ')} in the Studio (Onboarding › Verification), then run this again`);
      return null;
    }
    await owner.post(`${m}/onboarding/submit`);
    ob = await owner.get(`${m}/onboarding`);
    log(`  ${plan.name}: submitted for review`);
  }
  if (ob.status === 'pending') {
    await approve(staff, reviewer, id, plan.name);
    ob = await owner.get(`${m}/onboarding`);
  }
  if (ob.status !== 'active') {
    todo.push(`${plan.name}: status ${ob.status} — open it in the console's verification queue`);
    return null;
  }
  if (ob.step !== 'done') await owner.put(`${m}/onboarding/step`, { step: 'done' }).catch(() => {});
  return id;
}

async function completeChecklist(owner, id, ob) {
  const m = `/api/v1/merchants/${id}`;
  for (const c of ob.checklist.filter(x => x.status === 'todo' || x.status === 'rejected')) {
    const done = body => owner.post(`${m}/verifications/${c.id}/complete`, body);
    try {
      switch (c.action) {
        case 'identity': await identity(owner, id); break;
        case 'number': await done({ reference: c.key === 'gst' ? '123456789 RT0001' : String(40000 + Math.floor(Math.random() * 9999)) }); break;
        case 'upload': {
          const doc = await owner.call('POST', `${m}/onboarding/documents`, { multipart: file(demoPdf(`${c.key} ${id}`), `${c.key}.pdf`, { purpose: 'verification' }) });
          await done({ documentId: doc.id });
          break;
        }
        case 'sign': await done({ choice: 'signed' }); break;
        case 'choose': await done({ choice: c.key === 'returns_policy' ? 'standard' : 'none' }); break;
        case 'slot': await done({ reference: new Date(Date.now() + 2 * 86_400_000).toISOString().replace(/T.*/, 'T17:00:00Z') }); break;
        default: await done({});
      }
    } catch (e) {
      if (!(e instanceof HttpError)) throw e;
      log(`  ! ${c.key}: ${e.message}`);
    }
  }
}

/** Owner identity (Stripe Identity): locally the fake's outcome page; elsewhere Stripe's hosted page, by a person. */
async function identity(owner, id) {
  const m = `/api/v1/merchants/${id}`;
  const people = await owner.get(`${m}/identity-checks`);
  for (const p of (people.items ?? []).filter(x => !['verified', 'processing', 'review'].includes(x.status ?? x.check?.status))) {
    const started = await owner.post(`${m}/identity-checks/${p.principalId}/session`, { delivery: 'self' });
    if (LOCAL) {
      const session = new URL(started.url, URLS.studio).pathname.split('/').pop();
      await owner.call('POST', `/api/v1/dev/identity-sessions/${session}`, { form: { outcome: 'verified' }, follow: false, expect: [303, 302, 200] });
    } else {
      todo.push(`${state.merchants[Object.keys(state.merchants).find(k => state.merchants[k].id === id)]?.name ?? id}: verify the owner's identity on Stripe's test page (pick "Verified"): ${started.url}`);
    }
  }
}

const file = (blob, name, extra = {}) => { const f = new FormData(); f.append('file', blob, name); for (const [k, v] of Object.entries(extra)) f.append(k, v); return f; };

/**
 * Approval in the console. Four-eyes when the api has it (a reviewer recommends, a DIFFERENT approver decides): the
 * demo reviewer posts the review, the staff account (approver) the decision. An api without the review step (404/405)
 * gets the single-step decision from the approver, as before.
 */
async function approve(staff, reviewer, id, name) {
  const base = `/api/v1/console/verification/applications/${id}`;
  const detail = await staff.get(base);
  for (const r of (detail.registryReviews ?? detail.reviews ?? []).filter(x => (x.state ?? x.reviewState) === 'open')) {
    await staff.post(`/api/v1/console/registry-reviews/${r.id}/decision`, { decision: 'approve' });
  }
  for (const r of (detail.identityReviews ?? []).filter(x => (x.status ?? x.state) === 'review')) {
    await staff.post(`${base}/identity-reviews/${r.id}/decision`, { decision: 'approve' });
  }
  const note = 'Demo business (fake data).';
  let reviewed = false;
  try {
    await (await reviewer()).post(`${base}/review`, { decision: 'approve', recommendation: 'approve', note });
    reviewed = true;
  } catch (e) {
    if (!(e instanceof HttpError) || ![404, 405].includes(e.status)) throw e; // no four-eyes step on this api yet
  }
  const decide = () => staff.post(`${base}/decision`, { decision: 'approve', note });
  try {
    await decide();
  } catch (e) {
    if (e instanceof HttpError && !reviewed && /review|four|same_person|second/i.test(e.code ?? '')) {
      throw new Error(`${name}: the api wants a two-person review this script doesn't know how to post (${e.message}) — update approve() in scripts/demo/demo-data.mjs`);
    }
    if (!(e instanceof HttpError) || e.code !== 'reviews_open') throw e;
    // the open registry reviews the detail didn't list by those names: the queue has them
    const q = await staff.get(`/api/v1/console/registry-reviews?merchantId=${id}`).catch(() => ({ items: [] }));
    for (const r of (q.items ?? []).filter(x => x.merchantId === id)) await staff.post(`/api/v1/console/registry-reviews/${r.id}/decision`, { decision: 'approve' });
    await decide();
  }
  log(`  ${name}: ${reviewed ? `reviewed by ${state.accounts.reviewer.identifier}, approved by ${state.accounts.staff.identifier}` : 'approved'} in the console`);
}

/**
 * The second console account (four-eyes review): a demo person registered like the others, then given the trust &
 * safety role by the approver (Console › Team › Invite — the account must exist first).
 */
async function reviewerSession(staff) {
  // registered on the consumer site: the console's BFF lets in staff only, which the account becomes below
  await account('reviewer', PLAN.staff.reviewer, 'consumer', { open: false });
  const a = state.accounts.reviewer;
  if (!a.granted) {
    const team = await staff.get('/api/v1/console/team');
    const members = team.members ?? team.items ?? [];
    const me = members.find(x => x.userId === a.userId || x.email === a.identifier);
    if (!(me?.roles ?? []).includes(PLAN.staff.reviewer.consoleRole)) {
      await staff.post('/api/v1/console/team/invite', { email: a.identifier, role: PLAN.staff.reviewer.consoleRole });
      log(`  + reviewer: ${a.identifier} invited to the console as ${PLAN.staff.reviewer.consoleRole}`);
    }
    a.granted = true;
    save();
  }
  const s = new AppSession(URLS.console, URLS.auth);
  await signIn(s, a);
  await s.openApp();
  return s;
}

// ── storefront, licence, listings ─────────────────────────────────────────────────────────────────────────────────
async function storefront(owner, id, plan) {
  const m = `/api/v1/merchants/${id}`;
  let s = await owner.get(`${m}/storefront`).catch(e => (e instanceof HttpError && e.status === 404 ? null : Promise.reject(e)));
  if (!s) s = await owner.post(`${m}/storefront`);
  if (!s.logo) {
    const doc = await owner.call('POST', `${m}/onboarding/documents`, { multipart: file(logoPicture(plan.name, plan.colour), 'logo.png', { purpose: 'logo' }) });
    s = await owner.patch(`${m}/storefront`, { logoDocumentId: doc.id });
  }
  await owner.patch(`${m}/storefront`, { tagline: fill(plan.tagline), brandColor: plan.colour, ctaLabel: plan.type === 'provider' ? 'book' : 'shop' })
    .catch(e => owner.patch(`${m}/storefront`, { tagline: fill(plan.tagline) }).catch(() => { log(`  ! ${plan.name} storefront: ${e.message}`); }));
  await owner.post(`${m}/storefront/publish`).catch(e => log(`  ! ${plan.name} publish: ${e.message}`));
  s = await owner.get(`${m}/storefront`);
  state.merchants[plan.key].slug = s.slug;
  save();
}

async function licence(owner, staff, id, plan) {
  if (!plan.ageRestricted) return;
  const m = `/api/v1/merchants/${id}`;
  const current = await owner.get(`${m}/restricted-licences`);
  const mine = (current.items ?? []).filter(l => l.ageClass === plan.ageRestricted);
  if (!mine.some(l => ['approved', 'pending'].includes(l.status))) {
    const expires = new Date(Date.now() + 365 * 86_400_000).toISOString().slice(0, 10);
    await owner.call('POST', `${m}/restricted-licences`, { multipart: file(demoPdf(`${plan.ageRestricted} licence ${plan.name}`), 'licence.pdf', { ageClass: plan.ageRestricted, licenceNumber: `DEMO-${Math.floor(100000 + Math.random() * 899999)}`, expiresOn: expires }) });
    log(`  ${plan.name}: ${plan.ageRestricted} licence sent`);
  }
  if (!(await owner.get(`${m}/restricted-licences`)).items?.some(l => l.status === 'pending')) return;
  const queue = await staff.get('/api/v1/console/vetting/licences?status=pending');
  for (const { licence: l } of (queue.items ?? []).filter(x => x.licence?.merchantId === id)) {
    await staff.post(`/api/v1/console/vetting/licences/${l.id}/decision`, { decision: 'approve', note: 'Demo licence (fake).' });
    log(`  ${plan.name}: ${l.ageClass} licence approved in the console`);
  }
}

async function listings(owner, id, plan) {
  const m = `/api/v1/merchants/${id}`;
  const existing = await allListings(owner, id);
  const bySku = new Map(existing.filter(l => l.sku).map(l => [l.sku, l]));
  state.merchants[plan.key].listings ??= {};
  for (const l of plan.listings) {
    const sku = SKU(plan.key, l.key);
    let found = bySku.get(sku) ?? existing.find(x => x.name === l.title);
    const editable = !found || ['draft', 'rejected', 'changes_requested'].includes(found.vetting);
    if (editable) {
      let body;
      if (l.kind === 'product') {
        const image = await owner.call('POST', `${m}/media`, { multipart: file(listingPicture(l.title, plan.name, plan.colour), `${l.key}.png`) });
        body = {
          title: l.title, brand: plan.name, categoryId: l.category, attributes: l.attributes ?? {}, priceCents: l.priceCents, stock: l.stock, sku,
          description: fill(l.description), bullets: l.bullets ?? [], imageIds: [image.id], identifierType: 'none',
          countryOfOrigin: 'CA', restrictedOk: true, bilingualOk: true, fulfilment: ['pooled', 'pickup'], handlingTime: 'same_day',
          returnsPolicy: PERISHABLE.test(l.category) ? 'final_sale' : 'standard_14', lowStockAt: 3,
        };
      } else {
        body = {
          name: l.title, categoryId: l.category, pricingMode: l.pricingMode ?? 'fixed', priceCents: l.priceCents,
          durationMin: l.durationMin, bufferMin: l.bufferMin ?? 0, included: l.included, instantBook: true, sku,
        };
      }
      const path = `${m}/${l.kind === 'product' ? 'products' : 'services'}`;
      found = found ? await owner.put(`${path}/${found.id}`, body) : await owner.post(path, body);
      log(`  + ${plan.name}: ${l.title}`);
    } else {
      // back to the plan's price and stock (a demo sale or an edit moved them)
      await owner.patch(`${m}/listings/${found.id}/price-stock`, { priceCents: l.priceCents, ...(l.kind === 'product' ? { stock: l.stock } : {}) }).catch(() => {});
    }
    state.merchants[plan.key].listings[l.key] = { id: found.id, kind: l.kind, title: l.title, ...(l.durationMin ? { durationMin: l.durationMin } : {}) };
    save();
    const row = (await allListings(owner, id)).find(x => x.id === found.id) ?? found;
    if (['draft', 'rejected', 'changes_requested'].includes(row.vetting)) await owner.post(`${m}/listings/${found.id}/submit`).catch(e => log(`  ! submit ${l.title}: ${e.message}`));
    else if (row.vetting === 'approved' && row.status === 'hidden') await owner.post(`${m}/listings/${found.id}/publish`).catch(e => log(`  ! publish ${l.title}: ${e.message}`));
  }
}

/** A provider's opening hours (the plan's, else weekdays 9–5 and Saturday 10–3) and the owner bookable. */
async function availability(owner, id, plan) {
  if (plan.type !== 'provider') return;
  const base = `/api/v1/merchants/${id}/availability`;
  const view = await owner.get(`${base}/hours`);
  const me = view.members.find(x => x.userId === state.accounts.owner.userId) ?? view.members[0];
  const days = plan.hours ?? { mon: [['09:00', '17:00']], tue: [['09:00', '17:00']], wed: [['09:00', '17:00']], thu: [['09:00', '19:00']], fri: [['09:00', '17:00']], sat: [['10:00', '15:00']], sun: [] };
  if (!Object.values(me.days).some(r => r.length)) {
    await owner.put(`${base}/hours`, { effectiveFrom: new Date().toISOString().slice(0, 10), members: [{ memberUserId: me.userId, days }] });
  }
  if (!me.bookable) await owner.put(`${base}/team/${me.userId}`, { bookable: true });
}

async function allListings(owner, id) {
  const r = await owner.get(`/api/v1/merchants/${id}/listings?limit=200`);
  return r.items ?? r.rows ?? [];
}

/** Trust & safety: what the automated checks hand to a person (an age-restricted listing always is) is approved. */
async function vet(staff) {
  const ids = new Set(Object.values(state.merchants).map(m => m.id));
  const names = new Set(Object.values(state.merchants).map(m => m.name));
  for (let n = 0; n < 3; n++) {
    const queue = await staff.get('/api/v1/console/vetting');
    const ours = (queue.items ?? []).filter(i => (ids.has(i.merchantId ?? i.businessId) || names.has(i.businessName)) && ['pending', 'held', 'flagged', 'review'].includes(i.state ?? i.status));
    for (const i of ours) {
      await staff.post(`/api/v1/console/vetting/${i.kind === 'dish' ? 'dishes' : 'listings'}/${i.id}/decision`, { decision: 'approve' }).catch(e => log(`  ! vetting ${i.title ?? i.id}: ${e.message}`));
    }
    if (ours.length) log(`  vetting: ${ours.length} listing(s) approved by trust & safety`);
    if (!ours.length && n > 0) return;
    await sleep(2000);
  }
}

// ── customers and their history ───────────────────────────────────────────────────────────────────────────────────
async function ensureAddress(c, index) {
  const book = await c.get('/api/v1/me/addresses');
  const items = book.items ?? book;
  if (items.length) return items[0];
  const pool = await addresses();
  const a = pool[(index + 3) % Math.max(pool.length, 1)];
  const street = env.DEMO_ADDRESS_STREET ?? (a && streetOf(a));
  const postal = env.DEMO_ADDRESS_POSTAL ?? (a && postalOf(a));
  if (!street || !postal) throw new Error('no address for the customers: set DEMO_ADDRESS_STREET and DEMO_ADDRESS_POSTAL (an address in the market)');
  return c.post('/api/v1/me/addresses', { label: 'Home', street, city: market.city, province: market.province, postal });
}

function listing(ref) {
  const [mk, lk] = ref.split('.');
  return state.merchants[mk]?.listings?.[lk];
}

async function placeOrder(c, order, n) {
  const doneKey = `order-${n}`;
  if (state.activity[doneKey]) return;
  const items = order.items.map(([ref, qty]) => [listing(ref), qty]).filter(([l]) => l);
  if (items.length !== order.items.length) return;
  // the cart holds only this order
  const cart = await c.get('/api/v1/cart');
  for (const g of cart.groups ?? []) for (const it of g.items ?? []) await c.del(`/api/v1/cart/items/${it.itemId}`);
  for (const [l, qty] of items) await c.post('/api/v1/cart/items', { offerId: l.id, qty });
  const address = await ensureAddress(c, n);
  const checkout = await c.get(`/api/v1/me/checkout?market=${encodeURIComponent(market.city)}`);
  const body = checkoutBody(checkout, address);
  const key = `demo-${state.accounts[order.customer].userId}-${doneKey}-${Date.now()}`;
  const opened = await c.post('/api/v1/me/checkouts', body, { headers: { 'Idempotency-Key': key } });
  await authorizePayments(opened);
  const placed = await c.post(`/api/v1/me/checkouts/${opened.checkoutId}/place`, {}, { headers: { 'Idempotency-Key': `${key}-place` } });
  state.activity[doneKey] = { orderId: placed.orderId, ref: placed.ref, customer: order.customer };
  save();
  log(`  order ${placed.ref}: ${order.customer} bought ${items.map(([l, q]) => `${q} × ${l.title}`).join(', ')}`);
}

function checkoutBody(checkout, address) {
  // the first delivery window still open (a pooled run), else direct courier
  const option = (checkout.options ?? []).find(o => o.kind === 'pooled' && (!o.orderBy || Date.parse(o.orderBy) > Date.now() + 120_000)) ?? (checkout.options ?? []).find(o => o.kind === 'direct') ?? {};
  return {
    kind: option.kind ?? 'pooled', ...(option.windowId ? { windowId: option.windowId } : {}),
    address: { addressId: address.id },
    substitution: 'similar', tip: { kind: 'none', value: 0 },
  };
}

/** The local fake gateway authorizes by itself; with Stripe test mode the test card confirms each PaymentIntent. */
async function authorizePayments(opened) {
  if (LOCAL || !env.DEMO_STRIPE_PUBLISHABLE_KEY) return;
  for (const i of opened.intents ?? []) {
    if (!i.clientSecret) continue;
    const id = i.clientSecret.split('_secret_')[0];
    const res = await fetch(`https://api.stripe.com/v1/payment_intents/${id}/confirm`, {
      method: 'POST',
      headers: { authorization: `Bearer ${env.DEMO_STRIPE_PUBLISHABLE_KEY}`, 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ client_secret: i.clientSecret, payment_method: 'pm_card_visa', return_url: URLS.consumer }),
    });
    if (!res.ok) throw new Error(`Stripe test-mode confirm: ${res.status} ${(await res.text()).slice(0, 200)}`);
  }
}

async function book(c, b, n) {
  const doneKey = `booking-${n}`;
  if (state.activity[doneKey]?.bookingId) return state.activity[doneKey];
  const [mk] = b.service.split('.');
  const svc = listing(b.service);
  const merchant = state.merchants[mk];
  if (!svc || !merchant?.slug) return null;
  const address = await ensureAddress(c, n);
  const slots = await freeSlots(c, merchant.slug, svc.id, b.daysAhead);
  if (!slots.length) { log(`  ! ${svc.title}: no free slot in the next two weeks (Studio › Availability)`); return null; }
  let booked, startsAt;
  for (const candidate of slots.slice(0, 4)) {
    startsAt = candidate;
    try { booked = await bookSlot(c, b, svc, merchant, address, startsAt, doneKey); break; } catch (e) {
      if (!(e instanceof HttpError) || e.status !== 409) throw e; // slot_taken / hold_expired: the next slot
    }
  }
  if (!booked) throw new Error(`${svc.title}: every slot tried was taken`);
  state.activity[doneKey] = { bookingId: booked.bookingId, ref: booked.ref, customer: b.customer, service: b.service, merchantId: merchant.id, startsAt };
  save();
  log(`  booking ${booked.ref}: ${b.customer} booked ${svc.title} for ${startsAt}`);
  return state.activity[doneKey];
}

async function bookSlot(c, b, svc, merchant, address, startsAt, doneKey) {
  const hold = await c.post('/api/v1/me/bookings/holds', { slug: merchant.slug, serviceId: svc.id, startsAt });
  const key = `demo-${state.accounts[b.customer].userId}-${doneKey}-${hold.holdId}`;
  const body = {
    holdId: hold.holdId, serviceId: svc.id, description: b.note ?? 'Demo booking (fake data).',
    addressLine: `${address.street}, ${address.city} ${address.province} ${address.postal}`, spot: 'Front door',
    accessNote: 'Ring the bell, please.', present: "Yes, I'll be there", contactPreference: 'Text message',
    urgency: 'This week', flexibility: '± 1 hour is fine', agreePolicies: true, agreeTerms: true,
  };
  const hours = Math.max(1, Math.round((svc.durationMin ?? 60) / 30) / 2);
  let checkout;
  try {
    checkout = await c.post('/api/v1/me/bookings/checkout', body, { headers: { 'Idempotency-Key': key } });
  } catch (e) {
    // a home service is booked by the hour (the wizard's hours step); appointments refuse the field
    if (!(e instanceof HttpError) || !(e.body?.errors ?? []).some(x => x.field === 'hours')) throw e;
    checkout = await c.post('/api/v1/me/bookings/checkout', { ...body, hours }, { headers: { 'Idempotency-Key': `${key}-h` } });
  }
  await authorizePayments({ intents: [checkout] });
  const booked = checkout.booking ?? await c.post(`/api/v1/me/bookings/holds/${hold.holdId}/confirm`, {}, { headers: { 'Idempotency-Key': `confirm-${hold.holdId}` } });
  return booked;
}

/** The provider's free slots from `daysAhead` days on (its public calendar), a few per day, mid-day first. */
async function freeSlots(c, slug, serviceId, daysAhead) {
  const from = new Date(Date.now() + daysAhead * 86_400_000).toISOString().slice(0, 10);
  const cal = await c.get(`/api/v1/public/providers/${encodeURIComponent(slug)}/slots?serviceId=${serviceId}&from=${from}&days=14`);
  const out = [];
  for (const d of cal.days ?? []) {
    const free = (d.slots ?? []).filter(x => x.free && Date.parse(x.startsAt) > Date.now() + 3 * 3_600_000).map(x => x.startsAt);
    // not all at the first hour of the day: a calendar that looks lived in
    const pivot = Math.min(free.length - 1, 2 + (Number(d.date.slice(-1)) % 3));
    out.push(...free.slice(pivot), ...free.slice(0, pivot));
    if (out.length >= 6) break;
  }
  return out;
}

/** The provider travels, checks in and completes; the customer signs off (the mobile app's call) and reviews it. */
async function completeAndReview(owner, c, made, b) {
  const jobs = `/api/v1/merchants/${made.merchantId}/jobs/${made.bookingId}`;
  if (!made.completed) {
    let job = await owner.get(jobs);
    for (const [from, to] of [['confirmed', 'en-route'], ['en_route', 'on-site'], ['on_site', 'complete']]) {
      if (job.state === from) job = await owner.post(`${jobs}/${to}`, to === 'complete' ? { photoMediaIds: [], report: 'Done as booked (demo).' } : {});
    }
    if (job.state === 'completed') await c.post(`/api/v1/me/bookings/${made.bookingId}/sign-off`);
    made.completed = true;
    save();
    log(`  ${made.ref}: completed and signed off`);
  }
  if (b.review && !made.reviewed) {
    await c.post('/api/v1/me/reviews', { kind: 'booking', id: made.bookingId, rating: b.review.rating, text: b.review.text, tags: b.review.tags ?? [] });
    made.reviewed = true;
    save();
    log(`  ${made.ref}: reviewed ${b.review.rating}★`);
  }
}

/** The shops pack the orders the plan marks `pack` (the rest stay "to pack" for the demo). */
async function pack(owner) {
  for (const [n, o] of PLAN.activity.orders.entries()) {
    const done = state.activity[`order-${n}`];
    if (!o.pack || !done || done.packed) continue;
    const merchants = [...new Set(o.items.map(([ref]) => state.merchants[ref.split('.')[0]]?.id).filter(Boolean))];
    for (const m of merchants) await owner.post(`/api/v1/merchants/${m}/orders/${done.orderId}/pack`).catch(e => log(`  ! pack ${done.ref}: ${e.message}`));
    done.packed = true;
    save();
    log(`  ${done.ref}: packed`);
  }
}

// ── reset: demo-tagged data off the marketplace ───────────────────────────────────────────────────────────────────
async function reset(owner, applicant) {
  step('reset: demo-tagged data off the marketplace');
  const plannedSkus = new Set(PLAN.merchants.flatMap(p => p.listings.map(l => SKU(p.key, l.key))));
  for (const who of [owner, applicant].filter(Boolean)) {
    for (const b of await myBusinesses(who)) {
      const id = b.id;
      const planned = Object.values(state.merchants).some(m => m.id === id);
      let items = [];
      try { items = await allListings(who, id); } catch { continue; }
      for (const l of items) {
        if (planned && plannedSkus.has(l.sku)) continue; // the plan's own listings stay (re-run puts price and stock back)
        await who.post(`/api/v1/merchants/${id}/listings/${l.id}/hide`).catch(() => {});
        log(`  hidden: ${b.displayName} › ${l.name}`);
      }
    }
  }
  if (env.DEMO_RESET_ALL === '1') {
    for (const mk of Object.keys(state.merchants)) {
      const m = state.merchants[mk];
      for (const l of await allListings(owner, m.id).catch(() => [])) await owner.post(`/api/v1/merchants/${m.id}/listings/${l.id}/hide`).catch(() => {});
      log(`  hidden: every listing of ${m.name}`);
    }
  }
}

// ── run ───────────────────────────────────────────────────────────────────────────────────────────────────────────
async function main() {
  const started = Date.now();
  log(`Demo marketplace — ${ENV} (${URLS.consumer})`);
  step('market');
  await loadMarket();
  state.market = market.id;
  save();

  step('accounts');
  const owner = await account('owner', PLAN.owners.owner, 'studio');
  await account('applicant', PLAN.owners.applicant, 'studio', { open: false });
  // staff sign in only when something waits for them (sign-ins are rate limited per address)
  let staffSigned;
  const signedStaff = () => (staffSigned ??= staffSession());
  const staff = { get: async (...a) => (await signedStaff()).get(...a), post: async (...a) => (await signedStaff()).post(...a) };
  let reviewerSigned;
  const reviewer = () => (reviewerSigned ??= reviewerSession(staff));
  log(`  owner ${state.accounts.owner.identifier} · applicant ${state.accounts.applicant.identifier}`);

  if (RESET) {
    await reset(owner, await account('applicant', PLAN.owners.applicant, 'studio'));
    if (env.DEMO_RESET_ALL === '1') { await finish(started); return; }
  }

  step('businesses');
  const plans = PLAN.merchants.filter(p => !ONLY || ONLY.includes(p.key));
  for (const [i, plan] of plans.entries()) {
    const id = await ensureMerchant(owner, staff, reviewer, plan, i);
    if (!id) continue;
    await storefront(owner, id, plan);
    await licence(owner, staff, id, plan);
    await listings(owner, id, plan);
    await availability(owner, id, plan);
  }
  step('listing vetting');
  const waiting = [];
  for (const p of plans) if (state.merchants[p.key]?.id) waiting.push(...(await allListings(owner, state.merchants[p.key].id)).filter(r => !['approved', 'draft'].includes(r.vetting)));
  if (waiting.length) await vet(staff);

  step('customers');
  const sessions = {};
  const customers = new Proxy({}, { get: (_, key) => (sessions[key] ??= account(key, PLAN.customers.find(c => c.key === key), 'consumer')) });
  for (const c of PLAN.customers) await account(c.key, c, 'consumer', { open: false });

  if (!RESET) {
    step('orders');
    for (const [n, o] of PLAN.activity.orders.entries()) {
      try { if (!state.activity[`order-${n}`]) await placeOrder(await customers[o.customer], o, n); } catch (e) { log(`  ! order ${n + 1}: ${e.message}`); }
    }
    step('bookings and reviews');
    for (const [n, b] of PLAN.activity.bookings.entries()) {
      try {
        const made = state.activity[`booking-${n}`] ?? await book(await customers[b.customer], b, n);
        if (made && b.complete && (!made.completed || (b.review && !made.reviewed))) await completeAndReview(owner, await customers[b.customer], made, b);
      } catch (e) { log(`  ! booking ${n + 1}: ${e.message}`); }
    }
    step('packing');
    await pack(owner);
  }
  step('check');
  await check(owner);
  await finish(started);
}

/** Every planned listing live; what isn't is said (and why, when the Studio says). */
async function check(owner) {
  for (const plan of PLAN.merchants.filter(p => !ONLY || ONLY.includes(p.key))) {
    const m = state.merchants[plan.key];
    if (!m?.id) { log(`  ! ${plan.name}: not created`); continue; }
    const ours = (await allListings(owner, m.id)).filter(r => r.sku?.startsWith(`DEMO-${plan.key.toUpperCase()}-`));
    const live = ours.filter(r => r.status === 'live');
    log(`  ${live.length === plan.listings.length ? '✓' : '!'} ${plan.name.padEnd(30)} ${String(live.length).padStart(2)}/${plan.listings.length} listings live · page /providers/${m.slug ?? '?'}`);
    for (const r of ours.filter(x => x.status !== 'live')) log(`      ${r.name}: vetting ${r.vetting}, ${r.status}${r.vettingFlags?.length ? ` (${r.vettingFlags.join(', ')})` : ''}`);
  }
}

async function finish(started) {
  step('done');
  const photos = await presenterPhotos();
  log(`  ${Object.keys(state.merchants).length} businesses · ${Object.values(state.merchants).reduce((n, m) => n + Object.keys(m.listings ?? {}).length, 0)} listings · ${Object.keys(state.activity).length} orders/bookings — ${Math.round((Date.now() - started) / 1000)} s`);
  log(`  accounts and their authenticator keys: ${STATE_FILE} (keep it; never commit or share it); QR codes beside it (-qr/)`);
  log(`  pictures for the listing you add live: ${photos}`);
  if (!QUIET) for (const [k, a] of Object.entries(state.accounts)) log(`    ${k.padEnd(10)} ${a.identifier}  — ${a.role ?? ''}`);
  if (todo.length) {
    log('\n  For a person to finish (then run this again):');
    for (const t of todo) log(`  - ${t}`);
    process.exitCode = 3;
  }
}

main().catch(e => { console.error(`\n✗ ${e.message}`); if (env.DEMO_DEBUG) console.error(e.stack); process.exitCode = 1; });
