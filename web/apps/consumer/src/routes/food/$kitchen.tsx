import { createFileRoute, notFound } from '@tanstack/react-router';
import { z } from 'zod';
import { isNotFound } from '@northline/client';
import foodCss from '../../features/food/food.css?url';
import { restaurantQuery } from '../../features/food/api';
import { RestaurantScreen } from '../../features/food/RestaurantScreen';
import { CUISINE_NAMES } from '../../features/food/messages';
import { seo } from '../../lib/seo';
import { restaurantJsonLd } from '../../lib/structuredData';
import { brand } from '@northline/tokens';

/** Restaurant menu (S-57): modifiers, combos. Server-rendered for SEO; JSON-LD `Restaurant` with its menu (S-63). */
export const Route = createFileRoute('/food/$kitchen')({
  // S-159: `?group=<id>` adds the dishes to a group order
  validateSearch: z.object({ group: z.string().max(40).optional().catch(undefined) }),
  loader: async ({ context, params }) => {
    try {
      return await context.queryClient.ensureQueryData(restaurantQuery(params.kitchen));
    } catch (e) {
      if (isNotFound(e)) throw notFound();
      throw e;
    }
  },
  head: ({ match, loaderData, params }) => {
    const css = { rel: 'stylesheet', href: foodCss };
    if (!loaderData) return { meta: [{ title: brand.name }], links: [css] };
    const { locale, config } = match.context;
    const k = loaderData.kitchen;
    const cuisines = k.cuisines.map(c => CUISINE_NAMES[c]?.[locale] ?? c);
    const path = `/food/${params.kitchen}`;
    const tags = seo(match.context, {
      title: `${k.name} · ${brand.name}`, path, type: 'restaurant.restaurant',
      description: [k.name, cuisines.join(', '), loaderData.address].filter(Boolean).join(' · '),
      jsonLd: [restaurantJsonLd({
        url: `${config.siteOrigin.replace(/\/$/, '')}${path}`, name: k.name, address: loaderData.address, province: loaderData.province,
        cuisines, rating: k.rating, reviewCount: k.reviews, priceLevel: k.priceLevel, delivers: k.fulfilment.includes('delivery'),
        sections: loaderData.sections,
      })],
    });
    return { ...tags, links: [...tags.links, css] };
  },
  component: Restaurant,
});

function Restaurant() {
  const { kitchen } = Route.useParams();
  const { group } = Route.useSearch();
  return <RestaurantScreen slug={kitchen} group={group} />;
}
