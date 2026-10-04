import { config } from 'zod';

/**
 * Imported first by router.tsx, before any schema is built. Zod 4 compiles object parsers with `new Function` when it
 * may, and probes for it once — under a Content-Security-Policy without 'unsafe-eval' that probe is refused and reported
 * as a violation on every page (S104-09: the consumer's CSP reports to /csp-report). Jitless parsing never tries.
 */
config({ jitless: true });
