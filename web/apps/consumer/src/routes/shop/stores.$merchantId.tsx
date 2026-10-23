import { createFileRoute, notFound } from '@tanstack/react-router';
import shopCss from '../../features/shop/shop.css?url';
import { shopText } from '../../features/shop/messages';
import { ShopSkeleton } from '../../features/shop/parts';
import { StorePage } from '../../features/shop/StorePage';
import { loadStore, StoreSearch } from '../../features/shop/store';
import { pageTitle } from '../../features/shell/messages';
import { seo } from '../../lib/seo';

/** A shop's page (E-15 S-172): server-rendered for the URL's market; a shop neither its department nor the landing lists is a 404. */
export const Route = createFileRoute('/shop/stores/$merchantId')({
  validateSearch: StoreSearch,
  loaderDeps: ({ search }) => ({ market: search.market, dept: search.dept }),
  loader: async ({ context, deps, params }) => {
    const data = await loadStore(context.queryClient, params.merchantId, deps, context.locale);
    if (!data) throw notFound();
    return data;
  },
  head: ({ match, loaderData, params }) => {
    const t = shopText(match.context.locale);
    const tags = seo(match.context, {
      title: loaderData ? t('storeTitle', { shop: loaderData.shop.name }) : pageTitle(match.context.locale, 'shop'),
      description: loaderData && t('storeMetaDescription', { shop: loaderData.shop.name, department: loaderData.shop.departmentName, city: loaderData.landing.market }),
      path: `/shop/stores/${params.merchantId}`, query: { market: match.search.market },
    });
    return { ...tags, links: [...tags.links, { rel: 'stylesheet', href: shopCss }] };
  },
  pendingComponent: ShopSkeleton,
  component: StoreRoute,
});

function StoreRoute() {
  const data = Route.useLoaderData();
  const { market } = Route.useSearch();
  return <StorePage data={data} market={market} />;
}
