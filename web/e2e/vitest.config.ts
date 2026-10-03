import { defineConfig } from 'vitest/config';

// The suite's own helpers (TOTP, configuration). The journeys are Playwright's: `pnpm e2e` (make e2e), not vitest.
export default defineConfig({ test: { environment: 'node', include: ['src/**/*.test.ts'] } });
