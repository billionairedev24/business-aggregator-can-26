import { createFileRoute } from '@tanstack/react-router';
import { pending } from '../../features/shell/pending';

/** Food checkout (S-57). Guests see the guest banner. */
export const Route = createFileRoute('/food/checkout')({ ...pending('foodCheckout') });
