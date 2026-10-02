import { useLocalSearchParams } from 'expo-router';

import { BookService as Screen } from '../../../src/services/BookService';

/** Design 01 `book_service` (S-100): the booking wizard's first step. */
export default function BookService() {
  const { slug } = useLocalSearchParams<{ slug: string }>();
  return <Screen slug={String(slug)} />;
}
