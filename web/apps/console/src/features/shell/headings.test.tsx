import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { renderConsole, staffApi } from '../../test/render';
import { SCREEN_PATH, type ScreenKey } from './screens';

/**
 * S-142 (WCAG 1.3.1 / 2.4.6): a screen keeps its h1 while it loads and when its data fails to load — the error state
 * sits under the screen's heading instead of replacing it.
 */
const SCREENS = Object.entries(SCREEN_PATH) as [ScreenKey, string][];
const ownData = (url: string) => url.includes('/api/v1/') && !url.endsWith('/api/v1/console/me') && !url.includes('/role-view') && !url.includes('/geo/regions');

describe('screens keep their h1 (S-142)', () => {
  it.each(SCREENS)('%s: when its data fails to load', async (_key, path) => {
    staffApi(['admin'], call => (ownData(call.url) ? { status: 500, body: { detail: 'boom' } } : undefined));
    renderConsole(path);
    await waitFor(() => expect(screen.getAllByRole('alert').length + screen.queryAllByRole('button', { name: /retry|try again/i }).length).toBeGreaterThan(0), { timeout: 4000 });
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });

  it.each(SCREENS)('%s: while it loads', async (_key, path) => {
    staffApi(['admin']);
    const answer = globalThis.fetch;
    vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => (ownData(String(input)) ? new Promise(() => {}) : answer(input, init)));
    renderConsole(path);
    await waitFor(() => expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1), { timeout: 4000 });
  });
});
