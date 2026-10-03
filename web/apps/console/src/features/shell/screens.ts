/**
 * The console's screens (design 03 `view` values; docs/CONSOLE_PLAN.md § Routes): their paths, the story that builds
 * each, and the sidebar's groups. No icons here: route guards import this module (icons load with the layout,
 * navMenu.ts). Which role opens which screen comes from the api (`GET /api/v1/console/me`), never from here.
 */
export const SCREENS = ['overview', 'orders', 'disputes', 'delivery', 'sellers', 'verify', 'pilot', 'vetting', 'trust', 'taxonomy', 'support', 'regions', 'finance', 'reports', 'privacy', 'api', 'team', 'profile', 'oncall'] as const;
export type ScreenKey = (typeof SCREENS)[number];

/** Client paths. `api` is `/integrations`: `/api/…` belongs to the console-bff on this host. */
export const SCREEN_PATH: Record<ScreenKey, string> = {
  overview: '/', orders: '/orders', disputes: '/disputes', delivery: '/delivery', sellers: '/sellers', verify: '/verification', pilot: '/pilot', vetting: '/vetting',
  trust: '/trust', taxonomy: '/catalogue', support: '/support', regions: '/provinces', finance: '/finance', reports: '/reports', api: '/integrations',
  privacy: '/privacy', team: '/team', profile: '/profile', oncall: '/on-call',
};

/** The backlog story that replaces each screen's stand-in (E-8). */
export const SCREEN_STORY: Record<ScreenKey, string> = {
  overview: 'S-91', orders: 'S-81', disputes: 'S-80', delivery: 'S-81', sellers: 'S-82', verify: 'S-79', pilot: 'S-120', vetting: 'S-92', trust: 'S-93', taxonomy: 'S-94',
  support: 'S-83', regions: 'S-84', finance: 'S-85', reports: 'S-95', privacy: 'S-105', api: 'S-96', team: 'S-96', profile: 'S-96', oncall: 'S-96',
};

/** Every staff member opens these, whatever their roles (design `canSee`). */
export const OPEN_TO_ALL_STAFF: readonly ScreenKey[] = ['profile', 'oncall'];

export type NavGroupKey = 'operations' | 'marketplace' | 'platform';
/** Sidebar: Overview pinned, then the design's groups in order (design `allNav`). */
export const PINNED: readonly ScreenKey[] = ['overview'];
export const NAV_GROUPS: readonly { key: NavGroupKey; screens: readonly ScreenKey[] }[] = [
  { key: 'operations', screens: ['orders', 'disputes', 'delivery'] },
  { key: 'marketplace', screens: ['sellers', 'verify', 'pilot', 'vetting', 'trust', 'taxonomy', 'support'] },
  { key: 'platform', screens: ['regions', 'finance', 'reports', 'privacy', 'api', 'team'] },
];

/** The screen a pathname shows (`/sellers/123` → sellers), or undefined for a path outside the console's screens. */
export function screenFromPath(pathname: string): ScreenKey | undefined {
  const path = pathname.replace(/\/+$/, '') || '/';
  if (path === '/') return 'overview';
  return (Object.entries(SCREEN_PATH) as [ScreenKey, string][])
    .filter(([, p]) => p !== '/' && (path === p || path.startsWith(`${p}/`)))
    .sort((a, b) => b[1].length - a[1].length)[0]?.[0];
}
