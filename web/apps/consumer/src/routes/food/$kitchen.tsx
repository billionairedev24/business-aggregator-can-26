import { createFileRoute, notFound } from '@tanstack/react-router';
import { isNotFound } from '@northline/client';
import foodCss from '../../features/food/food.css?url';
import { restaurantQuery } from '../../features/food/api';
import { RestaurantScreen } from '../../features/food/RestaurantScreen';

/** Restaurant menu (S-57): modifiers, combos. Server-rendered for SEO. */
export const Route = createFileRoute('/food/$kitchen')({
  loader: async ({ context, params }) => {
    try {
      return await context.queryClient.ensureQueryData(restaurantQuery(params.kitchen));
    } catch (e) {
      if (isNotFound(e)) throw notFound();
      throw e;
    }
  },
  head: ({ loaderData }) => ({
    meta: loaderData ? [
      { title: `${loaderData.kitchen.name} · Northline` },
      { name: 'description', content: [loaderData.kitchen.name, loaderData.address].filter(Boolean).join(' · ') },
    ] : [{ title: 'Northline' }],
    links: [{ rel: 'stylesheet', href: foodCss }],
  }),
  component: Restaurant,
});

function Restaurant() {
  const { kitchen } = Route.useParams();
  return <RestaurantScreen slug={kitchen} />;
}
