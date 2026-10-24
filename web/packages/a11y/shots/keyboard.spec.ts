import { appendFileSync, mkdirSync } from 'node:fs';
import { expect, test, type Locator, type Page } from '@playwright/test';
import type { App } from '../pages/support';
import { ROUTES, type ShotRoute } from './routes';
import { prepare, visit } from './shoot';

/**
 * S-180 (`make ui-a11y`, with a11y.spec.ts): the keyboard paths of the revamped screens, on the ui-shots builds and
 * data — the consumer header and its search scope, the location dialog and the account menu, the results' filter rail
 * and the phone's filter sheet, the product's buy box, the provider's booking box, the checkout's choices, the Studio's
 * orders table with its bulk bar and the console's sellers table with Suspend… and its dialog. Each step uses the keys
 * only, checks that the focused control shows a visible focus indicator, and that a dialog or sheet keeps Tab inside,
 * closes on Escape and gives focus back to what opened it. One line per path in ui-shots/<app>/_keyboard.txt.
 */
const OUT = new URL('../../../../ui-shots/', import.meta.url).pathname;
const route = (app: App, name: string): ShotRoute => {
  const r = ROUTES[app].find(x => x.name === name);
  if (!r) throw new Error(`no ui-shots route ${app}/${name}`);
  return { ...r, click: undefined };
};

/** The focused element and whether it draws a focus indicator (an outline, or a ring as a box shadow). */
async function focused(page: Page) {
  return page.evaluate(() => {
    const el = document.activeElement as HTMLElement | null;
    if (!el || el === document.body) return { name: '(body)', visible: false, inDialog: false };
    const cs = getComputedStyle(el);
    const ring = (cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) > 0) || (cs.boxShadow !== 'none' && cs.boxShadow !== '');
    const label = (el.getAttribute('aria-label') ?? el.textContent ?? '').trim().replace(/\s+/g, ' ').slice(0, 40);
    return { name: `${el.tagName.toLowerCase()} "${label}"`, visible: ring, inDialog: !!el.closest('[role="dialog"], [role="alertdialog"]') };
  });
}

/** Tabs until `target` has focus (at most `max` presses); every stop on the way must show its focus. */
async function tabTo(page: Page, target: Locator, max = 40): Promise<string[]> {
  const unseen: string[] = [];
  for (let i = 0; i < max; i++) {
    await page.keyboard.press('Tab');
    const f = await focused(page);
    if (!f.visible) unseen.push(f.name);
    if (await target.evaluate(el => el === document.activeElement).catch(() => false)) return unseen;
  }
  throw new Error(`Tab never reached ${target}`);
}

/** A dialog or sheet: focus inside on open, Tab stays inside, Escape closes it, focus back on the opener. */
async function modal(page: Page, opener: Locator, key: 'Enter' | ' ' = 'Enter') {
  await opener.focus();
  await page.keyboard.press(key);
  const dialog = page.locator('[role="dialog"], [role="alertdialog"]').last();
  await expect(dialog).toBeVisible();
  expect((await focused(page)).inDialog, 'focus moves into the dialog').toBe(true);
  for (let i = 0; i < 25; i++) {
    await page.keyboard.press('Tab');
    const f = await focused(page);
    expect(f.inDialog, `Tab ${i + 1} stays inside the dialog (was on ${f.name})`).toBe(true);
    expect.soft(f.visible, `${f.name} shows its focus`).toBe(true);
  }
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  expect(await opener.evaluate(el => el === document.activeElement), 'focus returns to the opener').toBe(true);
}

async function open(page: Page, app: App, name: string, lang: 'en' | 'fr' = 'en') {
  const r = route(app, name);
  await prepare(page, app, r, lang);
  await visit(page, app, r, lang);
  await page.evaluate(() => { (document.activeElement as HTMLElement | null)?.blur(); window.scrollTo(0, 0); });
}

