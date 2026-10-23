#!/usr/bin/env node
// `make mobile-shots` (light & photo-first, mobile): a screenshot of every screen of the consumer and courier apps, to
// look at. The apps' web build (react-native-web, `pnpm export:web`, EXPO_PUBLIC_FIXTURES=1: the in-app fixture
// backend, no server) is served locally and driven by headless Chromium at 390 × 844 (an iPhone 14/15's points).
// Screens open in place through the fixture build's `__nlGo` (a page load would start a new, signed-out session).
//
//   node scripts/shots.mjs consumer|courier [--only <substring>[,<substring>…]]
//
// S-178: the stock photos load too. The fixture build points the photo library at the consumer web's /photos/
// (EXPO_PUBLIC_SITE_ORIGIN, http://localhost:3000 by default), which isn't running here, so every request for a
// `…/photos/<slot>/<variant>` is answered from the variants `make photos` builds into web/apps/consumer/public/photos
// (NL_PHOTOS_DIR to use another folder). Without them the run stops and says so (MOBILE_SHOTS_NO_PHOTOS=1 to go on).
//
// Writes shots-out/<app>/<nn>-<screen>.png (what the phone shows) and, when the screen scrolls, <…>-full.png (the
// whole scroll content in one picture), the French run's screens as <…>-fr.png, and _overflow.txt listing screens
// that scroll sideways. Native-only parts (maps, camera, the system browser, Keychain) are not on the web build.
import { createServer } from 'node:http';
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { extname, join } from 'node:path';
import { createRequire } from 'node:module';

const app = process.argv[2];
if (app !== 'consumer' && app !== 'courier') {
  console.error('usage: node scripts/shots.mjs consumer|courier [--only <substring>]');
  process.exit(2);
}
const only = process.argv.includes('--only') ? process.argv[process.argv.indexOf('--only') + 1].split(',') : null;
const MOBILE = new URL('../', import.meta.url).pathname;
const DIST = join(MOBILE, 'apps', app, 'dist-web');
const OUT = join(MOBILE, 'shots-out', app);
// playwright-core is the apps' devDependency (pnpm doesn't hoist it to mobile/)
const { chromium } = createRequire(join(MOBILE, 'apps', app, 'package.json'))('playwright-core');
if (!existsSync(join(DIST, 'index.html'))) {
  console.error(`${DIST} is missing: run \`pnpm --filter ${app === 'consumer' ? '@northline/consumer-app' : '@northline/courier'} export:web\` first`);
  process.exit(2);
}
const PHOTOS = process.env.NL_PHOTOS_DIR ?? join(MOBILE, '..', 'web', 'apps', 'consumer', 'public', 'photos');
if (!existsSync(PHOTOS) && !process.env.MOBILE_SHOTS_NO_PHOTOS) {
  console.error(`${PHOTOS} is missing: run \`make photos\` first (or MOBILE_SHOTS_NO_PHOTOS=1 for shots without stock photos)`);
  process.exit(2);
}
if (!only) rmSync(OUT, { recursive: true, force: true });
mkdirSync(OUT, { recursive: true });

function chromiumPath() {
  if (process.env.CHROMIUM_PATH) return process.env.CHROMIUM_PATH;
  const root = process.env.PLAYWRIGHT_BROWSERS_PATH ?? '/opt/pw-browsers';
  const dir = existsSync(root) ? readdirSync(root).find((d) => /^chromium-\d+$/.test(d)) : undefined;
  return dir ? join(root, dir, 'chrome-linux', 'chrome') : undefined;
}

const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json', '.ttf': 'font/ttf', '.png': 'image/png', '.jpg': 'image/jpeg', '.webp': 'image/webp', '.avif': 'image/avif' };

