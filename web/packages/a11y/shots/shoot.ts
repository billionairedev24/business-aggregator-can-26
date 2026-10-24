import { appendFileSync, mkdirSync } from 'node:fs';
import { expect, test, type Page } from '@playwright/test';
import { HYDRATION, mockApi, type App } from '../pages/support';
import { ROUTES, type ShotRoute } from './routes';
import { libraryPhotos } from './photos';

/**
 * `make ui-shots`: every route of the consumer site, the Studio and the console, full page, at 1440×900 (`-d`) and
 * 390×844 (`-m`), into ui-shots/<app>/<route>-<d|m>.png at the repository root (git-ignored). Same built apps and mock
 * api as the a11y sweep (playwright.config.ts) plus the demo-like data of shots/fixtures.mjs and the photo library's own
 * files — the ones `make demo-data` uploads (shots/photos.ts, served same-origin so no CSP changes). Nothing is
 * asserted about the pictures (they are for people), but a consumer route with a React hydration error fails
 * (S-182; shots/hydration.spec.ts checks every route in both languages, `make ui-hydration`). S-180: every route in
 * English and in French (`-fr-`), and three lists beside the pictures: _overflow.txt (a sideways scroll),
 * _truncated.txt (text cut off by an ellipsis or its box) and _untranslated.txt (English words on a French page).
 */
const OUT = new URL('../../../../ui-shots/', import.meta.url).pathname;
const DEFAULT_SIZES = [{ suffix: 'd', width: 1440, height: 900 }, { suffix: 'm', width: 390, height: 844 }] as const;
/**
 * `UI_SHOTS_WIDTHS=2560,1920,1440,390,360` photographs those widths instead (named `<route>-<width>.png`; the height a
 * common screen of that width: 16:9 from 1024px up, a phone's below). Default: 1440 (`-d`) and 390 (`-m`).
 */
const SIZES: readonly { suffix: string; width: number; height: number }[] = process.env.UI_SHOTS_WIDTHS
  ? process.env.UI_SHOTS_WIDTHS.split(',').map(Number).filter(w => w >= 280).map(width => ({
    suffix: String(width), width, height: width >= 1024 ? Math.round(width * 9 / 16) : width < 380 ? 760 : 844,
  }))
  : DEFAULT_SIZES;

/**
 * S-180: both languages. `UI_SHOTS_LANGS=en` (or `fr`) photographs one; by default every route is shot in English
 * (`<route>-d.png`) and in French (`<route>-fr-d.png`). The consumer takes `?lang=`, the Studio and the console the
 * stored `nl.locale` (their language switch).
 */
export const LANGS: readonly ('en' | 'fr')[] = (process.env.UI_SHOTS_LANGS ?? 'en,fr').split(',').filter((l): l is 'en' | 'fr' => l === 'en' || l === 'fr');
export const fileName = (r: ShotRoute, lang: 'en' | 'fr', suffix: string) => `${r.name}${lang === 'fr' ? '-fr' : ''}-${suffix}`;

/**
 * Everything a route needs before its page opens: the mock api's session and answers, the library photos, the pinned
 * clock, the localStorage entries and the language. Returns the api requests nobody answered. Shared with the
 * accessibility run (a11y.spec.ts), so it checks the very pages the shots show.
 */
export async function prepare(page: Page, app: App, r: ShotRoute, lang: 'en' | 'fr' = 'en'): Promise<string[]> {
  // the Studio and the console default to signed in (their screens need a session); the consumer to a guest
  const missing = await mockApi(page, app, { signedIn: r.signedIn ?? app !== 'consumer', down: r.down });
  await libraryPhotos(page);
  if (r.now) await page.clock.setFixedTime(new Date(r.now));
  if (r.storage) await page.addInitScript((entries: Record<string, string>) => { for (const [k, v] of Object.entries(entries)) { try { localStorage.setItem(k, v); } catch { /* ignore */ } } }, r.storage);
  if (app !== 'consumer') await page.addInitScript(l => { try { localStorage.setItem('nl.locale', l); } catch { /* ignore */ } }, lang);
  return missing;
}