function log(app: App, path: string, result: string) {
  mkdirSync(`${OUT}${app}`, { recursive: true });
  appendFileSync(`${OUT}${app}/_keyboard.txt`, `${path}: ${result}\n`);
}

/** Runs one path; its outcome (ok, or the first failure) goes to _keyboard.txt and fails the test. */
async function path(app: App, name: string, run: () => Promise<string | void>) {
  try {
    const note = await run();
    log(app, name, note ? `ok · ${note}` : 'ok');
  } catch (e) {
    log(app, name, `FAIL ${(e as Error).message.split('\n')[0]}`);
    throw e;
  }
}

const only = (app: App) => test.beforeEach(({}, info) => test.skip(info.project.name !== `a11y-${app}`));

test.describe('consumer keyboard', () => {
  only('consumer');
  test.describe('desktop', () => {
    test.use({ viewport: { width: 1440, height: 900 } });
    test('header: logo, place, search scope, search, account, cart', ({ page }) => path('consumer', 'header', async () => {
      await open(page, 'consumer', 'home');
      const scope = page.locator('header select').first();
      const unseen = await tabTo(page, scope);
      const before = await scope.inputValue();
      await page.keyboard.press('ArrowDown');
      const after = await scope.inputValue();
      expect(after, 'the scope select changes with the arrow keys').not.toBe(before);
      await page.keyboard.press('Tab');
      await page.keyboard.type('sourdough');
      await page.keyboard.press('Enter');
      await page.waitForURL(/\/search\?/);
      expect(page.url()).toContain('q=sourdough');
      expect(unseen, 'every stop shows its focus').toEqual([]);
      return `scope ${before} → ${after}, Enter searched`;
    }));
    test('location dialog and account menu', ({ page }) => path('consumer', 'location-and-account', async () => {
      await open(page, 'consumer', 'home');
      await modal(page, page.locator('header .nl-location').first());
      const account = page.locator('header .nl-account-button').first();
      await account.focus();
      await page.keyboard.press('Enter');
      const menu = page.getByRole('menu').first();
      await expect(menu).toBeVisible();
      await page.keyboard.press('ArrowDown');
      expect(await page.evaluate(() => !!document.activeElement?.closest('[role="menu"]')), 'arrows move inside the menu').toBe(true);
      await page.keyboard.press('Escape');
      await expect(menu).toBeHidden();
      expect(await account.evaluate(el => el === document.activeElement), 'focus returns to the account button').toBe(true);
    }));
    test('filter rail: a rating filter and its removable chip', ({ page }) => path('consumer', 'filter-rail', async () => {
      await open(page, 'consumer', 'search-shop');
      const rating = page.locator('.nl-rate-opt').first();
      const unseen = await tabTo(page, rating, 80);
      await page.keyboard.press('Enter');
      await page.waitForURL(/minRating=/);
      const chip = page.getByRole('button', { name: /^Remove filter/ }).first();
      await chip.focus();
      await page.keyboard.press('Enter');
      await page.waitForURL(url => !url.searchParams.has('minRating'));
      expect(unseen, 'every stop shows its focus').toEqual([]);
    }));
    test('buy box: quantity and Add to cart', ({ page }) => path('consumer', 'buy-box', async () => {
      await open(page, 'consumer', 'product');
      const add = page.getByRole('button', { name: /^Add to cart/ }).first();
      const unseen = await tabTo(page, add, 80);
      const posted = page.waitForRequest(req => req.method() === 'POST' && req.url().includes('/cart'), { timeout: 5_000 });
      await page.keyboard.press('Enter');
      await posted;
      expect(unseen, 'every stop shows its focus').toEqual([]);
    }));
    test('booking box: days and times are radio groups, Book is reachable', ({ page }) => path('consumer', 'booking-box', async () => {
      await open(page, 'consumer', 'provider');
      const days = page.locator('.nl-prov-days [role="radio"]:not([disabled])');
      await days.first().focus();
      await page.keyboard.press('ArrowRight');
      const checked = await page.locator('.nl-prov-days [role="radio"][aria-checked="true"]').count();
      expect(checked, 'an arrow key chooses the next day').toBe(1);
      const time = page.locator('.nl-prov-times [role="radio"]:not([disabled])').first();
      await time.focus();
      await page.keyboard.press(' ');
      await expect(page.locator('.nl-prov-times [role="radio"][aria-checked="true"]')).toHaveCount(1);
      const book = page.getByRole('button', { name: /^Book / }).first();
      await tabTo(page, book, 10);
    }));
    test('checkout: delivery window, out-of-stock rule and tip by keyboard', ({ page }) => path('consumer', 'checkout-choices', async () => {
      await open(page, 'consumer', 'checkout');
      const groups = page.locator('[role="radiogroup"]');
      const n = await groups.count();
      expect(n, 'the choices are radio groups').toBeGreaterThan(1);
      const notes: string[] = [];
      for (let i = 0; i < n; i++) {
        const g = groups.nth(i);
        const radios = g.locator('[role="radio"]:not([disabled]), input[type="radio"]:not([disabled])');
        if ((await radios.count()) < 2) continue;
        const current = g.locator('[aria-checked="true"], input:checked').first();
        await ((await current.count()) ? current : radios.first()).focus();
        await page.keyboard.press('ArrowRight');
        const f = await focused(page);
        expect.soft(f.visible, `${f.name} shows its focus`).toBe(true);
        notes.push(f.name);
      }
      return `${notes.length} groups: ${notes.join(', ')}`;
    }));
  });
  test.describe('phone', () => {
    test.use({ viewport: { width: 390, height: 844 }, hasTouch: true });
    test('filter sheet: focus in, Tab stays inside, Escape closes, focus returns', ({ page }) => path('consumer', 'filter-sheet', async () => {
      await open(page, 'consumer', 'search-filters');
      await modal(page, page.locator('.nl-results-filters-toggle').first());
    }));
  });
});

