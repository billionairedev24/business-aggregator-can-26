import { createFileRoute, notFound } from '@tanstack/react-router';
import { isNotFound } from '@northline/client';
import providerCss from '../../../features/provider/provider.css?url';
import { providerQuery, storefrontQuery } from '../../../features/provider/api';
import { ProviderPage, ProviderPageSkeleton } from '../../../features/provider/ProviderPage';
import { categoryName } from '../../../features/services/taxonomy';
import { pageTitle } from '../../../features/shell/messages';
import { seo } from '../../../lib/seo';
import { providerJsonLd } from '../../../lib/structuredData';

/**
 * Public provider page (S-54): design 06 `provider`, from the storefront API. Server-rendered and SEO-ready (title,
 * description, canonical — the business's own domain when it has one —, hreflang, Open Graph and S-63's JSON-LD
 * `LocalBusiness` with its services and rating). Also what `pages.<zone>/<slug>` and a merchant's own domain show
 * (src/lib/pages.ts); those canonicalise to the one URL.
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
    const origin = page.customDomain ? `https://${page.customDomain}` : match.context.config.siteOrigin;
    const path = page.customDomain ? '/' : `/providers/${page.slug}`;
    const image = page.logoUrl ? `${match.context.config.siteOrigin}${page.logoUrl}` : null;
    const tags = seo(match.context, {
      title, description, origin, path, type: 'business.business', image,
      jsonLd: [providerJsonLd({
        url: `${origin}${path}`, name: facts.name, description, image, city: facts.city ?? page.business.city,
        areaServed: page.business.serviceArea, category, rating: facts.rating, reviewCount: facts.reviewCount,
        services: facts.services.map(s => ({ name: s.name, description: s.included, priceCents: s.priceCents, pricingMode: s.pricingMode })),
      })],
    });
    return { ...tags, links: [...tags.links, { rel: 'stylesheet', href: providerCss }] };
  },
  pendingComponent: ProviderPageSkeleton,
  component: ProviderRoute,
});

function ProviderRoute() {
  const { slug } = Route.useParams();
  return <ProviderPage slug={slug} />;
}
