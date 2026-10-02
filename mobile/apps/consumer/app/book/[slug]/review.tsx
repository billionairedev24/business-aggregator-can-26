import { useLocalSearchParams } from 'expo-router';

import { BookReview as Screen } from '../../../src/services/BookReview';

/** Design 01 `book_review` (S-100): review and hold the payment in escrow. */
export default function BookReview() {
  const { slug } = useLocalSearchParams<{ slug: string }>();
  return <Screen slug={String(slug)} />;
}
