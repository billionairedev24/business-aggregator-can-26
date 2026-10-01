import { createFileRoute, notFound } from '@tanstack/react-router';
import { isNotFound as isApiNotFound } from '@northline/client';
import shopCss from '../../features/shop/shop.css?url';
import { departmentQuery, MarketSearch } from '../../features/shop/api';
import { DepartmentPage } from '../../features/shop/DepartmentPage';
import { shopText } from '../../features/shop/messages';
import { ShopSkeleton } from '../../features/shop/parts';
import { pageTitle } from '../../features/shell/messages';

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
  head: ({ match, loaderData }) => {
    const t = shopText(match.context.locale);
    return {
      meta: loaderData
        ? [{ title: t('deptTitle', { name: loaderData.name }) }, { name: 'description', content: t('deptMetaDescription', { name: loaderData.name, city: loaderData.market }) }]
        : [{ title: pageTitle(match.context.locale, 'category') }],
      links: [{ rel: 'stylesheet', href: shopCss }],
    };
  },
  pendingComponent: ShopSkeleton,
  component: DepartmentRoute,
});

function DepartmentRoute() {
  const { department } = Route.useParams();
  const { market } = Route.useSearch();
  return <DepartmentPage slug={department} market={market} />;
}
