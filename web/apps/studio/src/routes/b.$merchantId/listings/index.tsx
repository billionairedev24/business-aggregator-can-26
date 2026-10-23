import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { ListingsScreen } from '../../../features/catalogue/ListingsScreen';

/** `?q=` — the shell's global search (E-15) opens the table already searched. */
export const Route = createFileRoute('/b/$merchantId/listings/')({
  validateSearch: z.object({ q: z.string().optional() }),
  component: function ListingsRoute() { return <ListingsScreen search={Route.useSearch().q} />; },
});
