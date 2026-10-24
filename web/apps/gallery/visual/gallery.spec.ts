import { expect, test, type Page } from '@playwright/test';
import { GALLERY_IDS, SOLO_IDS } from './sections';

/** Every gallery section, both languages, desktop and phone; then the overlays open (S-168, `pnpm test:visual`). */
const WIDTHS = [{ name: 'd', width: 1440, height: 900 }, { name: 'm', width: 390, height: 844 }] as const;
const LANGS = ['en', 'fr'] as const;
/**
 * S-180, deterministic baselines: one instant for every run ("today", "2 h ago", a countdown read the same clock on a
 * laptop and in CI), with the zone (UTC), the locale, the scale and reduced motion set in playwright.config.ts.
 */
const NOW = new Date('2026-10-23T18:00:00Z');
test.beforeEach(async ({ page }) => { await page.clock.setFixedTime(NOW); });

async function ready(page: Page, url: string) {
  await page.goto(url, { waitUntil: 'networkidle' });
  // S-188: a face loads only once something draws in it, which can be after `fonts.ready` first settles (the display
  // face of the hero band, a weight further down): ask for every one, so a baseline never catches a face mid-swap
  await page.evaluate(async () => {
    await Promise.all([...['400', '500', '600', '700'].map(w => document.fonts.load(`${w} 14px "IBM Plex Sans"`)), document.fonts.load('800 66px "Archivo"')]);
    await document.fonts.ready;
  });
  // the fonts are IBM Plex Sans and Archivo, self-hosted: a fallback face in a baseline would hide a broken font load
  expect(await page.evaluate(() => document.fonts.check('14px "IBM Plex Sans"') && document.fonts.check('800 66px "Archivo"'))).toBe(true);
}

for (const w of WIDTHS) {
  for (const lang of LANGS) {
    test(`gallery ${lang} ${w.width}`, async ({ page }) => {
      await page.setViewportSize({ width: w.width, height: w.height });
      await ready(page, `/?lang=${lang}`);
      // nothing sticks out sideways (a sideways scroll on a phone is a broken layout)
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(w.width);
      for (const id of GALLERY_IDS.filter(x => !SOLO_IDS.includes(x))) {
        await expect(page.locator(`[data-gallery="${id}"]`), id).toHaveScreenshot(`${id}-${lang}-${w.name}.png`);
      }
    });
    for (const id of SOLO_IDS) {
      test(`${id} ${lang} ${w.width}`, async ({ page }) => {
        await page.setViewportSize({ width: w.width, height: w.height });
        await ready(page, `/?lang=${lang}&section=${id}`);
        expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(w.width);
        await expect(page.locator(`[data-gallery="${id}"]`), id).toHaveScreenshot(`${id}-${lang}-${w.name}.png`);
      });
    }
    for (const open of ['dialog', 'sheet'] as const) {
      test(`${open} ${lang} ${w.width}`, async ({ page }) => {
        await page.setViewportSize({ width: w.width, height: w.height });
        await ready(page, `/?lang=${lang}&section=overlays&open=${open}`);
        await expect(page.getByRole('dialog')).toBeVisible();
        await expect(page).toHaveScreenshot(`overlays-${open}-${lang}-${w.name}.png`);
      });
    }
  }
}
