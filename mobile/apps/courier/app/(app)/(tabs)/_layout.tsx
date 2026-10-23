import type { BottomTabBarProps } from 'expo-router/js-tabs';
import { Tabs } from 'expo-router/js-tabs';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { BottomTabBar, colors, type IconName } from '@northline/mobile-kit';

import { useI18n, type MessageKey } from '../../../src/i18n';

const ICONS: Record<string, IconName> = { index: 'house', run: 'path', account: 'user-circle' };
const LABELS: Record<string, MessageKey> = { index: 'tab.today', run: 'tab.route', account: 'tab.profile' };

/**
 * The driver app's tabs: Today · Route · Profile, on the kit's BottomTabBar (S-169, the consumer app's tab bar): a
 * Phosphor icon over a short label, the current tab in ink with the filled icon and an ink bar on top. A stop and its
 * pickup / drop-off open above the tabs with the Back button.
 */
function CourierTabBar({ state, navigation }: Pick<BottomTabBarProps, 'state' | 'navigation'>) {
  const { t } = useI18n();
  const insets = useSafeAreaInsets();
  const current = state.routes[state.index]?.key ?? '';
  return (
    <BottomTabBar
      label={t('tab.label')}
      bottomInset={insets.bottom}
      current={current}
      items={state.routes.map((route) => ({ key: route.key, label: t(LABELS[route.name] ?? 'tab.today'), icon: ICONS[route.name] ?? 'house', testID: `tab-${route.name}` }))}
      onSelect={(key) => {
        const route = state.routes.find((r) => r.key === key);
        if (!route) return;
        const event = navigation.emit({ type: 'tabPress', target: route.key, canPreventDefault: true });
        if (key !== current && !event.defaultPrevented) navigation.navigate(route.name, route.params);
      }}
    />
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
