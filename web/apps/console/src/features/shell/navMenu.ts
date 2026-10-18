import { RocketLaunch, Briefcase, ChartBar, ClipboardText, CurrencyCircleDollar, Gauge, GearSix, Headset, IdentificationCard, ListChecks, LockKey, Storefront, MapTrifold, Package, PlugsConnected, Scales, ShieldWarning, TreeStructure, Truck, UserList, UsersThree, type Icon } from '@phosphor-icons/react';
import { testingToolsEnabled, type NavGroup, type NavItem } from '@northline/ui';
import { opens, type RoleGrant } from './api';
import type { ShellT } from './messages';
import { NAV_GROUPS, PINNED, SCREEN_PATH, type NavGroupKey, type ScreenKey } from './screens';

/** The console sidebar (design 03 `allNav`, `NAV_ICON`, group icons), filtered by the active role's grant. */
const ICON: Partial<Record<ScreenKey, Icon>> = {
  overview: Gauge, orders: Package, disputes: Scales, delivery: Truck, sellers: UsersThree, verify: IdentificationCard, pilot: Storefront, vetting: ListChecks,
  trust: ShieldWarning, taxonomy: TreeStructure, support: Headset, regions: MapTrifold, finance: CurrencyCircleDollar, reports: ChartBar,
  privacy: LockKey, uat: ClipboardText, go_live: RocketLaunch, api: PlugsConnected, team: UserList,
};
const GROUP_ICON: Record<NavGroupKey, Icon> = { operations: Briefcase, marketplace: UsersThree, platform: GearSix };

/**
 * The pilot and testing screens (redesign § 3.5 and § 3.12: Pilot, UAT, Go-live; the API screen): out of the sidebar
 * unless the testing tools are on (`VITE_NL_TESTING_TOOLS`). Their routes and the api stay; the role still decides.
 */
export const TESTING_SCREENS: readonly ScreenKey[] = ['pilot', 'uat', 'go_live', 'api'];

export function buildNav(grant: RoleGrant | undefined, t: ShellT, testingTools = testingToolsEnabled()): { pinned: NavItem[]; groups: NavGroup[] } {
  const item = (k: ScreenKey): NavItem => ({ key: k, label: t(k as Parameters<ShellT>[0]), icon: ICON[k] ?? Gauge, href: SCREEN_PATH[k] });
  const visible = (keys: readonly ScreenKey[]) => keys.filter(k => opens(grant, k) && (testingTools || !TESTING_SCREENS.includes(k))).map(item);
  return {
    pinned: visible(PINNED),
    // groups without a visible item are dropped by the shell (design: a head shows only above an item it may see)
    groups: NAV_GROUPS.map(g => ({ label: t(`g_${g.key}`), icon: GROUP_ICON[g.key], items: visible(g.screens) })),
  };
}
