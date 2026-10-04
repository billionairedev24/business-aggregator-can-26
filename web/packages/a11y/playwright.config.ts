import { existsSync } from 'node:fs';
import { defineConfig } from '@playwright/test';

/**
 * Page-level accessibility sweep (S-109, `make a11y`): Playwright + axe against the BUILT apps — the Studio and the
 * console with `vite preview`, the consumer site with its Node server — answered by a mock api replaying what the apps'
 * own tests answer (fixtures/, scripts/responder.mjs). Build first (`make a11y` does).
 *
 * Chromium: CHROMIUM=/path, else the sandbox's /opt/pw-browsers/chromium, else Playwright's own (`playwright install`
 * on a laptop or in CI). Ports 4610/4620/4630 (apps) and 4603 (mock api for the consumer's server-side rendering).
 */
const chromium = process.env.CHROMIUM ?? (existsSync('/opt/pw-browsers/chromium') ? '/opt/pw-browsers/chromium' : undefined);
const web = new URL('../..', import.meta.url).pathname;
const reuse = !process.env.CI;
// /api and /bff are answered in the browser by the sweep's routes; this is where vite preview would proxy anything else.
const nowhere = 'http://127.0.0.1:4699';
// NL_A11Y_APPS=consumer starts only the consumer's servers (`make csp-check` builds nothing else)
const only = process.env.NL_A11Y_APPS?.split(',');
const wanted = (app: string) => !only || only.includes(app);

export default defineConfig({
  testDir: 'pages',
  outputDir: 'a11y-results/artifacts',
  workers: 1,
  fullyParallel: false,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: [['list'], ['json', { outputFile: 'a11y-results/results.json' }]],
  use: { launchOptions: { executablePath: chromium }, trace: 'off', screenshot: 'only-on-failure' },
  webServer: [
    ...(wanted('studio') ? [{ command: 'pnpm --filter @northline/studio exec vite preview --port 4610 --strictPort --host 127.0.0.1', cwd: web, url: 'http://127.0.0.1:4610/', env: { NL_BFF: nowhere }, reuseExistingServer: reuse, timeout: 60_000 }] : []),
    ...(wanted('console') ? [{ command: 'pnpm --filter @northline/console exec vite preview --port 4620 --strictPort --host 127.0.0.1', cwd: web, url: 'http://127.0.0.1:4620/', env: { NL_BFF: nowhere }, reuseExistingServer: reuse, timeout: 60_000 }] : []),
    { command: 'node scripts/mock-api.mjs consumer 4603', url: 'http://127.0.0.1:4603/__mock/health', reuseExistingServer: reuse },
    {
      command: 'node server/node-server.mjs', cwd: `${web}apps/consumer`, url: 'http://127.0.0.1:4630/healthz', reuseExistingServer: reuse, timeout: 60_000,
      env: { PORT: '4630', HOST: '127.0.0.1', NL_BFF_URL: 'http://127.0.0.1:4603', NL_SITE_ORIGIN: 'http://127.0.0.1:4630' },
    },
  ],
  projects: [
    { name: 'studio', testMatch: /studio\.spec\.ts/, use: { baseURL: 'http://127.0.0.1:4610' } },
    { name: 'console', testMatch: /console\.spec\.ts/, use: { baseURL: 'http://127.0.0.1:4620' } },
    { name: 'consumer', testMatch: /consumer\.spec\.ts/, use: { baseURL: 'http://127.0.0.1:4630' } },
    // S104-09: the consumer site's nonce-based CSP in a real browser (also `make csp-check`)
    { name: 'csp', testMatch: /csp\.spec\.ts/, use: { baseURL: 'http://127.0.0.1:4630' } },
  ],
});
