import { env } from '../src/env';
import { expect, test } from '../src/fixtures';

/**
 * Studio smoke (grown from scripts/studio-smoke.mjs, S-5): every Studio screen of the three seeded businesses, signed in
 * for real, at desktop and phone width in English and at desktop in French. A screen fails on a console error, an
 * /api call answering ≥ 400, the error state, no heading, or a horizontal scroll. All problems are listed at the end.
 * Local data only (the seeded businesses); `--grep-invert @smoke` leaves it out.
 */
const businesses = {
  provider: '01J9ZD3V00000000000000PWM1',
  seller: '01J9ZD3V00000000000000PWP1',
  kitchen: '01J9ZD3V00000000000000PDB1',
};
const screens: Record<keyof typeof businesses, string[]> = {
  provider: ['', 'appointments', 'messages', 'listings', 'listings/new', 'listings/bulk', 'availability', 'page', 'earnings', 'reports', 'payouts', 'refunds', 'compliance', 'reviews', 'settings', 'settings?tab=team', 'settings?tab=security', 'settings?tab=notifications', 'settings?tab=api', 'help'],
  seller: ['', 'orders', 'messages', 'listings', 'listings/new', 'page', 'earnings', 'payouts', 'refunds', 'compliance', 'reviews', 'settings'],
  kitchen: ['kitchen/live', 'kitchen/menu', 'kitchen/combos', 'kitchen/hours', 'messages', 'earnings', 'reports', 'payouts', 'compliance', 'reviews', 'page', 'settings', 'help'],
};

for (const [width, lang] of [[1280, 'en'], [375, 'en'], [1280, 'fr']] as const) {
  test(`Studio smoke · every screen at ${width} px in ${lang} @smoke`, async ({ owner }) => {
    test.skip(env.mode !== 'local', 'the seeded businesses exist only in the local dev seed');
    await owner.setViewportSize({ width, height: 900 });
    await owner.addInitScript(l => { try { localStorage.setItem('nl.locale', l); } catch { /* private mode */ } }, lang);
    let errors: string[] = [];
    owner.on('console', m => { if (m.type() === 'error' && !m.text().includes('ERR_CERT')) errors.push(m.text().slice(0, 200)); });
    owner.on('pageerror', e => errors.push(`page error: ${String(e).slice(0, 200)}`));
    owner.on('response', r => { if (r.url().includes('/api/') && r.status() >= 400) errors.push(`HTTP ${r.status()} ${new URL(r.url()).pathname}`); });
    const problems: string[] = [];
    for (const [kind, list] of Object.entries(screens) as [keyof typeof businesses, string[]][]) {
      for (const screen of list) {
        errors = [];
        const path = `/b/${businesses[kind]}${screen ? `/${screen}` : ''}`;
        await test.step(`${kind} · ${screen || 'dashboard'}`, async () => {
          await owner.goto(env.urls.studio + path);
          await expect(owner.locator('h1').first()).toBeVisible();
          const loaded = await expect(owner.locator('[aria-busy="true"]').first()).toBeHidden().then(() => true, () => false);
          const info = await owner.evaluate(() => ({
            hscroll: document.documentElement.scrollWidth > window.innerWidth + 1,
            errorState: !!document.querySelector('.nl-errorstate'),
          }));
          const found = [...new Set(errors), ...(loaded ? [] : ['still loading']), ...(info.hscroll ? ['horizontal scroll'] : []), ...(info.errorState ? ['error state'] : [])];
          if (found.length) problems.push(`${path}: ${found.join('; ')}`);
        });
      }
    }
    expect(problems, 'screens with problems').toEqual([]);
  });
}
