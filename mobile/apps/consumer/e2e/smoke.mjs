// Web-target smoke test of the consumer app in headless Chromium (S-97, S-98). The web build runs the same screens,
// session, DPoP proofs and queries as the phones, against the in-app fixture backend (EXPO_PUBLIC_FIXTURES=1), in a
// 402 × 874 viewport (design 01's frame). It proves the bundle starts and the shell and journeys work — not the native
// modules (Keychain, location services, the system browser), which only a device can.
//
//   pnpm export:web && pnpm smoke:web     (PLAYWRIGHT_BROWSERS_PATH or CHROMIUM_PATH pick the browser)
// Screenshots: smoke-out/*.png (gitignored).
import { createServer } from 'node:http';
import { existsSync, mkdirSync, readFileSync, readdirSync } from 'node:fs';
import { extname, join } from 'node:path';
import { chromium } from 'playwright-core';

const DIST = new URL('../dist-web/', import.meta.url).pathname;
const OUT = new URL('../smoke-out/', import.meta.url).pathname;
if (!existsSync(join(DIST, 'index.html'))) {
  console.error('dist-web/ is missing: run `pnpm export:web` first');
  process.exit(2);
}
mkdirSync(OUT, { recursive: true });

function chromiumPath() {
  if (process.env.CHROMIUM_PATH) return process.env.CHROMIUM_PATH;
  const root = process.env.PLAYWRIGHT_BROWSERS_PATH ?? '/opt/pw-browsers';
  const dir = existsSync(root) ? readdirSync(root).find((d) => /^chromium-\d+$/.test(d)) : undefined;
  return dir ? join(root, dir, 'chrome-linux', 'chrome') : undefined;
}

