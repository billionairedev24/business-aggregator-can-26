import { ArrowUUpLeft, Bank, BookOpenText, Briefcase, CalendarCheck, ChartBar, ChartLineUp, ChatCircleText, Clock, CookingPot, GearSix, Lifebuoy, Package, ShieldCheck, SquaresFour, Stack, Star, Storefront, Tag, Timer, UserCircle, Wallet, type Icon } from '@phosphor-icons/react';
import type { NavGroup, NavItem } from '@northline/ui';
import type { MerchantType } from './api';
import type { useShellT } from './messages';

export type ScreenKey = 'dashboard' | 'appointments' | 'orders' | 'messages' | 'products' | 'availability' | 'storefront' | 'earnings' | 'reports' | 'payouts' | 'refunds' | 'compliance' | 'reviews' | 'settings' | 'help' | 'kds' | 'menu' | 'combos' | 'kitchenHours';

/** Path of each screen under /b/$merchantId. */
export const SCREEN_PATH: Record<ScreenKey, string> = {
  dashboard: '', appointments: 'appointments', orders: 'orders', messages: 'messages', products: 'listings', availability: 'availability', storefront: 'page',
  earnings: 'earnings', reports: 'reports', payouts: 'payouts', refunds: 'refunds', compliance: 'compliance', reviews: 'reviews', settings: 'settings', help: 'help',
  kds: 'kitchen/live', menu: 'kitchen/menu', combos: 'kitchen/combos', kitchenHours: 'kitchen/hours',
};
const ICON: Record<ScreenKey, Icon> = {
  dashboard: SquaresFour, appointments: CalendarCheck, orders: Package, messages: ChatCircleText, products: Tag, availability: Clock, storefront: Storefront, earnings: ChartLineUp, reports: ChartBar,
  payouts: Bank, refunds: ArrowUUpLeft, compliance: ShieldCheck, reviews: Star, settings: GearSix, help: Lifebuoy, kds: CookingPot, menu: BookOpenText, combos: Stack, kitchenHours: Timer,
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

export function buildNav(type: MerchantType, merchantId: string, t: ReturnType<typeof useShellT>, badges: Record<string, string>): { pinned: NavItem[]; groups: NavGroup[] } {
  const label = (k: ScreenKey): string => {
    if (k === 'products') return t(type === 'seller' ? 'products_seller' : type === 'provider' ? 'products_provider' : 'products_both');
    if (k === 'storefront') return t(type === 'seller' ? 'storefront_seller' : type === 'kitchen' ? 'storefront_kitchen' : 'storefront_provider');
    return t(k);
  };
  const avail = new Set(screensFor(type));
  const item = (k: ScreenKey): NavItem => ({ key: k, label: label(k), icon: ICON[k], badge: badges[k] || undefined, href: screenHref(merchantId, k) });
  const pick = (keys: ScreenKey[]) => keys.filter(k => avail.has(k)).map(item);
  if (type === 'kitchen') {
    return { pinned: [], groups: [
      { label: t('g_kitchen'), icon: CookingPot, items: pick(['kds', 'menu', 'combos', 'kitchenHours', 'messages']) },
      { label: t('g_finance'), icon: Wallet, items: pick(['earnings', 'reports', 'payouts', 'refunds', 'compliance']) },
      { label: t('g_account'), icon: UserCircle, items: pick(['reviews', 'storefront', 'settings']) },
      { label: t('g_help'), icon: Lifebuoy, items: pick(['help']) },
    ] };
  }
  return { pinned: [item('dashboard')], groups: [
    { label: t('g_operations'), icon: Briefcase, items: pick(['appointments', 'orders', 'messages']) },
    { label: t('g_catalogue'), icon: Storefront, items: pick(['products', 'availability', 'storefront']) },
    { label: t('g_finance'), icon: Wallet, items: pick(['earnings', 'reports', 'payouts', 'refunds', 'compliance']) },
    { label: t('g_account'), icon: UserCircle, items: pick(['reviews', 'settings']) },
    { label: t('g_help'), icon: Lifebuoy, items: pick(['help']) },
  ] };
}
