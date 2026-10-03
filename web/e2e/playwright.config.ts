import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { defineConfig } from '@playwright/test';

// One id for the whole run, shared by every worker process (they inherit the environment).
process.env.E2E_RUN_ID ??= new Date().toISOString().replace(/\D/g, '').slice(2, 14);
const { env } = await import('./src/env');

/**
 * The end-to-end suite (S-117) — docs/runbooks/e2e.md. `make e2e` starts the stack and runs it (local profile);
 * `make e2e-target ENV=staging` runs it against a deployed environment. Journeys run one at a time (workers: 1): they
 * share personas, and the stack is a laptop's.
 *
 * Chromium: CHROMIUM=/path, else Playwright's own (PLAYWRIGHT_BROWSERS_PATH; `make e2e` points it at /opt/pw-browsers
 * when that exists). Nothing waits a fixed time: every step waits on what the next screen shows.
 */
const chromium = process.env.CHROMIUM ?? (!process.env.PLAYWRIGHT_BROWSERS_PATH && existsSync('/opt/pw-browsers/chromium') ? '/opt/pw-browsers/chromium' : undefined);

export default defineConfig({
  testDir: 'tests',
  outputDir: join(env.out, 'artifacts'),
  workers: 1,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0, // a flaky step is a bug to fix, not to retry (runbook § Flake triage)
  timeout: 5 * 60_000,
  expect: { timeout: 20_000 },
  reporter: [
    ['list'],
    ['html', { outputFolder: join(env.out, 'report'), open: 'never' }],
    ['junit', { outputFile: join(env.out, 'junit.xml') }],
    ['json', { outputFile: join(env.out, 'results.json') }],
  ],
  use: {
    launchOptions: { executablePath: chromium },
    viewport: { width: 1280, height: 900 },
    locale: 'en-CA',
    timezoneId: env.data.market.timeZone,
    actionTimeout: 20_000,
    navigationTimeout: 60_000,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    { name: 'sign-in', testMatch: /sign-in\.setup\.ts/ },
    { name: 'journeys', testMatch: /.*\.spec\.ts/, dependencies: ['sign-in'] },
  ],
});
