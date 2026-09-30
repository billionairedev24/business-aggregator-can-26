import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Tests render features and the shell with Testing Library (jsdom); routes are exercised through a memory router.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom', globals: true, setupFiles: ['./src/test/setup.ts'], css: false,
    include: ['src/**/*.test.{ts,tsx}'],
    testTimeout: 20_000,
    hookTimeout: 20_000,
  },
});
