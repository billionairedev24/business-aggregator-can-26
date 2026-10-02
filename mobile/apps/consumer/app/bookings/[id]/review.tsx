import { useLocalSearchParams } from 'expo-router';

import { Review as Screen } from '../../../src/services/Booking';

/** Design 01 `review` (S-100): the two-way review. */
export default function Review() {
  const { id } = useLocalSearchParams<{ id: string }>();
  return <Screen id={String(id)} />;
}
