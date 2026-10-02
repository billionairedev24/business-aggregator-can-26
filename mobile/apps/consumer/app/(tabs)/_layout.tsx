import { Tabs } from 'expo-router/js-tabs';

import { colors } from '@northline/mobile-kit';

import { TabBar } from '../../src/ui/TabBar';

/**
 * The bottom tabs of design 01 (Home · Services · Cart · Orders · You). Each tab screen draws its own top (no
 * navigation header); sub-screens open on the root stack above the tabs with the design's "← Back" header.
 * S-99 passes the cart's item count to the badge.
 */
export default function TabsLayout() {
  return (
    <Tabs
      screenOptions={{ headerShown: false, sceneStyle: { backgroundColor: colors.bg } }}
      tabBar={(props) => <TabBar state={props.state} navigation={props.navigation} />}
    >
      <Tabs.Screen name="home" />
      <Tabs.Screen name="services" />
      <Tabs.Screen name="cart" />
      <Tabs.Screen name="orders" />
      <Tabs.Screen name="account" />
    </Tabs>
  );
}
