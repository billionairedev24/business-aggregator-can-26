import { ArrowUUpLeft, Bank, BookOpenText, Briefcase, CalendarCheck, ChartBar, ChartLineUp, ChatCircleText, Clock, CookingPot, GearSix, Lifebuoy, Package, ShieldCheck, SquaresFour, Stack, Star, Storefront, Tag, Timer, UserCircle, Wallet, type Icon } from '@phosphor-icons/react';
import type { NavGroup, NavItem } from '@northline/ui';
import type { MerchantType } from './api';
import type { useShellT } from './messages';
import { screenHref, screensFor, type ScreenKey } from './nav';

/** The Studio menu and its icons (design navAll / navKitchen). Only the layout imports it (S-69: icons stay out of the initial bundle). */
const ICON: Record<ScreenKey, Icon> = {
  dashboard: SquaresFour, appointments: CalendarCheck, orders: Package, messages: ChatCircleText, products: Tag, availability: Clock, storefront: Storefront, earnings: ChartLineUp, reports: ChartBar,
  payouts: Bank, refunds: ArrowUUpLeft, compliance: ShieldCheck, reviews: Star, settings: GearSix, help: Lifebuoy, kds: CookingPot, menu: BookOpenText, combos: Stack, kitchenHours: Timer,
};


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
