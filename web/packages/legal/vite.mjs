// The legal pages (CLAUDE.md: design 09 Terms of Service and 10 Privacy Policy ship verbatim) as one set of files for
// every web app: scripts/legal-pages.mjs generates pages/ from the design, and this Vite plugin serves them at /legal/
// in development and writes them into the browser build (the Studio's nginx and the consumer's Node server then serve
// them as static files). S-63: one copy instead of one per app.
import { readdirSync, readFileSync } from 'node:fs';
import { extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const LEGAL_DIR = fileURLToPath(new URL('./pages/', import.meta.url));
const TYPES = { '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8' };

/** The files under pages/ (terms.html, privacy.html, northline.css). */
export const legalFiles = () => readdirSync(LEGAL_DIR).filter(name => extname(name) in TYPES);

/** @returns {import('vite').Plugin} */
export function legalPages() {
  return {
    name: 'northline-legal-pages',
    configureServer(server) {
      server.middlewares.use('/legal/', (req, res, next) => {
        const name = decodeURIComponent((req.url ?? '/').split('?')[0].replace(/^\//, ''));
        if (!legalFiles().includes(name)) return next();
        res.setHeader('content-type', TYPES[extname(name)]);
        res.end(readFileSync(join(LEGAL_DIR, name)));
      });
    },
    generateBundle() {
      // TanStack Start builds a browser and a server environment; the files belong to the browser's.
      if (this.environment && this.environment.name !== 'client') return;
      for (const name of legalFiles()) {
        this.emitFile({ type: 'asset', fileName: `legal/${name}`, source: readFileSync(join(LEGAL_DIR, name)) });
      }
    },
  };
}
