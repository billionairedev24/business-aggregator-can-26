// node --test scripts/photos/*.test.mjs — make photos-fetch against a fake Openverse (no network).
import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { licenceName, queriesFor, rank, run, searchUrl } from './fetch.mjs';

const JPEG = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 0xff, 0xd9]);
const result = (id, over = {}) => ({
  id, title: `Photo ${id}`, url: `https://images.example.org/${id}.jpg`, foreign_landing_url: `https://www.flickr.com/photos/x/${id}`,
  creator: `Creator ${id}`, creator_url: `https://www.flickr.com/people/${id}`, license: 'cc0', license_version: '1.0',
  license_url: 'https://creativecommons.org/publicdomain/zero/1.0/', source: 'flickr', category: 'photograph', filetype: 'jpg',
  width: 2400, height: 1600, tags: [{ name: 'bread' }], ...over,
});

/** A fake Openverse: `pages` maps a query to its results; `throttle` answers 429 that many times first. */
function openverse({ pages, throttle = 0, imageStatus = 200 }) {
  const calls = [];
  let limited = throttle;
  const fetchImpl = async (url, init) => {
    calls.push({ url, init });
    const headers = new Map([['content-type', 'image/jpeg']]);
    if (url.startsWith('https://api.openverse.org/')) {
      if (limited-- > 0) return { status: 429, ok: false, headers: new Map([['retry-after', '2']]), json: async () => ({}) };
      const q = new URL(url).searchParams;
      const key = `${q.get('q')}|${q.get('license') ?? q.get('license_type')}`;
      return { status: 200, ok: true, headers, json: async () => ({ results: pages[key] ?? [] }) };
    }
    return { status: imageStatus, ok: imageStatus === 200, headers, arrayBuffer: async () => JPEG };
  };
  return { fetchImpl, calls };
}

const quiet = () => {};
const noShrink = () => {};
const base = { log: quiet, shrink: noShrink, delayMs: 0, photos: {} };

test('asks for CC0 and public-domain images, landscape for heroes and banners, without category or size filters', () => {
  const u = new URL(searchUrl('sourdough bread loaf', 'category/shop.food-and-grocery.bakery', false));
  assert.equal(u.origin + u.pathname, 'https://api.openverse.org/v1/images/');
  assert.equal(u.searchParams.get('license'), 'cc0,pdm');
  assert.equal(u.searchParams.get('category'), null);
  assert.equal(u.searchParams.get('size'), null);
  assert.equal(u.searchParams.get('aspect_ratio'), null);
  assert.equal(new URL(searchUrl('x', 'hero/home-main', false)).searchParams.get('aspect_ratio'), 'wide');
  const c = new URL(searchUrl('x', 'banner/shop', true));
  assert.equal(c.searchParams.get('license_type'), 'commercial');
  assert.equal(c.searchParams.get('license'), null);
});

test('keeps wide-enough photographs under accepted licences, photo sites first', () => {
  const ranked = rank([
    result('small', { width: 900 }),
    result('drawing', { title: 'Bread vector illustration' }),
    result('nc', { license: 'by-nc' }),
    result('nd', { license: 'by-nd' }),
    result('flickr', { width: 3000 }),
    result('stock', { source: 'stocksnap', width: 1800 }),
    result('other', { source: 'somewhere', width: 5000 }),
  ], 'category/x.y');
  assert.deepEqual(ranked.map(r => r.id), ['stock', 'flickr', 'other']);
  assert.deepEqual(rank([result('tall', { width: 1700, height: 2600 })], 'hero/home-main'), []);
  assert.deepEqual(rank([result('narrow', { width: 1200, height: 800 })], 'hero/home-main'), [], 'wide slots need 1400 px');
  assert.equal(licenceName('cc0'), 'CC0 1.0');
  assert.equal(licenceName('by-sa', '2.0'), 'CC BY-SA 2.0');
});

test('downloads the top three per slot with their attribution, skips slots with a photo, and resumes', async () => {
  const outDir = mkdtempSync(join(tmpdir(), 'photos-fetch-'));
  const slots = [
    { slot: 'category/shop.food-and-grocery.bakery', query: 'bakery' },
    { slot: 'hero/home-main', query: 'mechanic' },
    { slot: 'banner/shop', query: 'groceries' },
  ];
  const fake = openverse({ pages: { 'bakery|cc0,pdm': ['a', 'b', 'c', 'd'].map(id => result(id)), 'mechanic|cc0,pdm': [] } });
  const photos = { 'banner/shop': { file: 'x' } };
  const summary = await run({ ...base, ...fake, slots, photos, outDir });
  assert.deepEqual([summary.filled, summary.empty], [1, 1]);
  assert.ok(!fake.calls.some(c => c.url.includes('q=groceries')), 'a slot with a photo is not searched');
  const state = JSON.parse(readFileSync(join(outDir, 'candidates.json'), 'utf8'));
  const bakery = state.slots['category/shop.food-and-grocery.bakery'];
  assert.equal(bakery.status, 'done');
  assert.deepEqual(bakery.candidates.map(c => c.n), [1, 2, 3]);
  assert.equal(bakery.candidates[0].licence, 'CC0 1.0');
  assert.equal(bakery.candidates[0].creator, 'Creator a');
  assert.equal(bakery.candidates[0].landingUrl, 'https://www.flickr.com/photos/x/a');
  assert.ok(existsSync(join(outDir, 'category/shop.food-and-grocery.bakery/3.jpg')));
  assert.ok(!existsSync(join(outDir, 'category/shop.food-and-grocery.bakery/4.jpg')));
  assert.equal(state.slots['hero/home-main'].status, 'empty');

  // a second run searches nothing again
  const again = openverse({ pages: {} });
  await run({ ...base, ...again, slots, photos, outDir });
  assert.deepEqual(again.calls.map(c => c.url), []);
  // --commercial searches the empty ones again with CC BY / BY-SA allowed, and keeps the attribution
  const wider = openverse({ pages: { 'mechanic|commercial': [result('m', { license: 'by', license_version: '2.0', license_url: 'https://creativecommons.org/licenses/by/2.0/' })] } });
  await run({ ...base, ...wider, slots, photos, outDir, commercial: true });
  const after = JSON.parse(readFileSync(join(outDir, 'candidates.json'), 'utf8')).slots['hero/home-main'];
  assert.equal(after.candidates[0].licence, 'CC BY 2.0');
  assert.equal(after.candidates[0].licenceUrl, 'https://creativecommons.org/licenses/by/2.0/');
});

