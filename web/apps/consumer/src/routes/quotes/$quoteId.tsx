import { createFileRoute } from '@tanstack/react-router';
import { pending } from '../../features/shell/pending';

/** Quote received (S-56): itemized lines, accept with an escrow deposit. */
export const Route = createFileRoute('/quotes/$quoteId')({ ...pending('quote') });
