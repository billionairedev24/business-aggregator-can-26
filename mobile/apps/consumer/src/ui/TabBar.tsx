import type { BottomTabBarProps } from 'expo-router/js-tabs';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { BottomTabBar, type IconName } from '@northline/mobile-kit';

import { useI18n, type MessageKey } from '../i18n';

/** Each tab's Phosphor icon (regular; fill when it's the one you're on). */
const ICONS: Record<string, IconName> = {
  home: 'house',
  services: 'squares-four',
  orders: 'receipt',
  cart: 'shopping-cart',
  account: 'user-circle',
};

/**
 * The tab bar: the kit's BottomTabBar (S-169) — Home · Browse · Activity · Cart · Account, a Phosphor icon over a
 * short label; S-188: white, the current tab as the web's current page (the violet bar and filled icon, the ink
 * label), the cart's count in the round yellow badge with ink; the home indicator's room below.
 */
export function TabBar({ state, navigation, cartCount = 0 }: Pick<BottomTabBarProps, 'state' | 'navigation'> & { cartCount?: number }) {
  const { t } = useI18n();
  const insets = useSafeAreaInsets();
  const current = state.routes[state.index]?.key ?? '';
  return (
    <BottomTabBar
      label={t('tab.label')}
      tone="brand"
      bottomInset={insets.bottom}
      current={current}
      items={state.routes.map((route) => {
        const badge = route.name === 'cart' && cartCount > 0 ? cartCount : 0;
        return {
          key: route.key,
          label: t(`tab.${route.name}` as MessageKey),
          icon: ICONS[route.name] ?? 'squares-four',
          badge,
          badgeLabel: badge ? t('tab.cartCount', { n: badge }) : undefined,
          testID: `tab-${route.name}`,
        };
      })}
      onSelect={(key) => {
        const route = state.routes.find((r) => r.key === key);
        if (!route) return;
        const event = navigation.emit({ type: 'tabPress', target: route.key, canPreventDefault: true });
        if (key !== current && !event.defaultPrevented) navigation.navigate(route.name, route.params);
      }}
    />
  );
}
