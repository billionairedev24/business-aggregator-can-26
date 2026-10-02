import { useLocalSearchParams } from 'expo-router';

import { SignOff as Screen } from '../../../src/services/Booking';

/** Design 01 `signoff` (S-100): completion and sign-off. */
export default function SignOff() {
  const { id } = useLocalSearchParams<{ id: string }>();
  return <Screen id={String(id)} />;
}
