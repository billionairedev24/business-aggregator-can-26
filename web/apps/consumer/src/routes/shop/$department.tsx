import { createFileRoute, notFound } from '@tanstack/react-router';
import { isNotFound as isApiNotFound } from '@northline/client';
import shopCss from '../../features/shop/shop.css?url';
import { departmentQuery, MarketSearch } from '../../features/shop/api';
import { DepartmentPage } from '../../features/shop/DepartmentPage';
import { shopText } from '../../features/shop/messages';
import { ShopSkeleton } from '../../features/shop/parts';
import { pageTitle } from '../../features/shell/messages';
import { seo } from '../../lib/seo';

/** Department / category page (S-49): design 06 `category`. Server-rendered for SEO; an unknown department is a 404. */
export const Route = createFileRoute('/shop/$department')({
  validateSearch: MarketSearch,
  loaderDeps: ({ search }) => ({ market: search.market }),
  loader: async ({ context, deps, params }) => {
    try {
      return await context.queryClient.ensureQueryData(departmentQuery(params.department, deps.market, context.locale));
    } catch (e) {
      if (isApiNotFound(e)) throw notFound();
      throw e;
    }
  },
  head: ({ match, loaderData, params }) => {
    const t = shopText(match.context.locale);
    const tags = seo(match.context, {
      title: loaderData ? t('deptTitle', { name: loaderData.name }) : pageTitle(match.context.locale, 'category'),
      description: loaderData && t('deptMetaDescription', { name: loaderData.name, city: loaderData.market }),
      path: `/shop/${params.department}`, query: { market: match.search.market },
    });
    return { ...tags, links: [...tags.links, { rel: 'stylesheet', href: shopCss }] };
  },
  pendingComponent: ShopSkeleton,
  component: DepartmentRoute,
});

function DepartmentRoute() {
  const { department } = Route.useParams();
  const { market } = Route.useSearch();
  return <DepartmentPage slug={department} market={market} />;
}
