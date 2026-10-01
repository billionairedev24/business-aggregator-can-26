// Web-target smoke test of the courier app in headless Chromium (S-87). The web build runs the same screens, session,
// outbox and DPoP proofs as the phones, against the in-app fixture backend (EXPO_PUBLIC_FIXTURES=1); it proves the
// bundle starts and a courier can sign in, pick up and drop off with a PIN — not the native modules (camera,
// background location, Keychain), which only a device can.
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
  const page = await browser.newPage({ viewport: { width: 390, height: 844 }, locale: 'en-CA' });
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto(base);
  const noSideScroll = async (label) => {
    const over = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    if (over > 1) throw new Error(`${label}: ${over}px of horizontal scroll`);
  };
  const shot = (name) => page.screenshot({ path: join(OUT, `${name}.png`), fullPage: true });

  await step('sign-in screen', async () => {
    await page.getByText('Courier sign-in').waitFor({ timeout: 30_000 });
    await noSideScroll('sign-in');
    await shot('01-sign-in');
  });
  await step('sign in (fixture backend, DPoP proofs signed in the browser)', async () => {
    await page.getByRole('button', { name: 'Sign in' }).click();
    await page.getByText('On a run').waitFor();
    await shot('02-shift');
  });
  await step('the run lists its stops in order', async () => {
    await page.getByRole('button', { name: 'Open run' }).click();
    await page.getByText('Juniper Bakery').waitFor();
    const labels = await page.locator('[aria-label^="Stop "]').evaluateAll((els) => els.map((e) => e.getAttribute('aria-label')));
    if (labels.length !== 4 || !labels[0].startsWith('Stop 1 of 4: Pickup')) throw new Error(labels.join(' | '));
    await noSideScroll('run');
    await shot('03-run');
  });
  await step('pickup', async () => {
    await page.getByRole('button', { name: /^Stop 1 of 4/ }).click();
    await page.getByRole('button', { name: 'Pick up' }).click();
    await page.getByRole('switch').click();
    await page.getByRole('button', { name: 'Confirm pickup' }).click();
    await page.getByRole('button', { name: /^Stop 1 of 4: Pickup, Juniper Bakery, Done/ }).waitFor();
  });
  await step('drop-off with the PIN', async () => {
    await page.getByRole('button', { name: /^Stop 3 of 4/ }).click();
    await page.getByText('Buzz 0804').waitFor();
    await page.getByRole('button', { name: 'Hand over' }).click();
    await page.getByRole('tab', { name: 'PIN' }).click();
    await page.getByLabel("Customer's PIN").fill('4821');
    await shot('04-dropoff');
    await page.getByRole('button', { name: 'Complete drop-off' }).click();
    await page.getByRole('button', { name: /^Stop 3 of 4: Drop-off, 1204 Example Ave, Done/ }).waitFor();
    await shot('05-run-after');
  });
  await step('French', async () => {
    // in-app navigation: a reload would start a new (signed-out) session, as on a phone after sign-out
    const account = page.getByRole('button', { name: 'Account' });
    for (let i = 0; i < 6 && !(await account.isVisible()); i++) await page.goBack();
    await account.click();
    await page.getByRole('tab', { name: 'Français' }).click();
    await page.getByText('Se déconnecter').waitFor();
    await noSideScroll('account');
    await shot('06-account-fr');
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
console.log('courier web smoke: all steps passed');
