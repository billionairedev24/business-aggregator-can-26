import { expect, test } from '@playwright/test';
import { HYDRATION, mockApi } from '../pages/support';
import { ROUTES } from './routes';
import { libraryPhotos } from './photos';

/**
 * S-182: no React hydration error on any consumer route (`make ui-hydration`, and every `make ui-shots` run). Each
 * route of routes.ts is server-rendered by the built consumer site with the ui-shots demo data, opened in Chromium
 * at a desktop and a phone width, in English and French, and fails on any hydration warning or error (React's
 * production codes #418–#425 or the development wording): the server's HTML and the browser's first render must be
 * the same, so time-based text (`ago()`, "today", the delivery cut-off) is rendered from the same clock on both sides.
 */
for (const r of ROUTES.consumer) {
  test(r.name, async ({ page }) => {
    await mockApi(page, 'consumer', { signedIn: r.signedIn ?? false, down: r.down });
    await libraryPhotos(page);
    const seen: string[] = [];
    page.on('console', m => { if ((m.type() === 'error' || m.type() === 'warning') && HYDRATION.test(m.text())) seen.push(m.text().slice(0, 400)); });
    page.on('pageerror', e => { if (HYDRATION.test(e.message)) seen.push(e.message.slice(0, 400)); });
    for (const [width, height] of [[1440, 900], [390, 844]] as const) {
      for (const lang of ['en', 'fr']) {
        await page.setViewportSize({ width, height });
        await page.goto(`${r.path}${r.path.includes('?') ? '&' : '?'}lang=${lang}`, { waitUntil: 'networkidle' }).catch(() => undefined);
        await page.locator(r.ready ?? 'h1').first().waitFor({ state: 'visible', timeout: 8_000 }).catch(() => undefined);
        await page.waitForTimeout(300);
        if (seen.length) seen.push(`  ↑ at ${width}px, ${lang}`);
      }
    }
    expect(seen, `${r.path}: React hydration errors`).toEqual([]);
  });
}
