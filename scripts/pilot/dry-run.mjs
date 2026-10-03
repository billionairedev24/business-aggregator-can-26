// Pilot onboarding dry run (S-120) — the driver. Run through scripts/pilot/dry-run.sh (make pilot-dry-run), which starts
// a throwaway database and the api (`local` profile: dev auth, fake Stripe Identity, fake Stripe Connect, fixture
// registries). Every business, person and email here is FAKE. Node 22, no dependencies.
//
// Environment: API (http://localhost:8120) · MARKET (region market id) · WEBHOOK_SECRET (the api's
// STRIPE_CONNECT_WEBHOOK_SECRET) · PSQL (a psql command line for the same database) · OUT (where to write the CSV).
import { execSync } from 'node:child_process';
import { createHmac, randomBytes } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { deflateSync } from 'node:zlib';

const API = process.env.API ?? 'http://localhost:8120';
const MARKET = process.env.MARKET;
const SECRET = process.env.WEBHOOK_SECRET;
const PSQL = process.env.PSQL;
const OUT = process.env.OUT ?? '.';
if (!MARKET || !SECRET || !PSQL) throw new Error('MARKET, WEBHOOK_SECRET and PSQL are required (run scripts/pilot/dry-run.sh)');

const STAFF = '01J9ZD3V00000000000000PNA1'; // Priya Natarajan, dev seed V191 (+ merchant success, V323)
const sql = q => execSync(`${PSQL}`, { input: q, encoding: 'utf8' }).trim();
const quote = s => `'${String(s).replace(/'/g, "''")}'`;
const [CITY, PROVINCE] = sql(`select city || '|' || province from region.regions where id = ${quote(MARKET)}`).split('|');
const log = (...a) => console.log(...a);
const sleep = ms => new Promise(r => setTimeout(r, ms));

// ── the cohort: 4 providers, 4 sellers, 4 kitchens (docs/runbooks/pilot-onboarding.md § The pilot cohort) ──────────
const PLAN = [
  ['provider', 'Dry Run Cleaning 01', 'service.cleaning-and-property.house-cleaning'],
  ['provider', 'Dry Run Cleaning 02', 'service.cleaning-and-property.house-cleaning'],
  ['provider', 'Dry Run Cleaning 03', 'service.cleaning-and-property.house-cleaning'],
  ['provider', 'Dry Run Cleaning 04', 'service.cleaning-and-property.house-cleaning'],
  ['seller', 'Dry Run Bakery 05', 'shop.food-and-grocery.bakery'],
  ['seller', 'Dry Run Bakery 06', 'shop.food-and-grocery.bakery'],
  ['seller', 'Dry Run Bakery 07', 'shop.food-and-grocery.bakery'],
  // S-117's finding: an address that names no market city — the business must still end up in the market
  ['seller', 'Dry Run Bakery 08', 'shop.food-and-grocery.bakery', 'RR 2, Site 4, Box 9'],
  ['kitchen', 'Dry Run Kitchen 09', 'food.format.restaurant-dine-in-and-takeout'],
  ['kitchen', 'Dry Run Kitchen 10', 'food.format.restaurant-dine-in-and-takeout'],
  ['kitchen', 'Dry Run Kitchen 11', 'food.format.restaurant-dine-in-and-takeout'],
  ['kitchen', 'Dry Run Kitchen 12', 'food.format.restaurant-dine-in-and-takeout'],
];

// ── HTTP ──────────────────────────────────────────────────────────────────────────────────────────────────────────
async function call(method, path, { user, role, body, form, multipart, raw, headers = {}, expect = [200, 201, 204] } = {}) {
  const h = { ...headers };
  if (user) h['X-Dev-User'] = user;
  if (role) h['X-Console-Role'] = role;
  let payload;
  if (multipart) payload = multipart;
  else if (form) { h['content-type'] = 'application/x-www-form-urlencoded'; payload = new URLSearchParams(form).toString(); }
  else if (raw !== undefined) payload = raw;
  else if (body !== undefined) { h['content-type'] = 'application/json'; payload = JSON.stringify(body); }
  const res = await fetch(API + path, { method, headers: h, body: payload, redirect: 'manual' });
  const text = await res.text();
  let json; try { json = text ? JSON.parse(text) : undefined; } catch { json = text; }
  if (!expect.includes(res.status)) throw new Error(`${method} ${path} → ${res.status}: ${text.slice(0, 600)}`);
  return json;
}
const staff = (method, path, opts = {}) => call(method, path, { user: STAFF, role: 'merchant_success', ...opts });
const agent = (method, path, opts = {}) => call(method, path, { user: STAFF, role: 'trust_safety', ...opts });

