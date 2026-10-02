import { defineConfig } from 'vitest/config';

// The helper's own tests (jsdom). The Playwright page sweep is `pnpm a11y` (playwright.config.ts), not vitest.
export default defineConfig({ test: { environment: 'jsdom', include: ['src/**/*.test.ts'] } });
