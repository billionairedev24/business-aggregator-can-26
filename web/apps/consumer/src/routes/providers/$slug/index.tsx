import { createFileRoute, notFound } from '@tanstack/react-router';
import { isNotFound } from '@northline/client';
import providerCss from '../../../features/provider/provider.css?url';
import { providerQuery, storefrontQuery } from '../../../features/provider/api';
import { ProviderPage, ProviderPageSkeleton } from '../../../features/provider/ProviderPage';
import { categoryName } from '../../../features/services/taxonomy';
import { pageTitle } from '../../../features/shell/messages';

/**
 * Public provider page (S-54): design 06 `provider`, from the storefront API. Server-rendered and SEO-ready (title,
 * description, canonical, Open Graph); structured data is S-63. Also what `pages.<zone>/<slug>` and a merchant's own
 * domain show (src/lib/pages.ts).
 */
export const Route = createFileRoute('/providers/$slug/')({
  loader: async ({ context, params }) => {
    try {
      const [page, facts] = await Promise.all([
        context.queryClient.ensureQueryData(storefrontQuery(params.slug)),
        context.queryClient.ensureQueryData(providerQuery(params.slug, context.locale)),
      ]);
      return { page, facts };
    } catch (e) {
      if (isNotFound(e)) throw notFound();
      throw e;
    }
  },
  head: ({ match, loaderData }) => {
    const locale = match.context.locale;
    if (!loaderData) return { meta: [{ title: pageTitle(locale, 'provider') }] };
    const { page, facts } = loaderData;
    const category = facts.category ? categoryName(facts.category.slug, facts.category.names, locale).text : null;
    const title = [facts.name, category, facts.city].filter(Boolean).join(' · ') + ' · Northline';
    const description = page.business.about ?? page.tagline ?? title;
    const canonical = page.customDomain ? `https://${page.customDomain}/` : `${match.context.config.siteOrigin}/providers/${page.slug}`;
    return {
      meta: [
        { title },
        { name: 'description', content: description.slice(0, 300) },
        { property: 'og:type', content: 'business.business' },
        { property: 'og:title', content: facts.name },
        { property: 'og:description', content: description.slice(0, 300) },
        { property: 'og:url', content: canonical },
        ...(page.logoUrl ? [{ property: 'og:image', content: `${match.context.config.siteOrigin}${page.logoUrl}` }] : []),
      ],
      links: [{ rel: 'canonical', href: canonical }, { rel: 'stylesheet', href: providerCss }],
    };
  },
  pendingComponent: ProviderPageSkeleton,
  component: ProviderRoute,
});

function ProviderRoute() {
  const { slug } = Route.useParams();
  return <ProviderPage slug={slug} />;
}