// ── fake files ────────────────────────────────────────────────────────────────────────────────────────────────────
const PDF = new Blob(['%PDF-1.4\n% dry run — not a real document\n'], { type: 'application/pdf' });
const JPEG = new Blob([Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0, 16, 0x4a, 0x46, 0x49, 0x46, 0, 1, 0xff, 0xd9])], { type: 'image/jpeg' });
/** A 1000 × 1000 white PNG (listing images: ≥ 1000 px, main image on white). */
function png(size = 1000) {
  const crcTable = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = b => { let c = 0xffffffff; for (const x of b) c = crcTable[(c ^ x) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const chunk = (type, data) => { const len = Buffer.alloc(4); len.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([len, td, c]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4); ihdr[8] = 8; ihdr[9] = 2;
  const row = Buffer.concat([Buffer.from([0]), Buffer.alloc(size * 3, 0xff)]);
  const raw = Buffer.concat(Array.from({ length: size }, () => row));
  return new Blob([Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0))])], { type: 'image/png' });
}
const PHOTO = png();
const file = (blob, name, extra = {}) => { const f = new FormData(); f.append('file', blob, name); for (const [k, v] of Object.entries(extra)) f.append(k, v); return f; };

// ── steps ─────────────────────────────────────────────────────────────────────────────────────────────────────────
function fakeOwner(i) {
  const id = `01JDRYRVN0000000000000PA${String(i).padStart(2, '0')}`;
  const first = 'Dryrun', last = `Owner${String(i).padStart(2, '0')}`;
  sql(`insert into identity.users (id, email, display_name, first_name, last_name, locale, mfa_primary, status)
       values (${quote(id)}, ${quote(`owner${i}@dry-run.example.test`)}, ${quote(`${first} ${last}`)}, ${quote(first)}, ${quote(last)}, 'en-CA', 'passkey', 'active')
       on conflict (id) do nothing`);
  return { id, name: `${first} ${last}` };
}

async function completeChecklist(m, owner) {
  const ob = await call('GET', `/api/v1/merchants/${m}/onboarding`, { user: owner.id });
  for (const c of ob.checklist.filter(x => x.status === 'todo' || x.status === 'rejected')) {
    const done = body => call('POST', `/api/v1/merchants/${m}/verifications/${c.id}/complete`, { user: owner.id, body });
    switch (c.action) {
      case 'identity': {
        const people = await call('GET', `/api/v1/merchants/${m}/identity-checks`, { user: owner.id });
        for (const p of people.items.filter(x => x.status !== 'verified')) {
          await call('POST', `/api/v1/merchants/${m}/identity-checks/${p.principalId}/session`, { user: owner.id, body: { delivery: 'email', email: `id-${m.toLowerCase()}@dry-run.example.test` } });
          const session = sql(`select stripe_session from merchants.owner_identity_checks where merchant_id = ${quote(m)} and principal_id = ${quote(p.principalId)}`);
          await call('POST', `/api/v1/dev/identity-sessions/${session}`, { form: { outcome: 'verified' }, expect: [303] });
        }
        break;
      }
      case 'number': await done({ reference: c.key === 'gst' ? '123456789 RT0001' : '44812' }); break;
      case 'upload': {
        const doc = await call('POST', `/api/v1/merchants/${m}/onboarding/documents`, { user: owner.id, multipart: file(PDF, 'evidence.pdf', { purpose: 'verification' }) });
        await done({ documentId: doc.id });
        break;
      }
      case 'sign': await done({ choice: 'signed' }); break;
      case 'choose': await done({ choice: c.key === 'returns_policy' ? 'standard' : c.key === 'aglc' ? 'not_applicable' : 'none' }); break;
      case 'slot': await done({ reference: new Date(Date.now() + 2 * 86_400_000).toISOString().replace(/T.*/, 'T17:00:00Z') }); break;
      default: await done({});
    }
  }
}

