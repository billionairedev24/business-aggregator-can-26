// Web-target smoke test of the consumer app in headless Chromium (S-97, S-98, S-100). The web build runs the same
// screens, session, DPoP proofs and queries as the phones, against the in-app fixture backend
// (EXPO_PUBLIC_FIXTURES=1), in a 402 × 874 viewport (design 01's frame). It proves the bundle starts and the shell and journeys work — not the native
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
    await page.getByTestId('stub-home').waitFor();
  });
  await step('You: signed in, French, sign out', async () => {
    await page.getByRole('tab', { name: 'You' }).click();
    await page.getByText('Signed in as Grace Hopper').waitFor();
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
    await page.getByTestId('stub-home').waitFor({ timeout: 30_000 });
    const tabs = await page.getByRole('tab').allTextContents();
    if (tabs.join('|') !== 'Home|Services|Cart|Orders|You') throw new Error(tabs.join('|'));
    await page.getByRole('tab', { name: 'Services' }).click();
    await page.getByTestId('services').waitFor();
    await noSideScroll('services');
    await shot('10-tabs-services');
  });
  await step('a sub-screen with the design header', async () => {
    await page.goto(`${base}/orders/NL-48213/track`);
    await page.getByText('Order NL-48213').waitFor();
    await page.getByText('← Back').waitFor();
    await shot('11-sub-screen');
  });
  // S-100 Journey C: a guest browses, signs in (fixture passkey), books a visit and signs a finished job off
  // (the web keeps earlier screens of the stack in the page: look for the visible one)
  const vis = (id) => page.locator(`[data-testid="${id}"]:visible`).first();
  await step('Journey C: services, providers, profile (guest)', async () => {
    await page.goto(`${base}/services`);
    await vis('category-mobile-mechanic').click({ timeout: 30_000 });
    await vis('provider-prairie-wrench').waitFor();
    await noSideScroll('providers');
    await shot('20-providers');
    await vis('provider-prairie-wrench').click();
    await page.getByText('Services & fixed prices').filter({ visible: true }).first().waitFor();
    await noSideScroll('provider');
    await shot('21-provider');
  });
  await step('Journey C: book a visit — service, time, review, escrow, booked', async () => {
    await vis('book-visit').click();
    await page.getByText('What does the car need?').filter({ visible: true }).first().waitFor();
    await vis('field-vehicle').fill('2018 Honda Civic · ABC 1234');
    await vis('field-note').fill('Grinding on braking, worse when cold.');
    await shot('22-book-service');
    await vis('book-next').click();
    await page.getByText('Sign in to hold this time. Your answers stay here.').filter({ visible: true }).first().waitFor();
    await page.getByRole('button', { name: 'Sign in' }).last().click();
    await page.getByRole('button', { name: 'Sign in with a passkey' }).filter({ visible: true }).first().click();
    await page.waitForURL(/\/(home|location)$/);
    if (page.url().endsWith('/location')) {
      // a fresh page has no saved address yet: the sign-in asks for one first (S-98)
      await page.getByLabel('Street address').filter({ visible: true }).first().fill('1204 Exa');
      await page.getByRole('button', { name: '1204 Example Ave, Sampleville, XA' }).filter({ visible: true }).first().click();
      await page.getByRole('button', { name: 'Save and continue' }).filter({ visible: true }).first().click();
    }
    await page.getByRole('tab', { name: 'Services' }).filter({ visible: true }).first().click();
    await vis('category-mobile-mechanic').click();
    await vis('provider-prairie-wrench').click();
    await vis('book-visit').click();
    await vis('book-next').click();
    await page.getByText('When suits you?').filter({ visible: true }).first().waitFor();
    await page.locator('[data-testid^="slot-"]:not([aria-disabled="true"]):visible').first().click();
    await vis('field-address').fill('1204 Example Ave');
    await vis('spot-0').click();
    await vis('field-access').fill('Driveway on the left');
    await noSideScroll('book-time');
    await shot('23-book-time');
    await vis('book-next').click();
    await page.getByText('Review and hold payment').filter({ visible: true }).first().waitFor();
    await vis('agree-policies').click();
    await vis('agree-terms').click();
    await noSideScroll('book-review');
    await shot('24-book-review');
    await vis('pay').click();
    await page.getByText(/^Booked\. Ravi is coming/).filter({ visible: true }).first().waitFor();
    await shot('25-booked');
  });
  await step('Journey C: notifications, ETA, sign-off, review', async () => {
    await vis('see-notifications').click();
    await page.getByText('Prairie Wrench is on the way').filter({ visible: true }).first().waitFor();
    await shot('26-notifications');
    await vis('notification-01J9BOOKINGDONE').click();
    await page.getByRole('button', { name: 'Release payment' }).filter({ visible: true }).first().click();
    await page.getByText('Released.').filter({ visible: true }).first().waitFor();
    await noSideScroll('sign-off');
    await shot('27-sign-off');
    await vis('rate').click();
    await page.getByText('How was Ravi?').filter({ visible: true }).first().waitFor();
    await shot('28-review');
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
