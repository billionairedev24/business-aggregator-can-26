import { createFileRoute } from '@tanstack/react-router';
import searchCss from '../features/search/search.css?url';
import { apiQuery, SearchParams, searchQuery } from '../features/search/api';
import { searchText } from '../features/search/messages';
import { SearchResults, SearchSkeleton } from '../features/search/SearchResults';

export { SearchParams } from '../features/search/api';

/**
 * Search results (S-48, design 06 `search`): `?q=` from the header / hero search, `scope` narrows to one side of the
 * marketplace, the rest are the page's filters and sort. The server renders the first page without a place (the
 * same HTML for everyone); the browser re-asks with the visitor's province and coordinates once known.
 */
export const Route = createFileRoute('/search')({
  validateSearch: SearchParams,
  loaderDeps: ({ search }) => search,
  loader: async ({ context, deps }) => {
    // A failing search renders the page's own error state (with Retry) rather than the route's.
    await context.queryClient.ensureInfiniteQueryData(searchQuery(apiQuery(deps, null, context.locale))).catch(() => undefined);
  },
  head: ({ match }) => {
    const t = searchText(match.context.locale);
    const q = match.search.q?.trim();
    return {
      meta: [
        { title: q ? t('title', { q }) : t('titleBlank') },
        { name: 'description', content: t('description') },
        // result pages are endless and personal to a query: crawlers follow the links, the listings get indexed
        { name: 'robots', content: 'noindex, follow' },
      ],
      links: [{ rel: 'stylesheet', href: searchCss }],
    };
  },
  pendingComponent: SearchSkeleton,
  component: SearchRoute,
});

function SearchRoute() {
  return <SearchResults params={Route.useSearch()} />;
}
