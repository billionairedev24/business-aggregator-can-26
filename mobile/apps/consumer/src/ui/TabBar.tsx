import type { BottomTabBarProps } from 'expo-router/js-tabs';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { MIN_TARGET, colors, fonts, radius } from '@northline/mobile-kit';

import { useI18n, type MessageKey } from '../i18n';

/**
 * Design 01's tab bar: five text labels (Home · Services · Cart · Orders · You), the current one in accent-700 and
 * semibold, the cart's item count on a rosehip badge; a hairline on top, the bar sticky at the bottom.
 */
export function TabBar({ state, navigation, cartCount = 0 }: Pick<BottomTabBarProps, 'state' | 'navigation'> & { cartCount?: number }) {
  const { t } = useI18n();
  const insets = useSafeAreaInsets();
  return (
    <View accessibilityRole="tablist" accessibilityLabel={t('tab.label')} style={[styles.bar, { paddingBottom: Math.max(insets.bottom, 10) }]}>
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
            <Text style={[styles.label, focused && styles.labelOn]}>{label}</Text>
            {badge ? (
              <View style={styles.badge}>
                <Text style={styles.badgeText}>{badge}</Text>
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
    justifyContent: 'space-around',
    paddingTop: 10,
    paddingHorizontal: 12,
    backgroundColor: colors.bg,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.divider,
  },
  tab: { flex: 1, minHeight: MIN_TARGET, alignItems: 'center', justifyContent: 'center' },
  label: { fontFamily: fonts.body, fontSize: 12, color: colors.neutral700 },
  labelOn: { fontFamily: fonts.bodyStrong, color: colors.accent700 },
  badge: {
    position: 'absolute',
    top: 2,
    right: 8,
    minWidth: 16,
    height: 16,
    borderRadius: radius.pill,
    paddingHorizontal: 4,
    backgroundColor: colors.accent2,
    alignItems: 'center',
    justifyContent: 'center',
  },
  badgeText: { fontFamily: fonts.bodyStrong, fontSize: 10, color: colors.onAccent },
});
