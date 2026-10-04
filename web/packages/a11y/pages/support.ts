import { mkdirSync, writeFileSync } from 'node:fs';
import AxeBuilder from '@axe-core/playwright';
import { expect, type Page } from '@playwright/test';
// @ts-expect-error — plain ES modules shared with the Node mock api
import { responder } from '../scripts/responder.mjs';
// @ts-expect-error — plain ES modules shared with the Node mock api
import { setSignedIn } from '../scripts/overrides.mjs';

export type App = 'studio' | 'console' | 'consumer';
export type Locale = 'en' | 'fr';

const RESULTS = new URL('../a11y-results/pages/', import.meta.url);
mkdirSync(RESULTS, { recursive: true });

/** WCAG 2.0–2.2 A/AA, plus axe's best practices (reported, never failing). */
const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice'];
const WCAG = /^wcag2/;

/** Answers /api and /bff (any origin: northline-auth too) from the mock api; fonts are not fetched. Returns the misses. */
export async function mockApi(page: Page, app: App, { signedIn }: { signedIn?: boolean } = {}): Promise<string[]> {
  if (signedIn !== undefined) {
    setSignedIn(app, signedIn);
    if (app === 'consumer') await fetch(`http://127.0.0.1:4603/__mock/state?signedIn=${signedIn}`, { method: 'POST' });
  }
  const answer = responder(app) as (m: string, u: string) => { status: number; body: unknown; missing?: boolean };
  const missing: string[] = [];
  await page.route(url => /^\/(api|bff|oauth2)\//.test(url.pathname) || url.hostname.endsWith('fonts.googleapis.com') || url.hostname.endsWith('fonts.gstatic.com'), async route => {
    const req = route.request();
    const url = new URL(req.url());
    if (url.hostname.includes('fonts.g')) return route.abort();
    const res = answer(req.method(), url.pathname + url.search);
    if (res.missing) missing.push(`${req.method()} ${url.pathname}${url.search}`);
    return route.fulfill({ status: res.status, contentType: 'application/json', body: res.body === undefined || res.body === null ? '' : JSON.stringify(res.body) });
  });
  return missing;
}

/** Opens `path` in `locale` and waits for the page heading. */
export async function open(page: Page, app: App, path: string, locale: Locale, ready = 'h1') {
  if (app !== 'consumer') await page.addInitScript(l => { try { localStorage.setItem('nl.locale', l); } catch { /* ignore */ } }, locale);
  const url = app === 'consumer' ? `${path}${path.includes('?') ? '&' : '?'}lang=${locale}` : path;
  await page.goto(url, { waitUntil: 'networkidle' });
  await page.locator(ready).first().waitFor({ state: 'visible', timeout: 20_000 });
  await page.waitForTimeout(300);
}

interface Finding { id: string; impact: string; wcag: boolean; help: string; nodes: number; targets: string[] }
export interface PageReport {
  name: string; path: string; locale: Locale; width: number; heading: string; errorState: boolean;
  violations: Finding[];
  lang: string; reflowOverflow: boolean; textSpacingOverflow: boolean; obscuredFocus: string[];
  runningAnimationsWithReducedMotion: number; targetsUnder44: number; smallTargets: string[]; missingApi: string[];
}

/**
 * Everything the sweep checks on one page at one width: axe (WCAG A/AA + best practice), <html lang>, no horizontal
 * scroll (1.4.10 reflow at 320 px), text spacing (1.4.12), focus not hidden by a sticky bar (2.4.11), no running
 * animation under reduced motion, and how many targets miss the project's 44 px. Writes a11y-results/pages/<name>.json.
 */
