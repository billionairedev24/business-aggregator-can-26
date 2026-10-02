import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup, configure } from '@testing-library/react';
import { configurePlatformTimeZone } from '@northline/ui';
import { recordFetches } from '@northline/a11y/record';
// Test data: a Mountain-time platform zone, as the launch configuration has (the apps get it from the region model).
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
// The router restores scroll on navigation; jsdom has no layout.
window.scrollTo = (() => {}) as typeof window.scrollTo;
// S-109: `pnpm --filter @northline/a11y record` replays this suite with NL_A11Y_RECORD set to keep the api answers for
// the Playwright page sweep; a no-op otherwise.
recordFetches(process.env.NL_A11Y_RECORD);
