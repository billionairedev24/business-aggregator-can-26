import { createFileRoute } from '@tanstack/react-router';
import { pending } from '../features/shell/pending';

/** Location (S-47): province, Google Places address, market/zone; saves with useDeliveryLocation().save(). */
export const Route = createFileRoute('/location')({ ...pending('location') });
