import { afterEach } from 'vitest';
import { cleanup, configure } from '@testing-library/react';
import { configurePlatformTimeZone } from '@northline/ui';
import { recordFetches } from '@northline/a11y/record';
// Test data: a fixed platform zone (the app gets the real one from the region model, S-134).
configurePlatformTimeZone('America/Edmonton');
afterEach(() => cleanup());
// findBy* / waitFor give up after 1 s by default — too short for a fetch → re-render on a loaded machine. They still
// resolve as soon as the element appears, so this only changes how long a genuinely missing element takes to fail
// (vite.config.ts `testTimeout` stays well above it). Typing uses userEvent.setup({ delay: null }) in the tests.
configure({ asyncUtilTimeout: 5_000 });
if (!('ResizeObserver' in globalThis)) {
  (globalThis as Record<string, unknown>).ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
}
if (!window.matchMedia) {
  window.matchMedia = (q: string) => ({ matches: false, media: q, onchange: null, addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {}, dispatchEvent: () => false }) as MediaQueryList;
}
window.scrollTo = () => {}; // the router restores scroll positions; jsdom has no layout
// S-109: `pnpm --filter @northline/a11y record` replays this suite with NL_A11Y_RECORD set to keep the api answers for
// the Playwright page sweep; a no-op otherwise.
recordFetches(process.env.NL_A11Y_RECORD);
