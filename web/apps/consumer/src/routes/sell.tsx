import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import sellCss from '../features/sell/sell.css?url';
import { sellText } from '../features/sell/messages';
import { SellScreen } from '../features/sell/SellScreen';

/**
 * Sell or offer a service (S-61): the entry from the consumer site into Studio onboarding (07a–07d), signed in or not.
 * `?type=` (the footer's Sell / Offer / Run a kitchen) marks one of the three. Server-rendered and indexable.
 */
export const SellParams = z.object({ type: z.enum(['seller', 'provider', 'kitchen']).optional().catch(undefined) });
export const Route = createFileRoute('/sell')({
  validateSearch: SellParams,
  head: ({ match }) => {
    const t = sellText(match.context.locale);
    return {
      meta: [{ title: t('title') }, { name: 'description', content: t('description') }],
      links: [{ rel: 'stylesheet', href: sellCss }],
    };
  },
  component: SellRoute,
});

function SellRoute() {
  const { type } = Route.useSearch();
  return <SellScreen type={type} />;
}
