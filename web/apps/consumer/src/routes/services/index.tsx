import { createFileRoute } from '@tanstack/react-router';
import servicesCss from '../../features/services/services.css?url';
import { servicesLandingQuery } from '../../features/services/api';
import { ServicesLanding, ServicesLandingSkeleton } from '../../features/services/ServicesLanding';
import { pageTitle } from '../../features/shell/messages';
import { seo } from '../../lib/seo';

/** Services landing (S-53): design 06 `services` — every group and category; server-rendered. */
export const Route = createFileRoute('/services/')({
  loader: ({ context }) => context.queryClient.ensureQueryData(servicesLandingQuery(context.locale)),
  head: ({ match }) => {
    const tags = seo(match.context, {
      title: pageTitle(match.context.locale, 'services'), path: '/services',
      description: match.context.locale === 'fr'
        ? 'Prestataires vérifiés — réservation instantanée, devis ou consultation, paiement en fiducie.'
        : 'Verified service providers — instant book, quotes or consultations, every job paid into escrow.',
    });
    return { ...tags, links: [...tags.links, { rel: 'stylesheet', href: servicesCss }] };
  },
  pendingComponent: ServicesLandingSkeleton,
  component: ServicesLanding,
});
