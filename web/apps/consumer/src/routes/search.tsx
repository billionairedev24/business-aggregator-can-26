import { createFileRoute } from '@tanstack/react-router';
import { queryOptions, useSuspenseQuery } from '@tanstack/react-query';
import { z } from 'zod';
const Search = z.object({ q: z.string().default(''), scope: z.enum(['all', 'services', 'shop', 'food']).default('all') });
const searchQuery = (s: z.infer<typeof Search>) => queryOptions({ queryKey: ['search', s], queryFn: () => fetch(`/api/v1/search?q=${encodeURIComponent(s.q)}&scope=${s.scope}`).then(r => r.json()) });
export const Route = createFileRoute('/search')({
  validateSearch: Search,
  loaderDeps: ({ search }) => search,
  loader: ({ context, deps }) => context.queryClient.ensureQueryData(searchQuery(deps)),
  component: () => { const s = Route.useSearch(); const { data } = useSuspenseQuery(searchQuery(s)); return <pre>{JSON.stringify(data, null, 2)}</pre>; },
});
