import { describe, expect, it } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import { createRouter } from '@tanstack/react-router';
import { routeTree } from '../../routeTree.gen';
import { NAV_GROUPS, PINNED, SCREEN_PATH, SCREENS, screenFromPath } from './screens';

describe('console screens', () => {
  it('maps paths back to their screen, detail pages to their list', () => {
    expect(screenFromPath('/')).toBe('overview');
    expect(screenFromPath('/sellers/01J9')).toBe('sellers');
    expect(screenFromPath('/verification')).toBe('verify');
    expect(screenFromPath('/integrations')).toBe('api');
    expect(screenFromPath('/on-call/')).toBe('oncall');
    expect(screenFromPath('/sign-in')).toBeUndefined();
  });

  it('has a route for every screen of design 03, none under /api', () => {
    const router = createRouter({ routeTree, context: { queryClient: new QueryClient() } });
    const paths = new Set(Object.keys(router.routesByPath).map(p => p.replace(/\/$/, '') || '/'));
    for (const screen of SCREENS) {
      expect(paths, screen).toContain(SCREEN_PATH[screen]);
      expect(SCREEN_PATH[screen].startsWith('/api')).toBe(false);
    }
  });

  it('puts every screen but profile and on-call in the sidebar once', () => {
    const inNav = [...PINNED, ...NAV_GROUPS.flatMap(g => g.screens)];
    expect(new Set(inNav).size).toBe(inNav.length);
    expect([...inNav, 'profile', 'oncall'].sort()).toEqual([...SCREENS].sort());
  });
});
