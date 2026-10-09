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

/**
 * S-69: `<link rel="modulepreload">` for the Studio layout and the dashboard (the screen most sessions open on) and
 * the chunks they import, so the browser fetches them while the entry script runs instead of after it. Costs the
 * signed-out pages ~40 kB gzip they may not need; buys the dashboard one network round trip.
 */
function preloadLanding(): Plugin {
  const landing = [/routes\/b\.\$merchantId\.tsx\?tsr-split=component/, /routes\/b\.\$merchantId\/index\.tsx\?tsr-split=component/];
  return {
    name: 'northline-preload-landing',
    apply: 'build',
    transformIndexHtml: {
      order: 'post',
      handler(_html, ctx) {
        const chunks = Object.values(ctx.bundle ?? {}).filter(c => c.type === 'chunk');
        const byFile = new Map(chunks.map(c => [c.fileName, c]));
        const files = new Set<string>();
        const add = (file: string) => {
          const chunk = byFile.get(file);
          if (!chunk || chunk.isEntry || files.has(file)) return;
          files.add(file);
          chunk.imports.forEach(add);
        };
        chunks.filter(c => landing.some(re => re.test(c.facadeModuleId ?? ''))).forEach(c => add(c.fileName));
        return [...files].map(file => ({ tag: 'link', attrs: { rel: 'modulepreload', crossorigin: true, href: `/${file}` }, injectTo: 'head' as const }));
      },
    },
  };
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, import.meta.dirname, 'NL_');
  const dev = !!env.NL_DEV_USER;
  const bff = env.NL_BFF ?? 'http://localhost:8082';
  // S-117: keep the browser's Host (Vite's string shorthand rewrites it to the target), so the BFF builds its OAuth
  // redirect URI and its post-login redirect on this origin instead of sending the browser to the BFF's own port.
  const toBff: ProxyOptions = { target: bff, changeOrigin: false };
  const proxy: Record<string, string | ProxyOptions> = dev
    ? { '/api': { target: env.NL_API ?? 'http://localhost:8080', headers: { 'X-Dev-User': env.NL_DEV_USER! } } }
    : { '/api': toBff, '/bff': toBff, '/oauth2': toBff, '/login': toBff };
  return {
    // /legal/terms.html, /legal/privacy.html: design 09/10 verbatim, shared with the consumer app (S-63)
    // S-69: each route's loader travels with its component (one lazy chunk), so the features' api modules and their
    // zod schemas stay out of the initial bundle; the error/pending/not-found components get their own chunks.
    plugins: [tanstackRouter({
      // as the console's (engineering follow-ups): lazy route chunks were transformed inside the tests' waits
      target: 'react', autoCodeSplitting: !process.env.VITEST,
      codeSplittingOptions: { defaultBehavior: [['loader', 'component'], ['pendingComponent'], ['errorComponent'], ['notFoundComponent']] },
    }), react(), devAuth(env), legalPages(), preloadLanding()],
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