const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json', '.ttf': 'font/ttf', '.png': 'image/png' };
const server = createServer((req, res) => {
  const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  const file = join(DIST, path);
  const target = file.startsWith(DIST) && existsSync(file) && extname(file) ? file : join(DIST, 'index.html');
  res.writeHead(200, { 'content-type': TYPES[extname(target)] ?? 'application/octet-stream' });
  res.end(readFileSync(target));
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch({ executablePath: chromiumPath(), args: ['--no-sandbox'] });
const failures = [];
const step = async (name, fn) => {
  try {
    await fn();
    console.log(`ok   ${name}`);
  } catch (e) {
    failures.push(name);
    console.log(`FAIL ${name}: ${e.message.split('\n')[0]}`);
  }
};

try {
  const page = await browser.newPage({ viewport: { width: 402, height: 874 }, locale: 'en-CA' });
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto(base);
  const noSideScroll = async (label) => {
    const over = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    if (over > 1) throw new Error(`${label}: ${over}px of horizontal scroll`);
  };
  const shot = (name) => page.screenshot({ path: join(OUT, `${name}.png`), fullPage: true });

  await step('A1 welcome', async () => {
    await page.getByText('Northline · Sample Province').waitFor({ timeout: 30_000 });
    await page.getByText('Every trusted local,').waitFor();
    await noSideScroll('welcome');
    await shot('01-welcome');
  });
  await step('A2 sign up: the rules, then the code is sent', async () => {
    await page.getByRole('button', { name: 'Create account' }).click();
    await page.getByText('Create your account').waitFor();
    await page.getByLabel('Mobile number').fill('555 555 0148');
    await page.getByLabel('Full name').fill('Grace');
    await page.getByLabel('Email (receipts)').fill('grace@example.com');
    await page.getByRole('checkbox').click();
    await page.getByRole('button', { name: 'Send code' }).click();
    await page.getByText('Last name is required.').waitFor();
    await page.getByLabel('Full name').fill('Grace Hopper');
    await noSideScroll('sign-up');
    await shot('02-sign-up');
    await page.getByRole('button', { name: 'Send code' }).click();
    await page.getByText('Enter the 6-digit code').waitFor();
    await page.getByText('Resend in 0:4', { exact: false }).waitFor();
    await shot('03-verify');
  });
  await step('A3 verify (six digits submit by themselves)', async () => {
    await page.getByLabel('6-digit code').fill('246810');
    await page.getByText('Protect your account').waitFor();
    await noSideScroll('second factor');
    await shot('04-second-factor');
  });
  await step('A4 second factor: SMS only, signed in through the hand-off', async () => {
    await page.getByRole('radio', { name: /^SMS code/ }).click();
    await page.getByRole('button', { name: 'Continue with SMS' }).click();
    await page.getByText('Where should we bring things?').waitFor();
  });
  await step('A5 province & address, saved', async () => {
    await page.getByText('Live · Sampleville, Exampleton').waitFor();
    await page.getByLabel('Street address').fill('1204 Exa');
    await page.getByRole('button', { name: '1204 Example Ave, Sampleville, XA' }).click();
    await page.getByText('Zone · Old Town').waitFor();
    await page.getByText('Sales tax 5%').waitFor();
    await noSideScroll('location');
    await shot('05-location');
    await page.getByRole('button', { name: 'Save and continue' }).click();
    await page.getByTestId('home').waitFor();
  });
  // Journey B (S-99): signed in with the address just saved; the fixture backend's payment stand-in
  await step('B1–B7 shop: home, search, product, cart, checkout, payment, confirmed', async () => {
    await page.getByText("Tonight's pooled run").waitFor({ timeout: 30_000 });
    await noSideScroll('home');
    await shot('20-home');
    await page.getByTestId('home-search').click();
    await page.getByLabel('Search the shops').fill('sourdough');
    await page.getByText('1 result · sorted by').waitFor();
    await noSideScroll('search');
    await shot('21-search');
    await page.getByRole('button', { name: /^Country sourdough/ }).click();
    await page.getByText('Maple Lane Bakery · Master tier · ★ 4.8 (211)').waitFor();
    await noSideScroll('product');
    await shot('22-product');
    await page.getByTestId('product-add').click();
    await page.getByText('1 item · 1 shop · one delivery').waitFor();
    await noSideScroll('cart');
    await shot('23-cart');
    await page.getByTestId('cart-checkout').click();
    await page.getByText('GST 5%').waitFor();
    await noSideScroll('checkout');
    await shot('24-checkout');
    await page.getByTestId('checkout-continue').click();
    await page.getByRole('button', { name: /^Pay \$/ }).click();
    await page.getByText('Confirm this payment').waitFor();
    await shot('25-bank-step');
    await page.getByTestId('bank-approve').click();
    await page.getByText(/^Order placed\. Arriving/).waitFor();
    await noSideScroll('confirmed');
    await shot('26-confirmed');
    await page.getByRole('button', { name: 'Back to home' }).click();
    await page.getByTestId('home').waitFor();
  });
  await step('You: signed in, French, sign out', async () => {
    await page.getByRole('tab', { name: 'You' }).click();
    await page.getByText('Grace Hopper').waitFor();
    await page.getByRole('radio', { name: 'Français' }).click();
    await page.getByRole('button', { name: 'Se déconnecter' }).waitFor();
    await noSideScroll('you');
    await shot('06-you-fr');
    await page.getByRole('button', { name: 'Se déconnecter' }).click();
    await page.getByRole('tab', { name: 'Vous' }).click();
    await page.getByText('Connectez-vous pour voir vos commandes, vos réservations et vos points.').waitFor();
    await page.getByRole('radio', { name: 'English' }).click();
  });

  await step('the tab shell: Home · Services · Cart · Orders · You', async () => {
    await page.getByRole('tab', { name: 'Home' }).click();
    await page.getByTestId('home').waitFor({ timeout: 30_000 });
    const tabs = await page.getByRole('tab').allTextContents();
    if (tabs.join('|') !== 'Home|Services|Cart|Orders|You') throw new Error(tabs.join('|'));
    await page.getByRole('tab', { name: 'Services' }).click();
    await page.getByTestId('stub-services').waitFor();
    await noSideScroll('services');
    await shot('10-tabs-services');
  });
  await step('a sub-screen with the design header', async () => {
    await page.goto(`${base}/orders/NL-48213/track`);
    await page.getByText('Order NL-48213').waitFor();
    await page.getByText('← Back').waitFor();
    await shot('11-sub-screen');
  });
  await step('no page errors', async () => {
    if (errors.length) throw new Error(errors.join(' | '));
  });
} finally {
  await browser.close();
  server.close();
}
if (failures.length) {
  console.error(`${failures.length} step(s) failed`);
  process.exit(1);
}
console.log('consumer web smoke: all steps passed');
