import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { pending } from '../features/shell/pending';

/** Sell or offer a service (S-61): entry into Studio onboarding (07a–07d). */
export const SellParams = z.object({ type: z.enum(['seller', 'provider', 'kitchen']).optional().catch(undefined) });
export const Route = createFileRoute('/sell')({ validateSearch: SellParams, ...pending('sell') });
