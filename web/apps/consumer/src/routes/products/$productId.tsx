import { createFileRoute, notFound, redirect } from '@tanstack/react-router';
import { isNotFound as isApiNotFound } from '@northline/client';
import shopCss from '../../features/shop/shop.css?url';
import productCss from '../../features/product/product.css?url';
import { productQuery, ProductSearch } from '../../features/product/api';
import { productText } from '../../features/product/messages';
import { ProductDetail, ProductSkeleton } from '../../features/product/ProductDetail';

/** Product detail (S-50): offers, variants, stock, delivery cut-off. Server-rendered for SEO; 404 when unpublished. */
export const Route = createFileRoute('/products/$productId')({
  validateSearch: ProductSearch,
  loaderDeps: ({ search }) => ({ market: search.market, offer: search.offer }),
  loader: async ({ context, deps, params }) => {
    let page;
    try {
      page = await context.queryClient.ensureQueryData(productQuery(params.productId, deps.market, context.locale));
    } catch (e) {
      if (isApiNotFound(e)) throw notFound();
      throw e;
    }
    // S-48: a search result is an offer; the api names its product, and the page lives at the product's own URL.
    if (page.productId !== params.productId) {
      throw redirect({ to: '/products/$productId', params: { productId: page.productId }, search: { market: deps.market, offer: deps.offer ?? params.productId }, statusCode: 301 });
    }
    return page;
  },
  head: ({ match, loaderData }) => {
    const t = productText(match.context.locale);
    const shop = loaderData?.offers[0]?.shopName;
    return {
      meta: loaderData
        ? [{ title: shop ? t('title', { name: loaderData.name, shop }) : t('titleNoShop', { name: loaderData.name }) },
          ...(loaderData.description ? [{ name: 'description', content: loaderData.description }] : [])]
        : [{ title: 'Northline' }],
      links: [{ rel: 'stylesheet', href: shopCss }, { rel: 'stylesheet', href: productCss }],
    };
  },
  pendingComponent: ProductSkeleton,
  component: ProductRoute,
});

function ProductRoute() {
  const { productId } = Route.useParams();
  const { market, offer } = Route.useSearch();
  return <ProductDetail productId={productId} market={market} offerId={offer} />;
}
