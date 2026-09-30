import { createFileRoute } from '@tanstack/react-router';
import { pending } from '../../../features/shell/pending';

/** Booking wizard (S-55): job details → location & access → schedule → payment → confirmed. */
export const Route = createFileRoute('/providers/$slug/book')({ ...pending('book') });
