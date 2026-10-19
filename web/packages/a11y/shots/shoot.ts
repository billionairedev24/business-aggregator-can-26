import { appendFileSync, mkdirSync } from 'node:fs';
import { test, type Page } from '@playwright/test';
import { mockApi, type App } from '../pages/support';
import { ROUTES, type ShotRoute } from './routes';
// @ts-expect-error — plain ES module shared with the Node mock api
import { photo, PHOTO_PREFIX } from './photos.mjs';

/**
 * `make ui-shots`: every route of the consumer site, the Studio and the console, full page, at 1440×900 (`-d`) and
 * 390×844 (`-m`), into ui-shots/<app>/<route>-<d|m>.png at the repository root (git-ignored). Same built apps and mock
 * api as the a11y sweep (playwright.config.ts) plus the demo-like data of shots/fixtures.mjs and generated placeholder
 * photos (shots/photos.mjs, served same-origin so no CSP changes). Nothing is asserted: the pictures are for people.
 */
const OUT = new URL('../../../../ui-shots/', import.meta.url).pathname;
const SIZES = [{ suffix: 'd', width: 1440, height: 900 }, { suffix: 'm', width: 390, height: 844 }] as const;

async function photos(page: Page) {
  // the generated photos, and the catalogue's media (`/api/v1/public/catalogue/media/{id}`, what listings point at)
  const MEDIA = '/api/v1/public/catalogue/media/';
  await page.route(url => url.pathname.startsWith(PHOTO_PREFIX) || url.pathname.startsWith(MEDIA), route => {
    const path = new URL(route.request().url()).pathname;
    const seed = decodeURIComponent(path.startsWith(MEDIA) ? path.slice(MEDIA.length) : path.slice(PHOTO_PREFIX.length)).replace(/\.svg$/, '');
    return route.fulfill({ status: 200, contentType: 'image/svg+xml', body: photo(seed) });
  });
}

async function shoot(page: Page, app: App, r: ShotRoute) {
  mkdirSync(`${OUT}${app}`, { recursive: true });
  // the Studio and the console default to signed in (their screens need a session); the consumer to a guest
  const missing = await mockApi(page, app, { signedIn: r.signedIn ?? app !== 'consumer' });
  await photos(page);
  if (app !== 'consumer') await page.addInitScript(() => { try { localStorage.setItem('nl.locale', 'en'); } catch { /* ignore */ } });
  for (const s of SIZES) {
    await page.setViewportSize({ width: s.width, height: s.height });
    const url = app === 'consumer' ? `${r.path}${r.path.includes('?') ? '&' : '?'}lang=en` : r.path;
    await page.goto(url, { waitUntil: 'networkidle' }).catch(() => undefined);
    await page.locator(r.ready ?? 'h1').first().waitFor({ state: 'visible', timeout: 8_000 }).catch(() => undefined);
    // fonts and lazy images: scroll through once so loading="lazy" pictures are in the shot
    await page.evaluate(async () => {
      await document.fonts?.ready;
      for (let y = 0; y < document.body.scrollHeight; y += 600) { window.scrollTo(0, y); await new Promise(r => setTimeout(r, 40)); }
      window.scrollTo(0, 0);
    });
    await page.waitForTimeout(400);
    await page.screenshot({ path: `${OUT}${app}/${r.name}-${s.suffix}.png`, fullPage: true, animations: 'disabled' });
  }
  // what the mock api had no answer for (the browser's requests): a page that looks empty usually needs one of these
  if (missing.length) appendFileSync(`${OUT}${app}/_missing-api.txt`, `${r.name}: ${[...new Set(missing)].join(', ')}\n`);
}

/** One test per route of `app` (each app's project runs its own spec file: consumer|studio|console.spec.ts). */
export function shotsFor(app: App) {
  for (const r of ROUTES[app as keyof typeof ROUTES]) test(r.name, async ({ page }) => shoot(page, app, r));
}
