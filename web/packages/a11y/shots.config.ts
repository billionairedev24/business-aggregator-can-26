import { existsSync } from 'node:fs';
import { defineConfig } from '@playwright/test';

/**
 * `make ui-shots` (docs/LOCAL_DEVELOPMENT.md § "Look at the UI"): full-page screenshots of every route of the three
 * built apps at desktop and phone sizes, into ui-shots/ at the repository root. The a11y sweep's servers on their own
 * ports (4641 Studio, 4642 console, 4644 consumer, 4643 the mock api behind the consumer's server-side rendering), so
 * both can run side by side; the mock api adds the demo-like data of shots/fixtures.mjs (NL_MOCK_SHOTS=1). No backend.
 * UI_SHOTS_APPS=consumer,studio limits the apps; `--grep <route>` limits the routes. UI_SHOTS_HYDRATION=1 (`make
 * ui-hydration`) runs the hydration check of every consumer route instead (shots/hydration.spec.ts, S-182).
 */
// read by pages/support.ts and scripts/responder.mjs in the test workers
process.env.NL_A11Y_MOCK_URL = 'http://127.0.0.1:4643';
process.env.NL_MOCK_SHOTS = '1';
const chromium = process.env.CHROMIUM ?? (existsSync('/opt/pw-browsers/chromium') ? '/opt/pw-browsers/chromium' : undefined);
const web = new URL('../..', import.meta.url).pathname;
const nowhere = 'http://127.0.0.1:4699';
const only = process.env.UI_SHOTS_APPS?.split(',');
const wanted = (app: string) => !only || only.includes(app);
const hydration = process.env.UI_SHOTS_HYDRATION === '1';

export default defineConfig({
  testDir: 'shots',
  globalSetup: './shots/setup.ts',
  outputDir: 'a11y-results/shots-artifacts',
  workers: 1,
  fullyParallel: false,
  timeout: 90_000,
  reporter: [['list']],
  use: { launchOptions: { executablePath: chromium }, trace: 'off', deviceScaleFactor: 1 },
  webServer: [
    ...(wanted('studio') ? [{ command: 'pnpm --filter @northline/studio exec vite preview --port 4641 --strictPort --host 127.0.0.1', cwd: web, url: 'http://127.0.0.1:4641/', env: { NL_BFF: nowhere }, reuseExistingServer: false, timeout: 60_000 }] : []),
    ...(wanted('console') ? [{ command: 'pnpm --filter @northline/console exec vite preview --port 4642 --strictPort --host 127.0.0.1', cwd: web, url: 'http://127.0.0.1:4642/', env: { NL_BFF: nowhere }, reuseExistingServer: false, timeout: 60_000 }] : []),
    ...(wanted('consumer') ? [
      { command: 'node scripts/mock-api.mjs consumer 4643', url: 'http://127.0.0.1:4643/__mock/health', env: { NL_MOCK_SHOTS: '1' }, reuseExistingServer: false },
      {
        command: 'node server/node-server.mjs', cwd: `${web}apps/consumer`, url: 'http://127.0.0.1:4644/healthz', reuseExistingServer: false, timeout: 60_000,
        env: { PORT: '4644', HOST: '127.0.0.1', NL_BFF_URL: 'http://127.0.0.1:4643', NL_SITE_ORIGIN: 'http://127.0.0.1:4644', NL_ERROR_PREVIEW: '1' },
      },
    ] : []),
  ],
  projects: hydration ? [{ name: 'hydration', testMatch: /hydration\.spec\.ts/, use: { baseURL: 'http://127.0.0.1:4644' } }] : [
    ...(wanted('consumer') ? [{ name: 'consumer', testMatch: /consumer\.spec\.ts/, use: { baseURL: 'http://127.0.0.1:4644' } }] : []),
    ...(wanted('studio') ? [{ name: 'studio', testMatch: /studio\.spec\.ts/, use: { baseURL: 'http://127.0.0.1:4641' } }] : []),
    ...(wanted('console') ? [{ name: 'console', testMatch: /console\.spec\.ts/, use: { baseURL: 'http://127.0.0.1:4642' } }] : []),
  ],
});
