import { config } from 'zod';

/**
 * Imported first by main.tsx, before any schema is built. Zod 4 compiles object parsers with `new Function` when it
 * may, and probes for it once — under a Content-Security-Policy without 'unsafe-eval' that probe is refused and reported
 * as a violation on every page (the CSP of web/docker/security-headers.inc.template, S104-09). Jitless parsing never tries.
 */
config({ jitless: true });
