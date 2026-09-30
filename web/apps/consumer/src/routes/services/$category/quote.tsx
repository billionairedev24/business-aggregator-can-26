import { createFileRoute } from '@tanstack/react-router';
import { pending } from '../../../features/shell/pending';

/** Quote request for a category, no provider picked yet (S-56): design 06 `book` in quote mode, from `svcCategory`. */
export const Route = createFileRoute('/services/$category/quote')({ ...pending('quoteRequest') });
