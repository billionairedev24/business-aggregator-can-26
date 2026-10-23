import { expect, test, type Page } from '@playwright/test';
import { GALLERY_IDS } from './sections';

/** Every gallery section, both languages, desktop and phone; then the overlays open (S-168, `pnpm test:visual`). */
const WIDTHS = [{ name: 'd', width: 1440, height: 900 }, { name: 'm', width: 390, height: 844 }] as const;
const LANGS = ['en', 'fr'] as const;

async function ready(page: Page, url: string) {
  await page.goto(url, { waitUntil: 'networkidle' });
  await page.evaluate(() => document.fonts.ready);
  // the font is IBM Plex Sans, self-hosted: a fallback face in a baseline would hide a broken font load
  expect(await page.evaluate(() => document.fonts.check('14px "IBM Plex Sans"'))).toBe(true);
}

for (const w of WIDTHS) {
  for (const lang of LANGS) {
    test(`gallery ${lang} ${w.width}`, async ({ page }) => {
      await page.setViewportSize({ width: w.width, height: w.height });
      await ready(page, `/?lang=${lang}`);
      // nothing sticks out sideways (a sideways scroll on a phone is a broken layout)
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(w.width);
      for (const id of GALLERY_IDS) {
        await expect(page.locator(`[data-gallery="${id}"]`), id).toHaveScreenshot(`${id}-${lang}-${w.name}.png`);
      }
    });
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