/** A photo variant by its path under /photos/ (`category/x/card-640.<hash>.jpg`), or null. */
function photoFile(path) {
  const file = join(PHOTOS, path);
  return file.startsWith(PHOTOS) && existsSync(file) && extname(file) ? file : null;
}
const server = createServer((req, res) => {
  const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  const file = join(DIST, path);
  const target = file.startsWith(DIST) && existsSync(file) && extname(file) ? file : join(DIST, 'index.html');
  res.writeHead(200, { 'content-type': TYPES[extname(target)] ?? 'application/octet-stream' });
  res.end(readFileSync(target));
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}`;

const W = 390;
const H = 844;
const browser = await chromium.launch({ executablePath: chromiumPath(), args: ['--no-sandbox'] });
const page = await browser.newPage({ viewport: { width: W, height: H }, locale: 'en-CA', deviceScaleFactor: 2 });
// the photo library's base is another origin (the consumer web): answer its variants from disk
const missingPhotos = new Set();
await page.route(/\/photos\/.+\.(jpg|webp|avif)$/, async (route) => {
  const path = decodeURIComponent(new URL(route.request().url()).pathname).replace(/^.*?\/photos\//, '');
  const file = photoFile(path);
  if (!file) {
    missingPhotos.add(path);
    return route.fulfill({ status: 404, body: '' });
  }
  return route.fulfill({ status: 200, contentType: TYPES[extname(file)], headers: { 'cache-control': 'max-age=3600' }, body: readFileSync(file) });
});
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
const overflow = [];
const failures = [];
let n = 0;

const settle = (ms = 900) => page.waitForTimeout(ms);
const go = async (href) => {
  await page.evaluate((h) => globalThis.__nlGo(h), href);
  await settle();
};
const vis = (id) => page.locator(`[data-testid="${id}"]:visible`).first();
const tap = async (id) => {
  await vis(id).click({ timeout: 10_000 });
  await settle(600);
};

/** The visible screen's own scroller (the stack keeps earlier screens in the page): how far it scrolls. */
const scrollExtra = () =>
  page.evaluate(() => {
    let best = 0;
    for (const el of document.querySelectorAll('div')) {
      const s = getComputedStyle(el);
      if (!/(auto|scroll)/.test(s.overflowY) || el.scrollHeight <= el.clientHeight + 2) continue;
      const r = el.getBoundingClientRect();
      if (r.width < 200 || r.height < 200 || !el.checkVisibility({ visibilityProperty: true, opacityProperty: true })) continue;
      if (r.left >= window.innerWidth || r.right <= 0) continue;
      best = Math.max(best, el.scrollHeight - el.clientHeight);
    }
    return best;
  });

async function shot(name, { full = true } = {}) {
  const file = only ? `x-${name}` : `${String(++n).padStart(2, '0')}-${name}`;
  if (only) n++;
  await page.screenshot({ path: join(OUT, `${file}.png`) });
  const over = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  if (over > 1) overflow.push(`${file}: ${over}px`);
  const extra = full ? await scrollExtra() : 0;
  if (extra > 8) {
    await page.setViewportSize({ width: W, height: Math.min(H + extra, 6000) });
    await settle(400);
    await page.screenshot({ path: join(OUT, `${file}-full.png`) });
    await page.setViewportSize({ width: W, height: H });
    await settle(300);
  }
}

async function step(name, fn) {
  if (only && !only.some((o) => name.includes(o)) && !['sign-in-done', 'today'].includes(name)) return;
  try {
    await fn();
    console.log(`ok   ${name}`);
  } catch (e) {
    failures.push(name);
    console.log(`FAIL ${name}: ${e.message.split('\n')[0]}`);
    await page.screenshot({ path: join(OUT, `_fail-${name.replace(/\W+/g, '-')}.png`) }).catch(() => undefined);
  }
}

/** Screens reached by a route alone: [name, href]. */
const screens = (list) => list.map(([name, href]) => [name, async () => { await go(href); await shot(name); }]);

/** S-169: the shared kit's gallery (dev builds and this fixture build): English, the bottom sheet open, then French. */
async function gallery() {
  await step('gallery', async () => {
    await go('/dev/gallery');
    await vis('kit-gallery').waitFor({ timeout: 10_000 });
    await shot('gallery');
  });
  await step('gallery-sheet', async () => {
    await tap('gallery-open-sheet');
    await settle(700);
    await shot('gallery-sheet', { full: false });
    await page.getByRole('button', { name: 'Close' }).filter({ visible: true }).last().click();
    await settle(500);
  });
  await step('gallery-fr', async () => {
    await page.getByRole('radio', { name: 'Français' }).filter({ visible: true }).first().click();
    await settle(600);
    await shot('gallery-fr');
  });
}

async function consumer() {
  await page.goto(base);
  await page.getByTestId('welcome').or(page.getByTestId('home')).first().waitFor({ timeout: 60_000 });
  await settle(1500);
  const guest = [
    ['welcome', async () => { await shot('welcome'); }],
    ...screens([
      ['home-guest', '/home'],
      ['sign-in', '/sign-in'],
      ['sign-up', '/sign-up'],
      ['location-sheet-guest', '/location'],
      ['account-guest', '/account'],
    ]),
  ];
  for (const [name, fn] of guest) await step(name, fn);
  // signed in through the fixture's browser sign-in (a passkey on the consumer site, on a phone)
  await step('sign-in-done', async () => {
    await go('/sign-in');
    await tap('browser-sign-in');
    await settle(1200);
    if (page.url().includes('/location')) {
      await vis('field-street').fill('1204 Exa');
      await page.getByRole('button', { name: '1204 Example Ave, Sampleville, XA' }).filter({ visible: true }).first().click();
      await settle();
      await tap('location-save');
    }
  });
  const signedIn = [
    ...screens([
      ['home', '/home'],
      ['browse', '/services'],
      ['search', '/search'],
      ['search-results', '/search?q=sourdough'],
      ['search-services', '/search?q=brake'],
      ['category', '/services/mobile-mechanic'],
      ['provider', '/providers/prairie-wrench'],
      ['product', '/product/p-sourdough'],
    ]),
    ['cart', async () => {
      await go('/product/p-sourdough');
      await tap('product-add');
      await go('/product/p-ribeye');
      await tap('product-add');
      await go('/cart');
      await shot('cart');
    }],
    ['checkout', async () => { await go('/checkout'); await shot('checkout'); }],
    ['pay', async () => { await tap('checkout-continue'); await settle(); await shot('pay'); }],
    ['book-service', async () => {
      await go('/providers/prairie-wrench');
      await tap('book-visit');
      await shot('book-service');
    }],
    ['book-time', async () => {
      await vis('field-vehicle').fill('2018 Honda Civic · ABC 1234');
      await vis('field-note').fill('Grinding on braking, worse when cold.');
      await tap('book-next');
      await settle();
      await page.locator('[data-testid^="slot-"]:not([aria-disabled="true"]):visible').first().click();
      await vis('field-address').fill('1204 Example Ave');
      await vis('spot-0').click();
      await vis('field-access').fill('Driveway on the left');
      await shot('book-time');
    }],
    ['book-review', async () => {
      await tap('book-next');
      await settle();
      await shot('book-review');
    }],
    ['booked', async () => {
      await tap('agree-policies');
      await tap('agree-terms');
      await tap('pay');
      await settle(1500);
      await shot('booked');
    }],
    ['seed-orders', async () => {
      // an order on its way and one delivered (the fixture build's seedOrder; checkout's own order is placed later)
      await page.evaluate(() => { globalThis.__nlSeedOrder('picked_up', 'ord-transit'); globalThis.__nlSeedOrder('delivered', 'ord-done'); });
    }],
    ...screens([
      ['activity', '/orders'],
      ['order', '/orders/ord-transit'],
      ['order-track', '/orders/ord-transit/track'],
      ['order-confirmed', '/orders/ord-transit/confirmed'],
      ['order-done', '/orders/ord-done'],
      ['order-delivered', '/orders/ord-done/delivered'],
      ['booking', '/bookings/01J9BOOKINGROUTE'],
      ['booking-eta', '/bookings/01J9BOOKINGROUTE/eta'],
      ['booking-sign-off', '/bookings/01J9BOOKINGDONE/sign-off'],
      ['booking-review', '/bookings/01J9BOOKINGDONE/review'],
      ['quote', '/quotes/q-2988'],
      ['case', '/cases/rf-2201'],
      ['problem', '/problem/order/ord-done'],
      ['notifications', '/notifications'],
      ['account', '/account'],
      ['account-profile', '/account/profile'],
      ['account-addresses', '/account/addresses'],
      ['account-payments', '/account/payments'],
      ['account-notifications', '/account/notifications'],
      ['account-favourites', '/account/favourites'],
      ['account-preferences', '/account/preferences'],
      ['account-help', '/account/help'],
      ['account-data', '/account/data'],
      ['security', '/security'],
      ['wallet', '/wallet'],
      ['location-sheet', '/location'],
    ]),
  ];
  for (const [name, fn] of signedIn) await step(name, fn);
  await gallery();
  // French (Account › Language), then a few screens again
  await step('french', async () => {
    await go('/account');
    await page.getByRole('radio', { name: 'Français' }).filter({ visible: true }).first().click();
    await settle();
    for (const [name, href] of [['home', '/home'], ['browse', '/services'], ['product', '/product/p-sourdough'], ['cart', '/cart'], ['order-track', '/orders/ord-transit/track'], ['account', '/account'], ['location-sheet', '/location']]) {
      await go(href);
      await shot(`${name}-fr`, { full: false });
    }
    await go('/account');
    await page.getByRole('radio', { name: 'English' }).filter({ visible: true }).first().click();
  });
}

async function courier() {
  await page.goto(base);
  await page.getByRole('button', { name: 'Sign in', exact: true }).waitFor({ timeout: 60_000 });
  await settle(1200);
  await step('sign-in', async () => { await shot('sign-in'); });
  await step('today', async () => {
    // S-159: email + password + code, or the passkey in the browser (the fixture signs that in at once)
    await tap('browser-sign-in');
    await settle(1500);
    await shot('today');
  });
  const list = screens([
    ['route', '/run'],
    ['stop-pickup', '/stops/st-1'],
  ]);
  for (const [name, fn] of list) await step(name, fn);
  await step('pickup', async () => {
    await go('/stops/st-1/pickup');
    await shot('pickup', { full: false });
    // confirm both pickups so the drop-offs open
    await page.getByRole('switch').filter({ visible: true }).first().click();
    await shot('pickup-sealed', { full: false });
    await tap('confirm-pickup');
    await go('/stops/st-2/pickup');
    await page.getByRole('switch').filter({ visible: true }).first().click();
    await tap('confirm-pickup');
  });
  for (const [name, fn] of screens([
    ['route-after-pickup', '/run'],
    ['stop-dropoff', '/stops/st-3'],
    ['dropoff', '/stops/st-3/dropoff'],
    ['profile', '/account'],
  ]))
    await step(name, fn);
  await step('dropoff-pin', async () => {
    await go('/stops/st-3/dropoff');
    await page.getByRole('tab', { name: 'PIN' }).filter({ visible: true }).first().click();
    await vis('pin').fill('4821');
    await settle(400);
    await shot('dropoff-pin', { full: false });
  });
  await step('dropoff-signature', async () => {
    await page.getByRole('tab', { name: 'Signature' }).filter({ visible: true }).first().click();
    await settle(400);
    await shot('dropoff-signature', { full: false });
  });
  await step('french', async () => {
    await go('/account');
    await page.getByRole('tab', { name: 'Français' }).filter({ visible: true }).first().click();
    await settle();
    for (const [name, href] of [['today', '/'], ['route', '/run'], ['stop-dropoff', '/stops/st-3'], ['profile', '/account']]) {
      await go(href);
      await shot(`${name}-fr`, { full: false });
    }
    await go('/account');
    await page.getByRole('tab', { name: 'English' }).filter({ visible: true }).first().click();
  });
  await gallery();
}

try {
  await (app === 'consumer' ? consumer() : courier());
} finally {
  writeFileSync(join(OUT, '_overflow.txt'), overflow.join('\n') + (overflow.length ? '\n' : ''));
  if (errors.length) writeFileSync(join(OUT, '_errors.txt'), errors.join('\n') + '\n');
  if (missingPhotos.size) writeFileSync(join(OUT, '_missing-photos.txt'), [...missingPhotos].sort().join('\n') + '\n');
  await browser.close();
  server.close();
}
console.log(`${n} screens → ${OUT}${overflow.length ? ` · ${overflow.length} scroll sideways (_overflow.txt)` : ''}${errors.length ? ` · ${errors.length} page errors (_errors.txt)` : ''}${missingPhotos.size ? ` · ${missingPhotos.size} photo variants not built (_missing-photos.txt; make photos)` : ''}`);
if (failures.length) {
  console.error(`${failures.length} step(s) failed: ${failures.join(', ')}`);
  process.exit(1);
}
