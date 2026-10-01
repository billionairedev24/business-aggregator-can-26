import { defineConfig } from 'vitest/config';

// Unit tests of the site's own logic (src/**/*.test.ts). The build itself is checked by `pnpm test:build`
// (scripts/check-build.mjs) after `pnpm build` — make docs runs both.
export default defineConfig({
  test: { include: ['src/**/*.test.ts'], environment: 'node', globals: true },
});
