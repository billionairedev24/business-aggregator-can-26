import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { brandHtml } from '@northline/tokens/vite';

/** The component gallery (S-168): a dev-only page of every @northline/ui component; not deployed. */
export default defineConfig({
  plugins: [react(), brandHtml()],
  server: { port: 3400 },
  preview: { port: 4677 },
  test: { environment: 'jsdom', globals: true, css: false, setupFiles: ['./src/test-setup.ts'], include: ['src/**/*.test.{ts,tsx}'] },
});
