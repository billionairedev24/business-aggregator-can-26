import { createFileRoute } from '@tanstack/react-router';
import shopCss from '../../features/shop/shop.css?url';
import { landingQuery, MarketSearch } from '../../features/shop/api';
import { shopText } from '../../features/shop/messages';
import { ShopSkeleton } from '../../features/shop/parts';
import { ShopLanding } from '../../features/shop/ShopLanding';
import { pageTitle } from '../../features/shell/messages';

/** Shop landing (S-49): a landing page, not search results. Server-rendered for the URL's market (none = the api's fallback market). */
export const Route = createFileRoute('/shop/')({
  validateSearch: MarketSearch,
  loaderDeps: ({ search }) => ({ market: search.market }),
  loader: ({ context, deps }) => context.queryClient.ensureQueryData(landingQuery(deps.market, context.locale)),
  head: ({ match, loaderData }) => ({
    meta: [
      { title: pageTitle(match.context.locale, 'shop') },
      { name: 'description', content: shopText(match.context.locale)('metaDescription', { city: loaderData?.market ?? '' }) },
    ],
    links: [{ rel: 'stylesheet', href: shopCss }],
  }),
  pendingComponent: ShopSkeleton,
  component: ShopRoute,
});

function ShopRoute() {
  const { market } = Route.useSearch();
  return <ShopLanding market={market} />;
}
