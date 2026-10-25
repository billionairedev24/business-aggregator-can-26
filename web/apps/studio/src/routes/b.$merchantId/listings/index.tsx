import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { ListingsScreen } from '../../../features/catalogue/ListingsScreen';

/** `?q=` — the shell's global search (E-15) opens the table already searched; `?view=` — the status tab (S-175). */
export const Route = createFileRoute('/b/$merchantId/listings/')({
  validateSearch: z.object({ q: z.string().optional(), view: z.string().optional() }),
  component: function ListingsRoute() {
    const { q, view } = Route.useSearch();
    const navigate = Route.useNavigate();
    return <ListingsScreen search={q} view={view} onViewChange={v => void navigate({ search: s => ({ ...s, view: v === 'all' ? undefined : v }), replace: true })} />;
  },
});
