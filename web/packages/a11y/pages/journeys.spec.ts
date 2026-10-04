import { expect, test, type Page } from '@playwright/test';
import { mockApi, open, type App } from './support';

/**
 * S-148 (the automated part of the screen-reader pass): the accessibility tree of the key journeys' screens — what a
 * screen reader is given: landmarks, headings and their levels, every control's role, name and state — pinned as
 * Playwright ARIA snapshots (`journeys.spec.ts-snapshots/*.aria.yml`, reviewed like code). A change to a name, a role,
 * a heading level or a state fails here; `pnpm --filter @northline/a11y a11y -- --update-snapshots` records the new
 * tree for review. This reads the tree; it does not listen — docs/a11y/screen-reader-script.md is the manual pass.
 *
 * Volatile text (dates, relative times) is matched by regular expressions in the snapshot files.
 */
const PROVIDER = '01J9ZD3V00000000000000PWM1', KITCHEN = '01J9ZD3V00000000000000PDB1';

interface Journey { app: App; name: string; path: string; signedIn: boolean }
const JOURNEYS: Journey[] = [
  // Studio: sign-in → KDS, finance
  { app: 'studio', name: 'studio-sign-in', path: '/sign-in', signedIn: false },
  { app: 'studio', name: 'studio-kds', path: `/b/${KITCHEN}/kitchen/live`, signedIn: true },
  { app: 'studio', name: 'studio-payouts', path: `/b/${PROVIDER}/payouts`, signedIn: true },
  // consumer: search → product → cart and checkout; services → booking
  { app: 'consumer', name: 'consumer-home', path: '/', signedIn: false },
  { app: 'consumer', name: 'consumer-search', path: '/search?q=sourdough', signedIn: false },
  { app: 'consumer', name: 'consumer-product', path: '/products/P1', signedIn: false },
  { app: 'consumer', name: 'consumer-checkout', path: '/cart', signedIn: true },
  { app: 'consumer', name: 'consumer-booking', path: '/providers/prairie-wrench/book', signedIn: true },
  { app: 'consumer', name: 'consumer-help', path: '/help', signedIn: false },
  // console: the queues
  { app: 'console', name: 'console-verification', path: '/verification', signedIn: true },
  { app: 'console', name: 'console-support', path: '/support', signedIn: true },
];

const BASE: Record<App, string> = { studio: 'http://127.0.0.1:4610', console: 'http://127.0.0.1:4620', consumer: 'http://127.0.0.1:4630' };

async function visit(page: Page, j: Journey) {
  await mockApi(page, j.app, { signedIn: j.signedIn });
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await open(page, j.app, j.path, 'en');
}

for (const j of JOURNEYS) {
  test(`${j.name}: landmarks, headings, names, roles and states`, async ({ page, baseURL }) => {
    test.skip(baseURL !== BASE[j.app], `runs in the ${j.app} project`);
    await visit(page, j);
    // one h1, a main landmark, the page language
    await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1);
    await expect(page.getByRole('main')).toHaveCount(1);
    await expect(page.getByRole('main')).toMatchAriaSnapshot({ name: `${j.name}.aria.yml` });
  });
}
