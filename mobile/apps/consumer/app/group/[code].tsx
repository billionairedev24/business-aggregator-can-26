import { useLocalSearchParams } from 'expo-router';

import { GroupOrder } from '../../src/food/GroupOrder';

/** A group food order (S-159): src/food/GroupOrder.tsx. */
export default function GroupOrderRoute() {
  const { code } = useLocalSearchParams<{ code: string }>();
  return <GroupOrder code={String(code ?? '')} />;
}
