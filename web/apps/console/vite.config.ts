import { defineConfig, loadEnv, type Plugin, type ProxyOptions } from 'vite';
import react from '@vitejs/plugin-react';
import { tanstackRouter } from '@tanstack/router-plugin/vite';

/**
 * Local "dev auth" (as the Studio's): with NL_DEV_USER set (a seeded staff member's identity.users id) /api goes
 * straight to the api with X-Dev-User — the api's `local` profile mints a token with that person's platform roles and
 * acr=mfa — and /bff/session is answered here. Without it everything goes through the console-bff like production.
 */
function devAuth(env: Record<string, string>): Plugin {
  return {
    name: 'northline-dev-auth',
    configureServer(server) {
      if (!env.NL_DEV_USER) return;
      server.middlewares.use('/bff/session', (_req, res) => {
        const [firstName = 'Priya', lastName = 'Natarajan'] = (env.NL_DEV_USER_NAME ?? 'Priya Natarajan').split(' ');
        res.setHeader('content-type', 'application/json');
        res.end(JSON.stringify({ user: { id: env.NL_DEV_USER, firstName, lastName, initials: (firstName[0]! + lastName[0]!).toUpperCase(), locale: 'en-CA' }, acr: 'mfa', devAuth: true }));
      });
      server.middlewares.use('/bff/logout', (_req, res) => { res.statusCode = 204; res.end(); });
    },
  };
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, import.meta.dirname, 'NL_');
  const dev = !!env.NL_DEV_USER;
  const bff = env.NL_BFF ?? 'http://localhost:8083';
  // `^/api/` and not `/api`: a client route may start with "api" (docs/CONSOLE_PLAN.md § Routes).
  const proxy: Record<string, string | ProxyOptions> = dev
    ? { '^/api/': { target: env.NL_API ?? 'http://localhost:8080', headers: { 'X-Dev-User': env.NL_DEV_USER! } } }
    : { '^/api/': bff, '^/bff/': bff, '^/oauth2/': bff, '^/login/': bff };
  return {
    plugins: [tanstackRouter({
      target: 'react', autoCodeSplitting: true,
      codeSplittingOptions: { defaultBehavior: [['loader', 'component'], ['pendingComponent'], ['errorComponent'], ['notFoundComponent']] },
    }), react(), devAuth(env)],
    server: { port: 3200, proxy },
    test: {
      environment: 'jsdom', globals: true, setupFiles: ['./src/test/setup.ts'], css: false,
      testTimeout: 20_000, hookTimeout: 20_000,
    },
  };
});
