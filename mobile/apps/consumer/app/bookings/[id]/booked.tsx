import { useLocalSearchParams } from 'expo-router';

import { Booked as Screen } from '../../../src/services/Booking';

/** Design 01 `booked` (S-100): the confirmation. */
export default function Booked() {
  const { id } = useLocalSearchParams<{ id: string }>();
  return <Screen id={String(id)} />;
}
