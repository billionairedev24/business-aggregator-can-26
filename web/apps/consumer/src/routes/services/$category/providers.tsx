import { createFileRoute, notFound } from '@tanstack/react-router';
import { isNotFound } from '@northline/client';
import servicesCss from '../../../features/services/services.css?url';
import { serviceCategoryQuery } from '../../../features/services/api';
import { ProviderList } from '../../../features/services/ProviderList';
import { ServiceCategorySkeleton } from '../../../features/services/ServiceCategory';
import { categoryName } from '../../../features/services/taxonomy';
import { pageTitle } from '../../../features/shell/messages';

/**
 * Provider list (S-53): design 06 `providers`. The category is rendered on the server; the providers covering the
 * customer's location load in the browser (the server doesn't know the location).
 */
export const Route = createFileRoute('/services/$category/providers')({
  loader: async ({ context, params }) => {
    try {
      return await context.queryClient.ensureQueryData(serviceCategoryQuery(params.category, context.locale));
    } catch (e) {
      if (isNotFound(e)) throw notFound();
      throw e;
    }
  },
  head: ({ match, loaderData }) => {
    const locale = match.context.locale;
    const name = loaderData ? categoryName(loaderData.slug, loaderData.names, locale).text : undefined;
    return {
      meta: [{ title: name ? `${name} · ${locale === 'fr' ? 'Prestataires' : 'Providers'} · Northline` : pageTitle(locale, 'providers') }],
      links: [{ rel: 'stylesheet', href: servicesCss }],
    };
  },
  pendingComponent: ServiceCategorySkeleton,
  component: ProvidersRoute,
});

function ProvidersRoute() {
  const { category } = Route.useParams();
  return <ProviderList slug={category} />;
}
