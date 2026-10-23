import { existsSync } from 'node:fs';
import { defineConfig } from '@playwright/test';

/**
 * `pnpm test:visual` (S-168): screenshot baselines of the component gallery — every section in en and fr-CA at 1440
 * and 390 wide, plus the dialog and the sheet open — against the built gallery (`vite preview`, port 4677, no
 * backend). A changed pixel fails; `pnpm --filter @northline/gallery test:visual:update` rewrites the baselines after
 * an intended change (commit them with it). Chromium from CHROMIUM, else /opt/pw-browsers/chromium (as `make ui-shots`).
 */
const chromium = process.env.CHROMIUM ?? (existsSync('/opt/pw-browsers/chromium') ? '/opt/pw-browsers/chromium' : undefined);
const here = new URL('..', import.meta.url).pathname;

export default defineConfig({
  testDir: '.',
  snapshotPathTemplate: '{testDir}/__screenshots__/{arg}{ext}',
  outputDir: '../node_modules/.visual-results',
  workers: 2,
  timeout: 60_000,
  reporter: [['list']],
  expect: { toHaveScreenshot: { maxDiffPixelRatio: 0.001, animations: 'disabled', caret: 'hide', scale: 'css' } },
  use: { baseURL: 'http://127.0.0.1:4677', launchOptions: { executablePath: chromium }, deviceScaleFactor: 1, colorScheme: 'light', locale: 'en-CA', timezoneId: 'UTC' },
  webServer: { command: 'pnpm build && pnpm exec vite preview --port 4677 --strictPort --host 127.0.0.1', cwd: here, url: 'http://127.0.0.1:4677/', reuseExistingServer: false, timeout: 180_000 },
});