/** Opens the route at the current window size in `lang`, waits for it, presses its `click`, loads lazy pictures. */
export async function visit(page: Page, app: App, r: ShotRoute, lang: 'en' | 'fr') {
  const url = app === 'consumer' ? `${r.path}${r.path.includes('?') ? '&' : '?'}lang=${lang}` : r.path;
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
}

async function shoot(page: Page, app: App, r: ShotRoute) {
  mkdirSync(`${OUT}${app}`, { recursive: true });
  const hydration: string[] = [];
  if (app === 'consumer') {
    page.on('console', m => { if ((m.type() === 'error' || m.type() === 'warning') && HYDRATION.test(m.text())) hydration.push(m.text().slice(0, 300)); });
    page.on('pageerror', e => { if (HYDRATION.test(e.message)) hydration.push(e.message.slice(0, 300)); });
  }
  const missing: string[] = [];
  for (const lang of LANGS) {
    // a fresh page per language: the Studio's and the console's language is read once, from localStorage
    const p = lang === LANGS[0] ? page : await page.context().newPage();
    if (p !== page && app === 'consumer') {
      p.on('console', m => { if ((m.type() === 'error' || m.type() === 'warning') && HYDRATION.test(m.text())) hydration.push(m.text().slice(0, 300)); });
      p.on('pageerror', e => { if (HYDRATION.test(e.message)) hydration.push(e.message.slice(0, 300)); });
    }
    missing.push(...await prepare(p, app, r, lang));
    for (const s of SIZES) {
      await p.setViewportSize({ width: s.width, height: s.height });
      await visit(p, app, r, lang);
      const name = fileName(r, lang, s.suffix);
      // a page wider than the screen (a sideways scroll on a phone): name what sticks out (_overflow.txt)
      const wide = await p.evaluate(overflowing);
      // and the page itself: wider than the window = a sideways scroll. base.css clips html and body (overflow-x: clip),
      // which hides it from scrollWidth, so measure with the clip lifted for a moment
      const doc = await p.evaluate(() => {
        const els = [document.documentElement, document.body];
        const before = els.map(el => el.style.overflowX);
        els.forEach(el => { el.style.overflowX = 'visible'; });
        const el = document.scrollingElement ?? document.documentElement;
        const out = { scroll: el.scrollWidth, client: el.clientWidth };
        els.forEach((e, i) => { e.style.overflowX = before[i]!; });
        return out;
      });
      if (doc.scroll > doc.client + 1) wide.unshift(`page ${doc.scroll}px > ${doc.client}px`);
      if (wide.length) appendFileSync(`${OUT}${app}/_overflow.txt`, `${name}: ${wide.join(' · ')}\n`);
      // S-180: words cut off with an ellipsis or clipped by their box (French runs longer), and English left on a French page
      const cut = await p.evaluate(truncated);
      if (cut.length) appendFileSync(`${OUT}${app}/_truncated.txt`, `${name}: ${cut.join(' · ')}\n`);
      if (lang === 'fr') {
        const english = await p.evaluate(untranslated);
        if (english.length) appendFileSync(`${OUT}${app}/_untranslated.txt`, `${name}: ${english.join(' · ')}\n`);
      }
      await p.screenshot({ path: `${OUT}${app}/${name}.png`, fullPage: !r.click && !r.sheet, animations: 'disabled' });
    }
    if (p !== page) await p.close();
  }
  expect(hydration, `${r.path}: React hydration errors`).toEqual([]);
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

/**
 * S-180: text that does not fit — an element with its own text cut by `text-overflow: ellipsis`, a line clamp, or
 * clipped by its box (`overflow: hidden`) — as `tag.class "its text"` (at most ten). A long shop name ellipsed in a card
 * is by design; a cut button, tab or heading is a bug (French runs about 20 % longer than English).
 */
function truncated(): string[] {
  const out: string[] = [];
  const ownText = (el: Element) => [...el.childNodes].some(n => n.nodeType === Node.TEXT_NODE && (n.textContent ?? '').trim().length > 1);
  for (const el of Array.from(document.body.querySelectorAll<HTMLElement>('*'))) {
    if (!ownText(el) || el.closest('[aria-hidden="true"], .sr-only, .nl-sr-only, .visually-hidden, svg, script, style, select, option')) continue;
    const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden' || el.getClientRects().length === 0) continue;
    const r = el.getBoundingClientRect();
    if (r.width <= 1 || r.height <= 1) continue;
    const wide = el.scrollWidth > el.clientWidth + 1, tall = el.scrollHeight > el.clientHeight + 2;
    const clamp = cs.getPropertyValue('-webkit-line-clamp');
    const cut = (cs.textOverflow === 'ellipsis' && wide) || (clamp && clamp !== 'none' && tall)
      || (cs.overflowX !== 'visible' && cs.overflowX !== 'auto' && cs.overflowX !== 'scroll' && wide && cs.whiteSpace === 'nowrap');
    if (!cut) continue;
    const cls = typeof el.className === 'string' ? el.className.trim().split(/\s+/).slice(0, 2).join('.') : '';
    out.push(`${el.tagName.toLowerCase()}${cls ? '.' + cls : ''} "${(el.textContent ?? '').trim().replace(/\s+/g, ' ').slice(0, 50)}"`);
    if (out.length >= 10) break;
  }
  return out;
}

/**
 * S-180: English left on a French page — visible text nodes (and accessible names) with two or more English-only
 * words. The fixtures' own data (business, product and review texts) is English, so this lists candidates to read, not
 * failures: a business name or a customer's review stays as written.
 */
function untranslated(): string[] {
  const EN = /\b(the|and|your|you|with|from|for|this|that|our|are|is|was|will|can|has|have|not|when|what|which|there|their|here|more|all|any|add|sign|order|orders|view|see|save|cancel|delete|search|help|account|today|tomorrow|book|next|back|continue|close|open|show|hide|edit|new|change|choose|select|remove|clear|filters?|by|of|to|in|at|yes|none|items?|price|reviews?|ratings?|left|days?|hours?|week|month|pending|active|paused|done|ready|details|settings|apply|send|reply|loading|error|try|again|out|sold|free|get|it|now|set|up|use|my|we)\b/gi;
  const out: string[] = [];
  const seen = new Set<string>();
  const consider = (text: string, where: string) => {
    const t = text.trim().replace(/\s+/g, ' ');
    if (t.length < 4 || seen.has(t)) return;
    const hits = new Set((t.match(EN) ?? []).map(w => w.toLowerCase()));
    if (hits.size < 2) return;
    seen.add(t);
    out.push(`${where} "${t.slice(0, 60)}"`);
  };
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  for (let n = walker.nextNode(); n && out.length < 12; n = walker.nextNode()) {
    const el = n.parentElement;
    if (!el || el.closest('script, style, noscript, [aria-hidden="true"], [translate="no"], [lang]:not(html):not([lang^="fr"])')) continue;
    if (el.getClientRects().length === 0 && !el.closest('.sr-only, .nl-sr-only, .visually-hidden')) continue;
    consider(n.textContent ?? '', el.tagName.toLowerCase());
  }
  for (const el of Array.from(document.querySelectorAll('[aria-label], [title], input[placeholder], textarea[placeholder]'))) {
    if (out.length >= 12) break;
    for (const attr of ['aria-label', 'title', 'placeholder']) { const v = el.getAttribute(attr); if (v) consider(v, `${el.tagName.toLowerCase()}[${attr}]`); }
  }
  return out;
}

/** One test per route of `app` (each app's project runs its own spec file: consumer|studio|console.spec.ts). */
export function shotsFor(app: App) {
  for (const r of ROUTES[app as keyof typeof ROUTES]) test(r.name, async ({ page }) => shoot(page, app, r));
}