/** Stripe Connect through the fake gateway, then Stripe's account.updated as a signed webhook (charges + payouts on). */
async function connectStripe(m, owner) {
  await call('POST', `/api/v1/merchants/${m}/compliance/stripe-links`, { user: owner.id, body: { kind: 'update' } });
  const account = sql(`select stripe_account_id from merchants.merchants where id = ${quote(m)}`);
  const created = Math.floor(Date.now() / 1000);
  const event = JSON.stringify({
    id: `evt_dryrun_${randomBytes(8).toString('hex')}`, object: 'event', type: 'account.updated', created, livemode: false, api_version: '2026-08-26.dahlia',
    account, data: { object: { id: account, object: 'account', charges_enabled: true, payouts_enabled: true, details_submitted: true,
      metadata: { northline_merchant_id: m }, requirements: { currently_due: [], past_due: [], eventually_due: [], disabled_reason: null } } },
  });
  const signature = createHmac('sha256', SECRET).update(`${created}.${event}`).digest('hex');
  await call('POST', '/api/v1/webhooks/stripe/connect', { raw: event, headers: { 'content-type': 'application/json', 'Stripe-Signature': `t=${created},v1=${signature}` } });
  return account;
}

async function addListing(b, m, owner) {
  if (b.type === 'provider') {
    const s = await call('POST', `/api/v1/merchants/${m}/services`, { user: owner.id, body: { name: `${b.name} · standard clean`, categoryId: b.category, pricingMode: 'fixed', priceCents: 12000, durationMin: 120, bufferMin: 15, included: 'Kitchen, bathrooms, floors', instantBook: true } });
    await call('POST', `/api/v1/merchants/${m}/listings/${s.id}/submit`, { user: owner.id });
    return { kind: 'service', id: s.id, name: `${b.name} · standard clean` };
  }
  if (b.type === 'seller') {
    const image = await call('POST', `/api/v1/merchants/${m}/media`, { user: owner.id, multipart: file(PHOTO, 'loaf.png') });
    const p = await call('POST', `/api/v1/merchants/${m}/products`, { user: owner.id, body: {
      title: `${b.name} sourdough loaf`, categoryId: b.category, priceCents: 900, stock: 25, description: 'Fake product for the pilot dry run.',
      imageIds: [image.id], countryOfOrigin: 'CA', restrictedOk: true, bilingualOk: true, fulfilment: ['pooled'], handlingTime: 'same_day', returnsPolicy: 'standard_14',
    } });
    await call('POST', `/api/v1/merchants/${m}/listings/${p.id}/submit`, { user: owner.id });
    return { kind: 'product', id: p.id, name: `${b.name} sourdough loaf` };
  }
  // the draft menu is created when the application is submitted (food listens to merchant.submitted, after commit)
  let menu;
  for (let n = 0; n < 30 && !menu?.sections?.length; n++) {
    menu = (await call('GET', `/api/v1/merchants/${m}/menus`, { user: owner.id })).items[0];
    if (!menu?.sections?.length) await sleep(500);
  }
  const item = await call('POST', `/api/v1/merchants/${m}/menu-items`, { user: owner.id, body: { menuId: menu.id, sectionId: menu.sections[1]?.id ?? menu.sections[0].id, name: `${b.name} dumplings`, description: 'Fake dish for the pilot dry run.', priceCents: 1500, prepAddMin: 0, allergens: ['wheat', 'eggs'], modifierGroupIds: [] } });
  await call('POST', `/api/v1/merchants/${m}/menu-items/${item.id}/photo`, { user: owner.id, multipart: file(PHOTO, 'dish.png') });
  return { kind: 'dish', id: item.id, name: `${b.name} dumplings`, menuId: menu.id };
}

async function kitchenVisit(pilotId) {
  const at = new Date(Date.now() + 86_400_000).toISOString().replace(/\.\d+Z$/, 'Z');
  const d = await staff('POST', `/api/v1/console/pilot/${pilotId}/kitchen-visits`, { body: { at, inspectorId: STAFF } });
  const visit = d.visits.find(v => v.status === 'scheduled');
  await staff('POST', `/api/v1/console/pilot/${pilotId}/kitchen-visits/${visit.id}/photos`, { multipart: file(JPEG, 'walk-in.jpg') });
  await staff('POST', `/api/v1/console/pilot/${pilotId}/kitchen-visits/${visit.id}/outcome`, { body: { outcome: 'passed', checklist: Object.fromEntries(d.visitItems.map(i => [i, 'pass'])) } });
}