test('backs off on 429 and stops, resumable, when it keeps being rate-limited', async () => {
  const outDir = mkdtempSync(join(tmpdir(), 'photos-fetch-'));
  const waits = [];
  const slots = [{ slot: 'category/a.b', query: 'a' }, { slot: 'category/a.c', query: 'c' }];
  const ok = openverse({ pages: { 'a|cc0,pdm': [result('x')] }, throttle: 2 });
  const s1 = await run({ ...base, ...ok, slots: slots.slice(0, 1), outDir, sleep: async ms => { waits.push(ms); } });
  assert.equal(s1.filled, 1);
  assert.ok(waits.includes(2000), 'Retry-After is honoured');
  const blocked = openverse({ pages: {}, throttle: 99 });
  const s2 = await run({ ...base, ...blocked, slots, outDir, sleep: async () => {} });
  assert.match(s2.stopped, /rate-limiting/);
  assert.equal(s2.total.left, 1, 'the slot it stopped on is searched next time');
});

test('a candidate that fails to download is skipped', async () => {
  const outDir = mkdtempSync(join(tmpdir(), 'photos-fetch-'));
  const fake = openverse({ pages: { 'a|cc0,pdm': [result('x')] }, imageStatus: 404 });
  const s = await run({ ...base, ...fake, slots: [{ slot: 'category/a.b', query: 'a' }], outDir, sleep: async () => {} });
  assert.deepEqual([s.filled, s.empty], [0, 1]);
});

test('tries a shorter query and the fallback slot\'s words when a slot finds too few', async () => {
  const slots = [
    { slot: 'category/s.g', query: 'Personal care' },
    { slot: 'category/s.g.nails', query: 'Nail technician salon', fallback: 'category/s.g' },
  ];
  assert.deepEqual(queriesFor(slots[1], slots), ['Nail technician salon', 'Nail technician', 'Personal care']);
  const outDir = mkdtempSync(join(tmpdir(), 'photos-fetch-'));
  const fake = openverse({ pages: { 'Personal care|cc0,pdm': [result('p')] } });
  const s = await run({ ...base, ...fake, slots: slots.slice(1).concat(slots[0]), outDir, sleep: async () => {} });
  assert.equal(s.filled, 2);
  // an empty slot from an older fetcher version is searched again
  const old = mkdtempSync(join(tmpdir(), 'photos-fetch-'));
  const first = openverse({ pages: {} });
  await run({ ...base, ...first, slots: [{ slot: 'category/a.b', query: 'a' }], outDir: old, sleep: async () => {} });
  const state = JSON.parse(readFileSync(join(old, 'candidates.json'), 'utf8'));
  delete state.slots['category/a.b'].version;
  (await import('node:fs')).writeFileSync(join(old, 'candidates.json'), JSON.stringify(state));
  const second = openverse({ pages: { 'a|cc0,pdm': [result('z')] } });
  const r = await run({ ...base, ...second, slots: [{ slot: 'category/a.b', query: 'a' }], outDir: old, sleep: async () => {} });
  assert.equal(r.filled, 1);
});

test('a refused search (401) is not recorded as empty, and stops the run', async () => {
  const outDir = mkdtempSync(join(tmpdir(), 'photos-fetch-'));
  const refused = { fetchImpl: async () => ({ status: 401, ok: false, headers: new Map(), json: async () => ({}) }), calls: [] };
  const s = await run({ ...base, ...refused, slots: [{ slot: 'category/a.b', query: 'a' }, { slot: 'category/a.c', query: 'c' }], outDir, sleep: async () => {} });
  assert.match(s.stopped, /401/);
  const state = JSON.parse(readFileSync(join(outDir, 'candidates.json'), 'utf8'));
  assert.equal(state.slots['category/a.b'], undefined);
  assert.equal(new URL(searchUrl('x', 'category/a.b', false)).searchParams.get('page_size'), '20');
});
