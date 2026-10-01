import { defineConfig, loadEnv, type Plugin, type ProxyOptions } from 'vite';
import react from '@vitejs/plugin-react';
import { tanstackRouter } from '@tanstack/router-plugin/vite';
import { legalPages } from '@northline/legal/vite';

/**
 * Local "dev auth": when NL_DEV_USER is set (a seeded identity.users id), /api calls go straight to the api with
 * X-Dev-User (accepted only by the api's `local` profile) and /bff/session answers with that user — so screens can be
 * built without running auth + bff. Without NL_DEV_USER everything goes through the studio BFF like production.
 */
function devAuth(env: Record<string, string>): Plugin {
  return {
    name: 'northline-dev-auth',
    configureServer(server) {
      if (!env.NL_DEV_USER) return;
      server.middlewares.use('/bff/session', (_req, res) => {
        const [firstName = 'Dev', lastName = 'User'] = (env.NL_DEV_USER_NAME ?? 'Ravi Sandhu').split(' ');
        res.setHeader('content-type', 'application/json');
        res.end(JSON.stringify({ user: { id: env.NL_DEV_USER, firstName, lastName, email: env.NL_DEV_USER_EMAIL ?? 'ravi@prairiewrench.ca', initials: (firstName[0]! + lastName[0]!).toUpperCase(), locale: 'en-CA', memberSince: '2026-03-02' }, acr: 'mfa', devAuth: true }));
      });
      server.middlewares.use('/bff/logout', (_req, res) => { res.statusCode = 204; res.end(); });
    },
  };
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, import.meta.dirname, 'NL_');
  const dev = !!env.NL_DEV_USER;
  const bff = env.NL_BFF ?? 'http://localhost:8082';
  const proxy: Record<string, string | ProxyOptions> = dev
    ? { '/api': { target: env.NL_API ?? 'http://localhost:8080', headers: { 'X-Dev-User': env.NL_DEV_USER! } } }
    : { '/api': bff, '/bff': bff, '/oauth2': bff, '/login': bff };
  return {
    // /legal/terms.html, /legal/privacy.html: design 09/10 verbatim, shared with the consumer app (S-63)
    plugins: [tanstackRouter({ target: 'react', autoCodeSplitting: true }), react(), devAuth(env), legalPages()],
    server: {
      port: 3100,
      proxy,
    },
    test: {
      environment: 'jsdom', globals: true, setupFiles: ['./src/test/setup.ts'], css: false,
      // A whole screen flow (render → type → submit → server answer) takes ~1 s alone and several times that when the
      // machine is busy (a Gradle build, parallel workers). Vitest's 5 s default made those runs fail intermittently;
      // a real hang still fails, just later. Testing Library's own wait is set in src/test/setup.ts.
      testTimeout: 20_000,
      hookTimeout: 20_000,
    },
  };
});
