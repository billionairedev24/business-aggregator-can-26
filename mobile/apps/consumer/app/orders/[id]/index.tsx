import { Redirect, useLocalSearchParams } from 'expo-router';

/**
 * `/orders/<id>`: where S-102's order links and notifications land (`routeOf` in @northline/mobile-kit) — the order's
 * tracking (S-99).
 */
export default function OrderRoute() {
  const { id } = useLocalSearchParams<{ id: string }>();
  return <Redirect href={{ pathname: '/orders/[id]/track', params: { id } }} />;
}