async function clearReviews(m) {
  for (const id of sql(`select id from merchants.registry_checks where merchant_id = ${quote(m)} and review_state = 'open'`).split('\n').filter(Boolean)) {
    await agent('POST', `/api/v1/console/registry-reviews/${id}/decision`, { body: { decision: 'approve' } });
  }
  for (const id of sql(`select id from merchants.owner_identity_checks where merchant_id = ${quote(m)} and status = 'review'`).split('\n').filter(Boolean)) {
    await agent('POST', `/api/v1/console/verification/applications/${m}/identity-reviews/${id}/decision`, { body: { decision: 'approve' } });
  }
}

/** Trust & safety's listing vetting: what the automated checks flagged for a human is approved (the dry run's data is fake). */
async function vet() {
  const queue = await agent('GET', '/api/v1/console/vetting');
  const ours = queue.items.filter(i => cohort.some(b => b.name === i.businessName) && ['pending', 'held'].includes(i.state));
  for (const i of ours) {
    log(`  vetting: ${i.businessName} · ${i.kind} flagged ${JSON.stringify(i.flags ?? [])} → approved by trust & safety`);
    await agent('POST', `/api/v1/console/vetting/${i.kind === 'dish' ? 'dishes' : 'listings'}/${i.id}/decision`, { body: { decision: 'approve' } });
  }
  return ours.length;
}

/** What a customer sees: the business is in a market (its city) and its listing is on the public pages. */
async function publicCheck(b) {
  try { return await visible(b); } catch (e) { return String(e.message).slice(0, 160); }
}
async function visible(b) {
  const city = sql(`select coalesce(city, '') from merchants.merchants where id = ${quote(b.merchantId)}`);
  if (!city) return 'no market';
  if (b.listing.kind === 'product') {
    const product = sql(`select product_id from catalogue.offers where id = ${quote(b.listing.id)}`);
    const page = await call('GET', `/api/v1/public/shop/products/${product}?market=${encodeURIComponent(city)}`);
    return JSON.stringify(page).includes(b.merchantId) ? 'ok' : `product page in ${city} lists no offer from it`;
  }
  if (b.listing.kind === 'dish') {
    const list = await call('GET', `/api/v1/public/kitchens?city=${encodeURIComponent(city)}`);
    if (!JSON.stringify(list).includes(b.name)) return `not in ${city}'s kitchens`;
    const slug = sql(`select slug from merchants.storefronts where merchant_id = ${quote(b.merchantId)}`);
    const menu = await call('GET', `/api/v1/public/kitchens/${slug}`);
    return JSON.stringify(menu).includes(b.listing.name) ? 'ok' : 'dish not on the public menu';
  }
  const slug = sql(`select slug from merchants.storefronts where merchant_id = ${quote(b.merchantId)}`);
  const page = await call('GET', `/api/v1/public/providers/${slug}?lang=en`);
  return JSON.stringify(page).includes(b.listing.name) ? 'ok' : 'service not on the public provider page';
}

