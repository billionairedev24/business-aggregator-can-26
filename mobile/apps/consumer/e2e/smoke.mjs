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

  // JOURNEY-A-STEPS

  await step('the tab shell: Home · Services · Cart · Orders · You', async () => {
    await page.goto(`${base}/home`);
    await page.getByTestId('stub-home').waitFor({ timeout: 30_000 });
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
