import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import foodCss from '../../features/food/food.css?url';
import { FoodScreen } from '../../features/food/FoodScreen';
import { pageTitle } from '../../features/shell/messages';
import { seo } from '../../lib/seo';

/** Food landing (S-57): kitchens open now, cuisines. `?cuisine=` comes from the home page's tiles (S-46). */
export const Route = createFileRoute('/food/')({
  validateSearch: z.object({ cuisine: z.string().optional().catch(undefined) }),
  head: ({ match }) => {
    const tags = seo(match.context, {
      title: pageTitle(match.context.locale, 'food'), path: '/food', query: { cuisine: match.search.cuisine },
      description: match.context.locale === 'fr'
        ? 'Cuisines locales ouvertes maintenant — livraison ou cueillette, paiement libéré à la remise.'
        : 'Local kitchens open now — delivery or pickup, paid to the kitchen on handoff.',
    });
    return { ...tags, links: [...tags.links, { rel: 'stylesheet', href: foodCss }] };
  },
  component: Food,
});

function Food() {
  const { cuisine } = Route.useSearch();
  return <FoodScreen cuisine={cuisine} />;
}
