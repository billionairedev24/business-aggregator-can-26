import { useLocalSearchParams } from 'expo-router';

import { Providers as ProviderList } from '../../src/services/Providers';

/** Design 01 `providers` (S-100): a category's providers. */
export default function Providers() {
  const { category } = useLocalSearchParams<{ category: string }>();
  return <ProviderList slug={String(category)} />;
}
