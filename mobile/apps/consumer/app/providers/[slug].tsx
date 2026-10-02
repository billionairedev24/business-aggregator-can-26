import { useLocalSearchParams } from 'expo-router';

import { Provider as ProviderProfile } from '../../src/services/Provider';

/** Design 01 `provider` (S-100): the provider profile. */
export default function Provider() {
  const { slug } = useLocalSearchParams<{ slug: string }>();
  return <ProviderProfile slug={String(slug)} />;
}
