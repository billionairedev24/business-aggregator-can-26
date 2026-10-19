import type { BottomTabBarProps } from 'expo-router/js-tabs';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { Icon, colors, fonts, radius, semantic, type IconName } from '@northline/mobile-kit';

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
 * The tab bar (light direction): five equal tabs — Home · Browse · Activity · Cart · Account — each a 24 pt Phosphor
 * icon over an 11 pt label; the current one in the brand colour with the filled icon and a short bar above it, the
 * others muted; the cart's count on an 18 pt pill; white with a hairline on top, the home indicator's room below.
 */
export function TabBar({ state, navigation, cartCount = 0 }: Pick<BottomTabBarProps, 'state' | 'navigation'> & { cartCount?: number }) {
  const { t } = useI18n();
  const insets = useSafeAreaInsets();
  return (
    <View accessibilityRole="tablist" accessibilityLabel={t('tab.label')} style={[styles.bar, { paddingBottom: Math.max(insets.bottom, 8) }]}>
      {state.routes.map((route, index) => {
        const focused = state.index === index;
        const label = t(`tab.${route.name}` as MessageKey);
        const badge = route.name === 'cart' && cartCount > 0 ? cartCount : 0;
        const onPress = () => {
          const event = navigation.emit({ type: 'tabPress', target: route.key, canPreventDefault: true });
          if (!focused && !event.defaultPrevented) navigation.navigate(route.name, route.params);
        };
        return (
          <Pressable
            key={route.key}
            accessibilityRole="tab"
            accessibilityState={{ selected: focused }}
            accessibilityLabel={badge ? `${label}, ${t('tab.cartCount', { n: badge })}` : label}
            onPress={onPress}
            style={styles.tab}
            testID={`tab-${route.name}`}
          >
            {focused ? <View style={styles.marker} /> : null}
            <Icon name={ICONS[route.name] ?? 'squares-four'} size={24} filled={focused} color={focused ? semantic['action-primary'] : colors.textMuted} />
            <Text style={[styles.label, focused && styles.labelOn]} numberOfLines={1} maxFontSizeMultiplier={1.3}>
              {label}
            </Text>
            {badge ? (
              <View style={styles.badge}>
                <Text style={styles.badgeText} maxFontSizeMultiplier={1.2}>
                  {badge > 99 ? '99+' : badge}
                </Text>
              </View>
            ) : null}
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  bar: {
    flexDirection: 'row',
    paddingTop: 4,
    paddingHorizontal: 4,
    backgroundColor: colors.surface,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.borderStrong,
  },
  tab: { flex: 1, minHeight: 52, alignItems: 'center', justifyContent: 'center', gap: 3 },
  marker: { position: 'absolute', top: -4, width: 28, height: 3, borderBottomLeftRadius: 3, borderBottomRightRadius: 3, backgroundColor: semantic['action-primary'] },
  label: { fontFamily: fonts.medium, fontSize: 11, color: colors.textMuted },
  labelOn: { fontFamily: fonts.bold, color: semantic['action-primary'] },
  badge: {
    position: 'absolute',
    top: 2,
    left: '50%',
    marginLeft: 6,
    minWidth: 18,
    height: 18,
    borderRadius: radius.pill,
    paddingHorizontal: 5,
    backgroundColor: semantic['cart-badge'],
    alignItems: 'center',
    justifyContent: 'center',
  },
  badgeText: { fontFamily: fonts.bold, fontSize: 10, color: semantic['cart-badge-text'] },
});
