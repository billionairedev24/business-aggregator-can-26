import { createFileRoute, notFound } from '@tanstack/react-router';
import { isNotFound } from '@northline/client';
import servicesCss from '../../../features/services/services.css?url';
import { serviceCategoryQuery } from '../../../features/services/api';
import { ServiceCategory, ServiceCategorySkeleton } from '../../../features/services/ServiceCategory';
import { categoryName } from '../../../features/services/taxonomy';
import { pageTitle } from '../../../features/shell/messages';
import { seo } from '../../../lib/seo';

/** Service category (S-53): design 06 `svcCategory` (visit / home / event / appointment / consult); server-rendered. */
export const Route = createFileRoute('/services/$category/')({
  loader: async ({ context, params }) => {
    try {
      return await context.queryClient.ensureQueryData(serviceCategoryQuery(params.category, context.locale));
    } catch (e) {
      if (isNotFound(e)) throw notFound();
      throw e;
    }
  },
  head: ({ match, loaderData, params }) => {
    const locale = match.context.locale;
    const name = loaderData ? categoryName(loaderData.slug, loaderData.names, locale).text : undefined;
    const tags = seo(match.context, {
      title: name ? `${name} · Northline` : pageTitle(locale, 'svcCategory'),
      path: `/services/${params.category}`,
      description: name && (locale === 'fr'
        ? `${name} : prestataires vérifiés, prix typiques, paiement en fiducie.`
        : `${name}: verified providers, typical prices, every job paid into escrow.`),
    });
    return { ...tags, links: [...tags.links, { rel: 'stylesheet', href: servicesCss }] };
  },
  pendingComponent: ServiceCategorySkeleton,
  component: CategoryRoute,
});

function CategoryRoute() {
  const { category } = Route.useParams();
  return <ServiceCategory slug={category} />;
}
