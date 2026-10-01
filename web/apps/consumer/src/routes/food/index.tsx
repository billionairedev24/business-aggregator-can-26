import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import foodCss from '../../features/food/food.css?url';
import { FoodScreen } from '../../features/food/FoodScreen';
import { pageTitle } from '../../features/shell/messages';

/** Food landing (S-57): kitchens open now, cuisines. `?cuisine=` comes from the home page's tiles (S-46). */
export const Route = createFileRoute('/food/')({
  validateSearch: z.object({ cuisine: z.string().optional().catch(undefined) }),
  head: ({ match }) => ({ meta: [{ title: pageTitle(match.context.locale, 'food') }], links: [{ rel: 'stylesheet', href: foodCss }] }),
  component: Food,
});

function Food() {
  const { cuisine } = Route.useSearch();
  return <FoodScreen cuisine={cuisine} />;
}
