import { Tabs } from 'expo-router/js-tabs';

import { colors } from '@northline/mobile-kit';

import { useCartCount } from '../../src/shop/common';
import { TabBar } from '../../src/ui/TabBar';

/**
 * The bottom tabs of the redesign (spec § 2.4): Home · Browse · Activity · Cart · Account — the routes keep their
 * names (`services` is Browse, `orders` is Activity) so deep links and the screens' links stay. Each tab screen draws
 * its own top (no navigation header); sub-screens open on the root stack above the tabs with the round Back header.
 * S-99 passes the cart's item count to the badge.
 */
export default function TabsLayout() {
  const cartCount = useCartCount();
  return (
    <Tabs
      screenOptions={{ headerShown: false, sceneStyle: { backgroundColor: colors.surface } }}
      tabBar={(props) => <TabBar state={props.state} navigation={props.navigation} cartCount={cartCount} />}
    >
      <Tabs.Screen name="home" />
      <Tabs.Screen name="services" />
      <Tabs.Screen name="orders" />
      <Tabs.Screen name="cart" />
      <Tabs.Screen name="account" />
    </Tabs>
  );
}
