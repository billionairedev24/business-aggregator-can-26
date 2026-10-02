import { defineConfig, loadEnv, type Plugin, type ProxyOptions } from 'vite';
import { tanstackStart } from '@tanstack/react-start/plugin/vite';
import react from '@vitejs/plugin-react';
import { legalPages } from '@northline/legal/vite';
import { createSeo, isSeoPath } from './server/seo.mjs';
import { appLinkAnswer, appLinksConfig, isAppLinkPath } from './server/app-links.mjs';

/**
 * Local "dev auth", like the Studio's: with NL_DEV_USER (a seeded identity.users id) /api goes straight to the api with
 * X-Dev-User (accepted only by the api's `local` profile, as a single-factor consumer: X-Dev-Acr none) and
 * /bff/session answers with that user — screens can be built without auth + bff. Without it, /api, /bff, /oauth2 and
 * /login go to the consumer-bff (:8081, `--spring.profiles.active=local,consumer`) exactly as in the cloud.
 * NL_DEV_GUEST=1 makes /bff/session answer as a guest instead.
 */
function devAuth(env: Record<string, string>): Plugin {
  return {
    name: 'northline-dev-auth',
    configureServer(server) {
      if (!env.NL_DEV_USER && !env.NL_DEV_GUEST) return;
      server.middlewares.use('/bff/session', (_req, res) => {
        const [firstName = 'Amara', lastName = 'Osei'] = (env.NL_DEV_USER_NAME ?? 'Amara Osei').split(' ');
        const user = env.NL_DEV_USER
          ? { id: env.NL_DEV_USER, firstName, lastName, email: env.NL_DEV_USER_EMAIL ?? 'amara@example.ca', initials: (firstName[0]! + lastName[0]!).toUpperCase(), locale: 'en-CA', memberSince: '2026-03-02' }
          : null;
        res.setHeader('content-type', 'application/json');
        res.end(JSON.stringify({ user, guestId: 'g_devguest0000000000000000', devAuth: true }));
      });
      server.middlewares.use('/bff/logout', (_req, res) => { res.statusCode = 204; res.end(); });
    },
  };
}

/** robots.txt and the sitemaps in development, as node-server.mjs serves them (S-63; the dev server is the site). */
function seoFiles(env: Record<string, string>): Plugin {
  return {
    name: 'northline-seo',
    configureServer(server) {
      const handle = createSeo({ siteOrigin: env.NL_SITE_ORIGIN ?? 'http://localhost:3000', bffUrl: env.NL_BFF_URL ?? 'http://localhost:8081' });
      server.middlewares.use((req, res, next) => {
        const path = (req.url ?? '/').split('?')[0]!;
        if (!isSeoPath(path)) return next();
        void handle({ kind: 'site' }, path).then(a => { res.statusCode = a.status; res.setHeader('content-type', a.type); res.end(a.body); });
      });
    },
  };
}

/** The native apps' association files and OAuth redirect page in development, as node-server.mjs serves them (S-97). */
function appLinkFiles(env: Record<string, string>): Plugin {
  return {
    name: 'northline-app-links',
    configureServer(server) {
      const config = appLinksConfig({ ...process.env, ...env });
      server.middlewares.use((req, res, next) => {
        const path = (req.url ?? '/').split('?')[0]!;
        if (!isAppLinkPath(path)) return next();
        const a = appLinkAnswer(path, config)!;
        res.statusCode = a.status;
        res.setHeader('content-type', a.type);
        res.setHeader('cache-control', a.cache);
        res.end(a.body);
      });
    },
  };
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, import.meta.dirname, 'NL_');
  // Server-side code reads these from process.env (as in the container); .env feeds them to the dev server too.
  for (const key of ['NL_BFF_URL', 'NL_AUTH_ORIGIN']) if (env[key] && !process.env[key]) process.env[key] = env[key];
  const bff = env.NL_BFF ?? 'http://localhost:8081';
  const api = env.NL_API ?? 'http://localhost:8080';
  const proxy: Record<string, string | ProxyOptions> = env.NL_DEV_USER || env.NL_DEV_GUEST
    ? { '/api': { target: api, headers: env.NL_DEV_USER ? { 'X-Dev-User': env.NL_DEV_USER, 'X-Dev-Acr': 'none' } : { 'X-Northline-Guest': 'g_devguest_0000000000000' } } }
    : { '/api': bff, '/bff': bff, '/oauth2': bff, '/login': bff };
  return {
    // /legal/*.html: the Studio's verbatim design 09/10 pages, one copy for both apps (S-63)
    plugins: [tanstackStart(), react(), devAuth(env), legalPages(), seoFiles(env), appLinkFiles(env)],
    // Server-side rendering fetches public data through the consumer-bff (NL_BFF_URL, default http://localhost:8081;
    // the chart sets the in-cluster Service). In dev-auth mode there is no bff: NL_BFF_URL=http://localhost:8080.
    server: { port: 3000, strictPort: true, proxy },
  };
});
