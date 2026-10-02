import { useLocalSearchParams } from 'expo-router';

import { Eta as Screen } from '../../../src/services/Booking';

/** Design 01 `eta` (S-100): the day-of screen. */
export default function Eta() {
  const { id } = useLocalSearchParams<{ id: string }>();
  return <Screen id={String(id)} />;
}
