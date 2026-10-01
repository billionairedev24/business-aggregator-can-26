import type { MerchantType } from './api';

/**
 * Screens, paths and portal rules of the Studio. No icons here (S-69): route guards import this module, so it is in the
 * initial bundle; the menu with its icons is `navMenu.ts`, loaded with the Studio layout.
 */
export type ScreenKey = 'dashboard' | 'appointments' | 'orders' | 'messages' | 'products' | 'availability' | 'storefront' | 'earnings' | 'reports' | 'payouts' | 'refunds' | 'compliance' | 'reviews' | 'settings' | 'help' | 'kds' | 'menu' | 'combos' | 'kitchenHours';

/** Path of each screen under /b/$merchantId. */
export const SCREEN_PATH: Record<ScreenKey, string> = {
  dashboard: '', appointments: 'appointments', orders: 'orders', messages: 'messages', products: 'listings', availability: 'availability', storefront: 'page',
  earnings: 'earnings', reports: 'reports', payouts: 'payouts', refunds: 'refunds', compliance: 'compliance', reviews: 'reviews', settings: 'settings', help: 'help',
  kds: 'kitchen/live', menu: 'kitchen/menu', combos: 'kitchen/combos', kitchenHours: 'kitchen/hours',
};
/** First screen of a portal: kitchens open on Live orders, everyone else on the Dashboard. */
export const homeScreen = (type: MerchantType): ScreenKey => (type === 'kitchen' ? 'kds' : 'dashboard');

/** Which screens a portal has (design: navAll / navKitchen). Used for nav and for route guards. */
export function screensFor(type: MerchantType): ScreenKey[] {
  if (type === 'kitchen') return ['kds', 'menu', 'combos', 'kitchenHours', 'messages', 'earnings', 'reports', 'payouts', 'refunds', 'compliance', 'reviews', 'storefront', 'settings', 'help'];
  const prov = type !== 'seller', sell = type !== 'provider';
  return ['dashboard', ...(prov ? ['appointments' as const] : []), ...(sell ? ['orders' as const] : []), 'messages', 'products', ...(prov ? ['availability' as const] : []), 'storefront', 'earnings', 'reports', 'payouts', 'refunds', 'compliance', 'reviews', 'settings', 'help'];
}

export const screenHref = (merchantId: string, key: ScreenKey) => `/b/${merchantId}${SCREEN_PATH[key] ? `/${SCREEN_PATH[key]}` : ''}`;

/** Maps a pathname under /b/$id back to its nav key (editor/bulk pages highlight their parent). */
export function screenFromPath(pathname: string): ScreenKey {
  const rest = pathname.replace(/^\/b\/[^/]+\/?/, '');
  const hit = (Object.entries(SCREEN_PATH) as [ScreenKey, string][]).filter(([, p]) => p && (rest === p || rest.startsWith(`${p}/`))).sort((a, b) => b[1].length - a[1].length)[0];
  return hit?.[0] ?? 'dashboard';
}
