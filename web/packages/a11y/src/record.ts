import { appendFileSync } from 'node:fs';
import { expect, vi } from 'vitest';

/**
 * Records what the apps' Testing Library tests answer to `fetch` (S-109), so the Playwright page sweep can serve the same
 * api answers to the built apps without a backend: `pnpm --filter @northline/a11y record` runs each app's vitest suite
 * with NL_A11Y_RECORD=<file> and `scripts/fixtures.mjs` turns the lines into `fixtures/<app>.json`.
 *
 * Every test stubs fetch with `vi.stubGlobal('fetch', …)`; in recording mode the stub is wrapped and each answer is
 * appended as one JSON line: method, url, status, body, the test that gave it. Nothing changes when the variable is unset.
 */
export function recordFetches(file: string | undefined): void {
  if (!file) return;
  const stub = vi.stubGlobal.bind(vi);
  vi.stubGlobal = ((name: string | symbol | number, value: unknown) => {
    if (name !== 'fetch' || typeof value !== 'function') return stub(name, value);
    const original = value as typeof fetch;
    const wrapped = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const response = await original(input, init);
      try {
        const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
        const body = await response.clone().text();
        const { currentTestName, testPath } = expect.getState();
        appendFileSync(file, JSON.stringify({ method: (init?.method ?? 'GET').toUpperCase(), url, status: response.status, body, test: `${testPath?.split('/src/')[1] ?? ''} › ${currentTestName ?? ''}` }) + '\n');
      } catch { /* a stream body or a test that ended: skip it */ }
      return response;
    });
    return stub(name, wrapped);
  }) as typeof vi.stubGlobal;
}
