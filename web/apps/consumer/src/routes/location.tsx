import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import locationCss from '../features/location/location.css?url';
import { marketsQuery } from '../features/location/api';
import { LocationScreen } from '../features/location/LocationScreen';
import { pageTitle } from '../features/shell/messages';

/** Location (S-47): province, Google Places address, market/zone; saves with useDeliveryLocation().save(). */
export const Route = createFileRoute('/location')({
  validateSearch: z.object({ next: z.string().optional().catch(undefined) }),
  // Provinces and markets are public and the same for everyone: rendered on the server when the api answers.
  loader: ({ context }) => context.queryClient.prefetchQuery(marketsQuery),
  head: ({ match }) => ({ meta: [{ title: pageTitle(match.context.locale, 'location') }, { name: 'robots', content: 'noindex' }], links: [{ rel: 'stylesheet', href: locationCss }] }),
  component: Location,
});

function Location() {
  const { next } = Route.useSearch();
  return <LocationScreen next={next} />;
}
