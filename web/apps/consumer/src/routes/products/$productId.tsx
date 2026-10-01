import { createFileRoute, notFound } from '@tanstack/react-router';
import { isNotFound as isApiNotFound } from '@northline/client';
import shopCss from '../../features/shop/shop.css?url';
import productCss from '../../features/product/product.css?url';
import { productQuery, ProductSearch } from '../../features/product/api';
import { productText } from '../../features/product/messages';
import { ProductDetail, ProductSkeleton } from '../../features/product/ProductDetail';
import { seo } from '../../lib/seo';
import { productJsonLd } from '../../lib/structuredData';

/** Product detail (S-50): offers, variants, stock, delivery cut-off. Server-rendered for SEO; 404 when unpublished. */
export const Route = createFileRoute('/products/$productId')({
  validateSearch: ProductSearch,
  loaderDeps: ({ search }) => ({ market: search.market }),
  loader: async ({ context, deps, params }) => {
    try {
      return await context.queryClient.ensureQueryData(productQuery(params.productId, deps.market, context.locale));
    } catch (e) {
      if (isApiNotFound(e)) throw notFound();
      throw e;
    }
  },
  head: ({ match, loaderData, params }) => {
    const t = productText(match.context.locale);
    const css = [{ rel: 'stylesheet', href: shopCss }, { rel: 'stylesheet', href: productCss }];
    if (!loaderData) return { meta: [{ title: 'Northline' }], links: css };
    const shop = loaderData.offers[0]?.shopName;
    const path = `/products/${params.productId}`;
    const query = { market: match.search.market };
    const site = match.context.config.siteOrigin.replace(/\/$/, '');
    const images = [...new Set(loaderData.offers.flatMap(o => o.images))].map(src => (src.startsWith('/') ? `${site}${src}` : src));
    const tags = seo(match.context, {
      title: shop ? t('title', { name: loaderData.name, shop }) : t('titleNoShop', { name: loaderData.name }),
      description: loaderData.description ?? loaderData.bullets.join(' · '), path, query, type: 'product', image: images[0],
      jsonLd: [productJsonLd({
        url: `${site}${path}`, name: loaderData.name, description: loaderData.description, brand: loaderData.brand,
        category: loaderData.departmentName || null, images, offers: loaderData.offers,
      })],
    });
    return { ...tags, links: [...tags.links, ...css] };
  },
  pendingComponent: ProductSkeleton,
  component: ProductRoute,
});

function ProductRoute() {
  const { productId } = Route.useParams();
  const { market, offer } = Route.useSearch();
  return <ProductDetail productId={productId} market={market} offerId={offer} />;
}