export async function check(page: Page, name: string, path: string, locale: Locale, missingApi: string[]): Promise<PageReport> {
  const width = page.viewportSize()?.width ?? 1280;
  const axe = await new AxeBuilder({ page }).withTags(TAGS).analyze();
  const violations: Finding[] = axe.violations.map(v => ({
    id: v.id, impact: v.impact ?? 'minor', wcag: v.tags.some(t => WCAG.test(t)), help: v.help, nodes: v.nodes.length,
    targets: v.nodes.slice(0, 4).map(n => String(n.target[0]).slice(0, 120)),
  }));
  const lang = await page.evaluate(() => document.documentElement.lang);
  const heading = await page.evaluate(() => document.querySelector('h1')?.textContent?.trim().slice(0, 100) ?? '');
  const errorState = await page.evaluate(() => !!document.querySelector('.nl-errorstate, .nl-dt-error'));
  const reflowOverflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
  const smallTargets = await targetsUnder(page, 44);
  const targetsUnder44 = smallTargets.length;
  const obscuredFocus = await focusObscured(page);
  const runningAnimationsWithReducedMotion = await reducedMotion(page);
  const textSpacingOverflow = await textSpacing(page);
  const report: PageReport = { name, path, locale, width, heading, errorState, violations, lang, reflowOverflow, textSpacingOverflow, obscuredFocus, runningAnimationsWithReducedMotion, targetsUnder44, smallTargets: smallTargets.slice(0, 40), missingApi: [...new Set(missingApi)] };
  writeFileSync(new URL(`${name}.json`, RESULTS), JSON.stringify(report, null, 1));
  return report;
}

/**
 * Controls whose pointer target is smaller than `min` px (docs/SCREENS.md: 44; WCAG 2.5.8 asks 24). The target is
 * what a pointer hits: a control may be drawn smaller and reach `min` with a hit area (a pseudo-element, a label
 * around a checkbox), so each small control is probed with elementFromPoint `min/2 - 1` px left, right, above and below
 * its centre. Exempt (as WCAG's): links and link-styled buttons inside a sentence, and visually hidden elements
 * (≤ 1 px: screen-reader-only controls). S-143.
 */
async function targetsUnder(page: Page, min: number): Promise<string[]> {
  const found: string[] = await page.evaluate(async m => {
    const r0 = (el: Element) => el.getBoundingClientRect();
    // WCAG 2.5.8's inline exception: a link or link-styled button inside a sentence (its parent has text of its own)
    const inSentence = (el: HTMLElement) => {
      if (el.tagName !== 'A' && el.tagName !== 'BUTTON') return false;
      const parent = el.parentElement;
      return !!parent && [...parent.childNodes].some(n => n.nodeType === Node.TEXT_NODE && (n.textContent ?? '').trim().length > 1)
        && getComputedStyle(parent).display !== 'flex' && getComputedStyle(parent).display !== 'grid';
    };
    const hits = (el: HTMLElement) => {
      const r = r0(el);
      const cx = r.left + r.width / 2, cy = r.top + r.height / 2, d = m / 2 - 1;
      return [[cx - d, cy], [cx + d, cy], [cx, cy - d], [cx, cy + d]].every(([x, y]) => {
        const top = document.elementFromPoint(x!, y!);
        return !!top && (top === el || el.contains(top) || (top.tagName === 'LABEL' && top.contains(el)) || (el as HTMLInputElement).labels?.[0]?.contains(top) === true);
      });
    };
    const out: string[] = [];
    const all = [...document.querySelectorAll<HTMLElement>('a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],[role=tab],[role=menuitem],[role=radio],[role=checkbox],[role=switch],[role=link]')];
    for (const el of all) {
      const r = r0(el);
      if (r.width <= 1 || r.height <= 1 || getComputedStyle(el).visibility === 'hidden') continue;
      if (r.width >= m && r.height >= m) continue;
      if (inSentence(el) || el.closest('[aria-hidden="true"], [inert]')) continue;
      el.scrollIntoView({ block: 'center', inline: 'center' });
      await new Promise(requestAnimationFrame);
      if (hits(el)) continue;
      const name = (el.getAttribute('aria-label') ?? el.textContent ?? '').trim().replace(/\s+/g, ' ').slice(0, 30);
      const cls = typeof el.className === 'string' ? el.className.trim().split(/\s+/).slice(0, 2).join('.') : '';
      out.push(`${el.tagName.toLowerCase()}${cls ? '.' + cls : ''} "${name}" ${Math.round(r.width)}×${Math.round(r.height)}`);
    }
    window.scrollTo(0, 0);
    return out;
  }, min);
  return found;
}

