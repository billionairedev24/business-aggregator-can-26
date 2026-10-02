import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';
import { storybookTest } from '@storybook/addon-vitest/vitest-plugin';

const dirname = path.dirname(fileURLToPath(import.meta.url));

// Three projects:
//  - unit: pure logic (`pnpm test`), Node environment.
//  - dom: every component rendered with Testing Library in jsdom and checked with axe (S-109; `pnpm test`).
//  - storybook: every story rendered in headless Chromium (Playwright) with its play function (interaction tests)
//    and the a11y addon (`parameters.a11y.test: 'error'` in .storybook/preview.tsx fails on violations).
//    Run with `pnpm test-storybook`; needs the tokens build and a Playwright Chromium.
export default defineConfig({
  test: {
    projects: [
      {
        test: {
          name: 'unit',
          include: ['src/**/*.test.ts'],
          environment: 'node',
        },
      },
      {
        test: {
          name: 'dom',
          include: ['src/**/*.test.tsx'],
          environment: 'jsdom',
          setupFiles: ['src/test/setup.ts'],
          css: false,
          testTimeout: 20_000,
        },
      },
      {
        plugins: [storybookTest({ configDir: path.join(dirname, '.storybook') })],
        test: {
          name: 'storybook',
          browser: {
            enabled: true,
            headless: true,
            provider: 'playwright',
            instances: [{ browser: 'chromium' }],
          },
          setupFiles: ['.storybook/vitest.setup.ts'],
        },
      },
    ],
  },
});