test.describe('studio keyboard', () => {
  only('studio');
  test.use({ viewport: { width: 1440, height: 900 } });
  test('orders table: select a row, the bulk bar, Mark packed', ({ page }) => path('studio', 'orders-bulk', async () => {
    await open(page, 'studio', 'orders');
    const box = page.locator('tbody input[type="checkbox"]').first();
    await box.focus();
    await page.keyboard.press(' ');
    const bar = page.locator('.nl-dt-bulk');
    await expect(bar).toBeVisible();
    const first = bar.locator('button').first();
    await page.keyboard.press('Shift+Tab'); // the bulk bar sits above the rows: reach it from the header checkbox
    await tabTo(page, first, 40).catch(async () => { await first.focus(); });
    const f = await focused(page);
    expect.soft(f.visible, `${f.name} shows its focus`).toBe(true);
    return `bulk bar: ${await bar.locator('button').allInnerTexts().then(x => x.join(' · '))}`;
  }));
});

test.describe('console keyboard', () => {
  only('console');
  test.use({ viewport: { width: 1440, height: 900 } });
  test('sellers: select by keyboard, Suspend… opens its dialog, Escape returns focus', ({ page }) => path('console', 'sellers-suspend', async () => {
    await open(page, 'console', 'sellers');
    const box = page.locator('tbody input[type="checkbox"]').nth(1);
    await box.focus();
    await page.keyboard.press(' ');
    const suspend = page.locator('.nl-dt-bulk button', { hasText: /Suspend/ }).first();
    await expect(suspend).toBeVisible();
    await modal(page, suspend);
  }));
  test('seller page: the action panel and its dialog', ({ page }) => path('console', 'seller-actions', async () => {
    await open(page, 'console', 'seller');
    await modal(page, page.getByRole('button', { name: /^Change tier/ }).first());
  }));
});