/** Tabs through up to 30 stops; a stop is obscured when a sticky or fixed element covers all of its sample points. */
async function focusObscured(page: Page): Promise<string[]> {
  const hidden: string[] = [];
  await page.evaluate(() => { (document.activeElement as HTMLElement | null)?.blur(); window.scrollTo(0, 0); });
  for (let i = 0; i < 30; i++) {
    await page.keyboard.press('Tab');
    const res = await page.evaluate(() => {
      const el = document.activeElement as HTMLElement | null;
      if (!el || el === document.body) return null;
      const r = el.getBoundingClientRect();
      if (!r.width || !r.height) return null;
      const pts = [[r.left + r.width / 2, r.top + r.height / 2], [r.left + 2, r.top + 2], [r.right - 2, r.bottom - 2]];
      const visible = pts.some(([x, y]) => { if (x! < 0 || y! < 0 || x! > innerWidth || y! > innerHeight) return false; const top = document.elementFromPoint(x!, y!); return !!top && (el === top || el.contains(top) || top.contains(el)); });
      const label = `${el.tagName.toLowerCase()}${el.id ? '#' + el.id : ''} "${(el.getAttribute('aria-label') ?? el.textContent ?? '').trim().slice(0, 40)}"`;
      return { visible, label, offscreen: r.bottom < 0 || r.top > innerHeight };
    });
    if (res && !res.visible && !res.offscreen) hidden.push(res.label);
  }
  await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
  return hidden;
}

async function reducedMotion(page: Page): Promise<number> {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.waitForTimeout(100);
  const n = await page.evaluate(() => document.getAnimations().filter(a => a.playState === 'running' && Number(a.effect?.getComputedTiming().duration ?? 0) > 1).length);
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  return n;
}

/** WCAG 1.4.12's bookmarklet values; reports a horizontal scroll they cause, of the page or of a Data Table. */
async function textSpacing(page: Page): Promise<boolean> {
  const style = await page.addStyleTag({ content: '* { line-height: 1.5 !important; letter-spacing: .12em !important; word-spacing: .16em !important; } p { margin-bottom: 2em !important; }' });
  await page.waitForTimeout(400); // the Data Table re-measures its columns on resize (S-141)
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1
    // nor may a Data Table scroll sideways inside its own box
    || [...document.querySelectorAll<HTMLElement>('.nl-dt-table')].some(t => t.scrollWidth > (t.parentElement?.clientWidth ?? Infinity) + 1));
  await style.evaluate(el => (el as Element).remove());
  return overflow;
}

/** Best-practice rules the sweep holds every page to (S-142): headings in order, one h1 even in error states. */
const HEADINGS = new Set(['heading-order', 'page-has-heading-one']);

/**
 * What fails the sweep: WCAG violations rated critical or serious, a page without its language, horizontal scroll at
 * 320 px (1.4.10) or with the 1.4.12 text spacing at any width (S-141), skipped heading levels or no h1 (S-142), and any
 * control under the 44 px target (S-143).
 */
export function expectNoBlockers(report: PageReport, expectedLang: string) {
  const blockers = report.violations.filter(v => (v.wcag && (v.impact === 'critical' || v.impact === 'serious')) || HEADINGS.has(v.id));
  expect.soft(blockers.map(b => `${b.impact} ${b.id} (${b.nodes}): ${b.targets.join(' | ')}`), `${report.name}: critical/serious WCAG violations, heading order`).toEqual([]);
  expect.soft(report.lang, `${report.name}: <html lang>`).toBe(expectedLang);
  if (report.width <= 320) expect.soft(report.reflowOverflow, `${report.name}: horizontal scroll at ${report.width} px`).toBe(false);
  expect.soft(report.textSpacingOverflow, `${report.name}: horizontal scroll with the 1.4.12 text spacing`).toBe(false);
  expect.soft(report.smallTargets, `${report.name}: controls under 44 px`).toEqual([]);
}

/** One journey screen: desktop in English, then a 320 px phone in French. */
export async function sweep(page: Page, app: App, name: string, path: string, opts: { ready?: string; signedIn?: boolean } = {}) {
  const missing = await mockApi(page, app, { signedIn: opts.signedIn });
  await page.setViewportSize({ width: 1280, height: 900 });
  await open(page, app, path, 'en', opts.ready);
  expectNoBlockers(await check(page, `${app}-${name}-en-1280`, path, 'en', missing), 'en-CA');
  await page.setViewportSize({ width: 320, height: 720 });
  await open(page, app, path, 'fr', opts.ready);
  expectNoBlockers(await check(page, `${app}-${name}-fr-320`, path, 'fr', missing), 'fr-CA');
}
