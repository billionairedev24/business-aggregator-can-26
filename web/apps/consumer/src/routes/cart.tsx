import { createFileRoute } from '@tanstack/react-router';
import { pending } from '../features/shell/pending';

/** Cart and checkout (S-51): multi-shop, Stripe Payment Element. Guests see the guest banner. */
export const Route = createFileRoute('/cart')({ ...pending('cart') });