// ── run ───────────────────────────────────────────────────────────────────────────────────────────────────────────
const started = Date.now();
log(`Pilot market ${MARKET} (${CITY}, ${PROVINCE}); ${PLAN.length} fake businesses.`);
const cohort = [];
for (const [i, [type, name, category, address]] of PLAN.entries()) {
  const b = { type, name, category, address: address ?? `${100 + i} Main St, ${CITY} ${PROVINCE}` };
  const invited = await staff('POST', '/api/v1/console/pilot/invites', { body: { marketId: MARKET, businessType: type, label: name, email: `pilot${i + 1}@dry-run.example.test`, language: i % 3 === 2 ? 'fr' : 'en', ownerId: STAFF } });
  b.pilotId = invited.detail.row.id;
  const token = invited.link.split('/').pop();
  const owner = fakeOwner(i + 1);
  const preview = await call('GET', `/api/v1/pilot-invites/${token}`, { user: owner.id });
  const created = await call('POST', '/api/v1/merchants', { user: owner.id, body: { type: preview.businessType, province: preview.province, pilotInvite: token, businessTermsAccepted: true } });
  b.merchantId = created.merchantId;
  await call('PUT', `/api/v1/merchants/${b.merchantId}/onboarding/business`, { user: owner.id, body: {
    displayName: name, legalName: owner.name, structure: 'sole',
    legalDetails: { owner_legal_name: owner.name, sin_collected_by_stripe: true, address: b.address },
    categoryIds: [category], profile: { description: 'Fake business for the pilot dry run.' },
  } });
  await completeChecklist(b.merchantId, owner);
  b.account = await connectStripe(b.merchantId, owner);
  await call('POST', `/api/v1/merchants/${b.merchantId}/onboarding/submit`, { user: owner.id });
  b.listing = await addListing(b, b.merchantId, owner);
  if (type === 'kitchen') {
    // the region requires a passed visit: approving first is refused
    await agent('POST', `/api/v1/console/verification/applications/${b.merchantId}/decision`, { body: { decision: 'approve' }, expect: [409] }).catch(() => {});
    await kitchenVisit(b.pilotId);
  }
  await clearReviews(b.merchantId);
  await agent('POST', `/api/v1/console/verification/applications/${b.merchantId}/decision`, { body: { decision: 'approve' } });
  if (type === 'kitchen') {
    await call('POST', `/api/v1/merchants/${b.merchantId}/menus/${b.listing.menuId}/publish`, { user: owner.id });
    // Kitchen › Hours: without fulfilment settings the market's kitchen list leaves the kitchen out
    await call('PUT', `/api/v1/merchants/${b.merchantId}/kitchen/fulfilment`, { user: owner.id, body: { courier: true, pickup: true, mealKits: false, scheduled: false, scheduledDays: 7, groupOrders: false, groupMax: 10, radiusKm: 10 } });
    await call('PUT', `/api/v1/merchants/${b.merchantId}/kitchen/hours`, { user: owner.id, body: { days: [1, 2, 3, 4, 5, 6, 7].map(weekday => ({ weekday, ranges: [['11:00', '21:00']] })) } });
  }
  await call('POST', `/api/v1/merchants/${b.merchantId}/storefront/publish`, { user: owner.id });
  b.owner = owner;
  cohort.push(b);
  log(`  ${String(i + 1).padStart(2)} ${type.padEnd(8)} ${name.padEnd(22)} invited → approved → page published`);
}

// Vetting, Stripe's webhook and the approval's listeners run after commit: wait for the board to settle, vetting what
// the automated checks hand to a human on the way.
let board;
for (let n = 0; n < 90; n++) {
  board = await staff('GET', `/api/v1/console/pilot?market=${encodeURIComponent(MARKET)}`);
  if (board.items.filter(r => cohort.some(b => b.pilotId === r.id)).every(r => r.stage === 'live')) break;
  if (n % 5 === 4) await vet();
  await sleep(1000);
}
const rows = board.items.filter(r => cohort.some(b => b.pilotId === r.id));
const checks = [];
for (const b of cohort) checks.push([b, await publicCheck(b)]);

const pad = (s, n) => String(s ?? '').slice(0, n).padEnd(n);
log('\nPilot board — ' + MARKET);
log(`${pad('Business', 24)}${pad('Type', 9)}${pad('City', 12)}${pad('Stage', 17)}${pad('Listings', 9)}${pad('Public', 10)}Next`);
for (const r of rows) {
  const b = cohort.find(x => x.pilotId === r.id);
  const pub = checks.find(([x]) => x === b)[1];
  log(`${pad(r.businessName, 24)}${pad(r.businessType, 9)}${pad(r.city ?? '—', 12)}${pad(r.stage, 17)}${pad(`${r.listingsLive}/${r.listings}`, 9)}${pad(pub, 10)}${r.next ? `${r.next.key}: ${r.next.action ?? ''} (${r.next.owner ?? ''})` : '—'}`);
}
log(`Stages: ${Object.entries(board.stages).filter(([, n]) => n).map(([k, n]) => `${k} ${n}`).join(' · ')}`);
const csv = await (await fetch(`${API}/api/v1/console/pilot/export?market=${encodeURIComponent(MARKET)}`, { headers: { 'X-Dev-User': STAFF } })).text();
writeFileSync(`${OUT}/pilot-board.csv`, csv);

const live = rows.filter(r => r.stage === 'live').length;
const noMarket = rows.filter(r => !r.city).length;
const hidden = checks.filter(([, c]) => c !== 'ok');
log(`\n${live} of ${cohort.length} pilot businesses live on ${CITY}; ${noMarket} without a market; ${cohort.length - hidden.length} publicly visible. ${Math.round((Date.now() - started) / 1000)} s. CSV: ${OUT}/pilot-board.csv`);
for (const [b, why] of hidden) log(`  NOT VISIBLE: ${b.name}: ${why}`);
process.exitCode = live === cohort.length && noMarket === 0 && hidden.length === 0 && cohort.length >= 10 ? 0 : 1;
