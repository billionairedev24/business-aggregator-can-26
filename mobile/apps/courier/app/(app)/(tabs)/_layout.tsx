import type { BottomTabBarProps } from 'expo-router/js-tabs';
import { Tabs } from 'expo-router/js-tabs';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { Icon, MIN_TARGET, colors, fonts, type IconName } from '@northline/mobile-kit';

import { useI18n, type MessageKey } from '../../../src/i18n';

const ICONS: Record<string, IconName> = { index: 'house', run: 'path', account: 'user-circle' };
const LABELS: Record<string, MessageKey> = { index: 'tab.today', run: 'tab.route', account: 'tab.profile' };

/**
 * The driver app's tabs (redesign spec § 2.5, AT DR: Home · Route · Scan · Profile; Northline confirms a sealed bag, so
 * no Scan): Today · Route · Profile — a 24 pt icon (fill when current) over a 10 pt semibold label. A stop and its
 * pickup / drop-off open above the tabs with the round Back button.
 */
function CourierTabBar({ state, navigation }: Pick<BottomTabBarProps, 'state' | 'navigation'>) {
  const { t } = useI18n();
  const insets = useSafeAreaInsets();
  return (
    <View accessibilityRole="tablist" accessibilityLabel={t('tab.label')} style={[styles.bar, { paddingBottom: Math.max(insets.bottom, 8) }]}>
      {state.routes.map((route, index) => {
        const focused = state.index === index;
        const label = t(LABELS[route.name] ?? 'tab.today');
        const onPress = () => {
          const event = navigation.emit({ type: 'tabPress', target: route.key, canPreventDefault: true });
          if (!focused && !event.defaultPrevented) navigation.navigate(route.name, route.params);
        };
        return (
          <Pressable key={route.key} accessibilityRole="tab" accessibilityState={{ selected: focused }} accessibilityLabel={label} onPress={onPress} style={styles.tab} testID={`tab-${route.name}`}>
            <Icon name={ICONS[route.name] ?? 'house'} size={24} filled={focused} color={focused ? colors.text : colors.textSubtle} />
            <Text style={[styles.label, focused && styles.labelOn]} numberOfLines={1} maxFontSizeMultiplier={1.3}>
              {label}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}

export default function TabsLayout() {
  return (
    <Tabs screenOptions={{ headerShown: false, sceneStyle: { backgroundColor: colors.surface } }} tabBar={(props) => <CourierTabBar state={props.state} navigation={props.navigation} />}>
      <Tabs.Screen name="index" />
      <Tabs.Screen name="run" />
      <Tabs.Screen name="account" />
    </Tabs>
  );
}

const styles = StyleSheet.create({
  bar: { flexDirection: 'row', paddingTop: 6, paddingHorizontal: 8, backgroundColor: colors.surface, borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: colors.border },
  tab: { flex: 1, minHeight: Math.max(MIN_TARGET, 50), alignItems: 'center', justifyContent: 'center', gap: 2 },
  label: { fontFamily: fonts.semibold, fontSize: 10, color: colors.textSubtle },
  labelOn: { color: colors.text },
});
