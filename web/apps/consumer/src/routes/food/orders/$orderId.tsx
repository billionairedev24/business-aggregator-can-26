import { createFileRoute } from '@tanstack/react-router';
import foodCss from '../../../features/food/food.css?url';
import { TrackScreen } from '../../../features/food/TrackScreen';
import aftercareCss from '../../../features/aftercare/aftercare.css?url';
import { pageTitle } from '../../../features/shell/messages';

/** Food order tracking (S-57): design 06 `foodTrack`. */
export const Route = createFileRoute('/food/orders/$orderId')({
  head: ({ match }) => ({ meta: [{ title: pageTitle(match.context.locale, 'foodTrack') }, { name: 'robots', content: 'noindex' }], links: [{ rel: 'stylesheet', href: foodCss }, { rel: 'stylesheet', href: aftercareCss }] }),
  component: Track,
});

function Track() {
  const { orderId } = Route.useParams();
  return <TrackScreen orderId={orderId} />;
}
