import { ArrowUUpLeft, Bank, Scales, BookOpenText, Briefcase, CalendarCheck, ChartBar, ChartLineUp, ChatCircleText, Clock, CookingPot, GearSix, Lifebuoy, Package, ShieldCheck, SquaresFour, Stack, Star, Storefront, Tag, Timer, UserCircle, Wallet, type Icon } from '@phosphor-icons/react';
import type { NavGroup, NavItem } from '@northline/ui';
import type { MerchantType } from './api';
import type { useShellT } from './messages';
import { screenHref, screensFor, type ScreenKey } from './nav';

/** The Studio menu and its icons. Only the layout imports it (S-69: icons stay out of the initial bundle). */
const ICON: Record<ScreenKey, Icon> = {
  dashboard: SquaresFour, appointments: CalendarCheck, orders: Package, messages: ChatCircleText, products: Tag, availability: Clock, storefront: Storefront, earnings: ChartLineUp, reports: ChartBar,
  payouts: Bank, refunds: ArrowUUpLeft, compliance: ShieldCheck, standing: Scales, reviews: Star, settings: GearSix, help: Lifebuoy, kds: CookingPot, menu: BookOpenText, combos: Stack, kitchenHours: Timer,
};

/**
 * The grouped Studio menu (E-15, design/revamp/a.html screen 7): Dashboard; Operations (the day's work); Catalogue (what
 * is sold); Money; Store (the public page, reviews, settings); Account (standing, compliance, help). Every portal gets the
 * same groups with its own screens: providers bookings and services, sellers orders and products, kitchens the kitchen
 * display and the menu. The routes and the portal rules (`screensFor`) are unchanged.
 */
export function buildNav(type: MerchantType, merchantId: string, t: ReturnType<typeof useShellT>, badges: Record<string, string>): { pinned: NavItem[]; groups: NavGroup[] } {
  const label = (k: ScreenKey): string => {
    if (k === 'products') return t(type === 'seller' ? 'products_seller' : type === 'provider' ? 'products_provider' : 'products_both');
    if (k === 'storefront') return t(type === 'seller' ? 'storefront_seller' : type === 'kitchen' ? 'storefront_kitchen' : 'storefront_provider');
    return t(k);
  };
  const avail = new Set(screensFor(type));
  const item = (k: ScreenKey): NavItem => ({ key: k, label: label(k), icon: ICON[k], badge: badges[k] || undefined, href: screenHref(merchantId, k) });
  const pick = (keys: ScreenKey[]) => keys.filter(k => avail.has(k)).map(item);
  const kitchen = type === 'kitchen';
  return { pinned: pick(['dashboard']), groups: [
    { label: t('g_operations'), icon: Briefcase, items: pick(kitchen ? ['kds', 'messages'] : ['appointments', 'orders', 'messages']) },
    { label: t('g_catalogue'), icon: kitchen ? CookingPot : Tag, items: pick(kitchen ? ['menu', 'combos', 'kitchenHours'] : ['products', 'availability']) },
    { label: t('g_money'), icon: Wallet, items: pick(['earnings', 'payouts', 'reports', 'refunds']) },
    { label: t('g_store'), icon: Storefront, items: pick(['storefront', 'reviews', 'settings']) },
    { label: t('g_account'), icon: UserCircle, items: pick(['standing', 'compliance', 'help']) },
  ] };
}
