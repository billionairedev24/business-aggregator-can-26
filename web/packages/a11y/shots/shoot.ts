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
  // the generated photos, the catalogue's media (`/api/v1/public/catalogue/media/{id}`, what listings point at) and
  // the kitchen's menu-item photos (`/api/v1/merchants/{id}/menu-items/{item}/photo`)
  const MEDIA = '/api/v1/public/catalogue/media/';
  const MENU_PHOTO = /^\/api\/v1\/merchants\/[^/]+\/menu-items\/([^/]+)\/photo$/;
  await page.route(url => url.pathname.startsWith(PHOTO_PREFIX) || url.pathname.startsWith(MEDIA) || MENU_PHOTO.test(url.pathname), route => {
    const path = new URL(route.request().url()).pathname;
    const item = MENU_PHOTO.exec(path)?.[1];
    const seed = item ? `bowl-${item}` : decodeURIComponent(path.startsWith(MEDIA) ? path.slice(MEDIA.length) : path.slice(PHOTO_PREFIX.length)).replace(/\.svg$/, '');
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
    if (r.click) {
      await page.locator(r.click).first().click({ timeout: 8_000 }).catch(() => undefined);
      await page.waitForLoadState('networkidle').catch(() => undefined);
      await page.waitForTimeout(500);
    }
    // fonts and lazy images: scroll through once so loading="lazy" pictures are in the shot
    await page.evaluate(async () => {
      await document.fonts?.ready;
      for (let y = 0; y < document.body.scrollHeight; y += 600) { window.scrollTo(0, y); await new Promise(r => setTimeout(r, 40)); }
      window.scrollTo(0, 0);
    });
    await page.waitForTimeout(400);
    // a page wider than the screen (a sideways scroll on a phone): name what sticks out (_overflow.txt)
    const wide = await page.evaluate(overflowing);
    if (wide.length) appendFileSync(`${OUT}${app}/_overflow.txt`, `${r.name}-${s.suffix}: ${wide.join(' · ')}\n`);
    await page.screenshot({ path: `${OUT}${app}/${r.name}-${s.suffix}.png`, fullPage: !r.click, animations: 'disabled' });
  }
  // what the mock api had no answer for (the browser's requests): a page that looks empty usually needs one of these
  if (missing.length) appendFileSync(`${OUT}${app}/_missing-api.txt`, `${r.name}: ${[...new Set(missing)].join(', ')}\n`);
}

/**
 * The elements that reach past the right edge of the screen without a clipping or scrolling box around them — what
 * makes a page scroll sideways (html clips it, but the content is cut off). In the browser; at most eight, as
 * `tag.class (right edge px)`.
 */
function overflowing(): string[] {
  const vw = document.documentElement.clientWidth;
  // clipped by a box with overflow — but an absolutely positioned element only by its containing block and above
  const escapes = (pos: string) => pos === 'absolute' || pos === 'fixed';
  const clipped = (el: Element) => {
    let abs = escapes(getComputedStyle(el).position);
    for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) {
      const cs = getComputedStyle(p);
      if (abs && cs.position === 'static') continue;
      if (cs.overflowX !== 'visible') return true;
      if (abs) abs = escapes(cs.position);
    }
    return false;
  };
  const out: string[] = [];
  for (const el of Array.from(document.body.querySelectorAll('*'))) {
    const r = el.getBoundingClientRect();
    if (r.width === 0 || r.right <= vw + 1 || clipped(el)) continue;
    const parent = el.parentElement;
    if (parent && parent.getBoundingClientRect().right > vw + 1 && !clipped(parent)) continue; // report the outermost only
    out.push(`${el.tagName.toLowerCase()}${el.classList.length ? '.' + Array.from(el.classList).join('.') : ''} (${Math.round(r.right)}px)`);
    if (out.length >= 8) break;
  }
  return out;
}

/** One test per route of `app` (each app's project runs its own spec file: consumer|studio|console.spec.ts). */
export function shotsFor(app: App) {
  for (const r of ROUTES[app as keyof typeof ROUTES]) test(r.name, async ({ page }) => shoot(page, app, r));
}
