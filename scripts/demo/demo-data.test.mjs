// node --test scripts/demo/demo-data.test.mjs (make demo-data-test) — the demo plan and the driver's helpers.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { inflateSync } from 'node:zlib';

import { CookieJar } from './lib/http.mjs';
import { listingPicture, logoPicture, demoPdf } from './lib/images.mjs';
import { totpAt } from './lib/totp.mjs';

const plan = JSON.parse(readFileSync(new URL('./data/marketplace.json', import.meta.url), 'utf8'));

test('the plan: 8–12 businesses, 3–8 listings each, unique keys, every category the business declares', () => {
  assert.ok(plan.merchants.length >= 8 && plan.merchants.length <= 12);
  assert.equal(new Set(plan.merchants.map(m => m.key)).size, plan.merchants.length);
  for (const m of plan.merchants) {
    assert.ok(m.listings.length >= 3 && m.listings.length <= 8, m.key);
    assert.equal(new Set(m.listings.map(l => l.key)).size, m.listings.length, m.key);
    for (const l of m.listings) {
      assert.ok(m.categories.includes(l.category), `${m.key}.${l.key}: ${l.category} is not one of the business's categories`);
      assert.equal(l.kind, m.type === 'provider' ? 'service' : 'product', `${m.key}.${l.key}`);
      assert.ok(l.priceCents > 0);
      if (l.kind === 'service') assert.ok(l.durationMin >= 15 && l.durationMin <= 720);
      else assert.ok(Number.isInteger(l.stock) && l.stock > 0);
    }
  }
  assert.ok(plan.merchants.some(m => m.ageRestricted), 'an age-restricted shop shows the ID check');
});

test('the plan: every order and booking names a listing and a customer that exist', () => {
  const listing = ref => { const [m, l] = ref.split('.'); return plan.merchants.find(x => x.key === m)?.listings.find(x => x.key === l); };
  const customers = new Set(plan.customers.map(c => c.key));
  for (const o of plan.activity.orders) {
    assert.ok(customers.has(o.customer), o.customer);
    for (const [ref] of o.items) assert.equal(listing(ref)?.kind, 'product', ref);
  }
  for (const b of plan.activity.bookings) {
    assert.ok(customers.has(b.customer), b.customer);
    assert.equal(listing(b.service)?.kind, 'service', b.service);
    if (b.review) assert.ok(b.complete, `${b.service}: only a completed booking can be reviewed`);
  }
});

test('the plan names no place, province or time zone (region-neutral rule: they come from the market)', () => {
  const text = JSON.stringify(plan);
  for (const word of ['Alberta', 'Calgary', 'Edmonton', 'Ontario', 'Toronto', 'Vancouver', 'Montréal', 'Québec', 'America/']) {
    assert.ok(!text.includes(word), `the plan says ${word}`);
  }
  assert.ok(text.includes('{city}'), 'the city is filled from the market');
});

test('the plan: the demo accounts are fake', () => {
  for (const p of [...Object.values(plan.owners), ...Object.values(plan.staff), ...plan.customers]) {
    assert.match(p.email, /@demo\.northline\.test$/);
    assert.match(p.lastName, /^Demo-/);
  }
});

test('cookies: one jar per host name, across ports (as browsers do), Domain cookies to subdomains, expiry removes', () => {
  const jar = new CookieJar();
  const headers = lines => ({ getSetCookie: () => lines });
  jar.store('http://localhost:9000/x', headers(['NL_AUTH=a; Path=/; HttpOnly']));
  jar.store('http://localhost:3100/x', headers(['NL_STUDIO=s; Path=/', 'XSRF-TOKEN=t; Path=/']));
  assert.equal(jar.header('http://localhost:3000/'), 'NL_AUTH=a; NL_STUDIO=s; XSRF-TOKEN=t');
  jar.store('https://auth.demo.example/x', headers(['wide=1; Domain=demo.example', 'narrow=2']));
  assert.equal(jar.header('https://studio.demo.example/'), 'wide=1');
  jar.store('http://localhost:3100/x', headers(['NL_STUDIO=; Max-Age=0']));
  assert.equal(jar.get('http://localhost:3100', 'NL_STUDIO'), undefined);
});

test('TOTP: RFC 6238 SHA-1 test vector', () => {
  // the RFC's key "12345678901234567890" in base32; T = 59 s → step 1 → 94287082 (8 digits) → 287082
  assert.equal(totpAt('GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ', 1), '287082');
});

test('pictures: 1000 × 1000 PNGs on white, different per item; logos 512 px; a PDF', async () => {
  const png = Buffer.from(await listingPicture('Country sourdough loaf', 'Hearth & Crumb Bakery', '#A0522D').arrayBuffer());
  assert.equal(png.subarray(1, 4).toString(), 'PNG');
  assert.equal(png.readUInt32BE(16), 1000);
  assert.equal(png.readUInt32BE(20), 1000);
  const raw = inflateSync(png.subarray(41, 41 + png.readUInt32BE(33)));
  assert.deepEqual([...raw.subarray(1, 4)], [255, 255, 255], 'the corner is white');
  const other = Buffer.from(await listingPicture('Classic baguette', 'Hearth & Crumb Bakery', '#A0522D').arrayBuffer());
  assert.notDeepEqual(png, other);
  const logo = Buffer.from(await logoPicture('Fade Room Barbers', '#37474F').arrayBuffer());
  assert.equal(logo.readUInt32BE(16), 512);
  assert.match(await demoPdf('licence').text(), /^%PDF-1\.4[\s\S]*%%EOF\n$/);
});
