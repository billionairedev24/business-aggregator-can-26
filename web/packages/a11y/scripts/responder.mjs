// The mock api of the page sweep (S-109): answers a request from fixtures/<app>.json (recorded from the apps' own tests)
// after the app's overrides (overrides.mjs: the session, the merchant per portal, …). Used by the Playwright routes
// (pages/support.ts) for what the browser asks, and by mock-api.mjs for the consumer's server-side rendering.
import { readFileSync } from 'node:fs';
import { pathOf, pattern } from './reduce.mjs';
import { OVERRIDES } from './overrides.mjs';

const fixturesDir = new URL('../fixtures/', import.meta.url);

/** `(method, url) → { status, body }` for `app`; unknown requests answer 404 and are reported by the sweep. */
export function responder(app) {
  const fixtures = JSON.parse(readFileSync(new URL(`${app}.json`, fixturesDir), 'utf8'));
  const overrides = OVERRIDES[app] ?? [];
  return (method, url) => {
    const path = pathOf(url);
    for (const o of overrides) {
      const hit = o(method, path);
      if (hit) return { status: 200, ...hit };
    }
    const hit = fixtures.exact[`${method} ${path}`] ?? fixtures.exact[`${method} ${path.split('?')[0]}`] ?? fixtures.pattern[`${method} ${pattern(path)}`];
    return hit ? { status: hit.status, body: hit.body } : { status: 404, body: { detail: 'No fixture for this request (S-109 page sweep)', path }, missing: true };
  };
}
