import { useLocalSearchParams } from 'expo-router';

import { BookTime as Screen } from '../../../src/services/BookTime';

/** Design 01 `book_slot` (S-100): the live calendar. */
export default function BookTime() {
  const { slug } = useLocalSearchParams<{ slug: string }>();
  return <Screen slug={String(slug)} />;
}
