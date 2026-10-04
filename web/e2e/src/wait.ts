import { expect, type Locator, type Page } from '@playwright/test';

/**
 * Reloads until `expectation` holds — for state a background job changes (vetting, escrow release, a delivery). Each
 * round first waits, with the normal patience, for `ready` (the screen drawn: a dev server under load can take seconds
 * to render after a reload), then gives the expectation a short look. No fixed sleep: the rounds are Playwright's
 * `toPass` intervals.
 */
export async function reloadUntil(page: Page, ready: Locator, expectation: () => Promise<unknown>, timeout = 120_000) {
  await expect(async () => {
    await page.reload();
    await expect(ready).toBeVisible({ timeout: 30_000 });
    await expectation();
  }).toPass({ timeout, intervals: [1_000, 2_000, 5_000] });
}
