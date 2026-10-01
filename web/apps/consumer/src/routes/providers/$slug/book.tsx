import { createFileRoute, notFound } from '@tanstack/react-router';
import { z } from 'zod';
import { isNotFound } from '@northline/client';
import bookingCss from '../../../features/booking/booking.css?url';
import { BookingSkeleton, BookingWizard, type Step } from '../../../features/booking/BookingWizard';
import { providerQuery, storefrontQuery } from '../../../features/provider/api';
import { pageTitle } from '../../../features/shell/messages';

/** `?step=` is the wizard step (back/forward work), `?service=` preselects a service, `?booking=` the confirmed one. */
export const BookParams = z.object({
  step: z.enum(['details', 'location', 'schedule', 'pay', 'done']).optional().catch(undefined),
  service: z.string().max(64).optional().catch(undefined),
  booking: z.string().max(64).optional().catch(undefined),
});

/**
 * Booking wizard (S-55): design 06 `book` — job details → location & access → schedule → payment → confirmed. The
 * provider and its services are server-rendered; the calendar, the hold and the payment are the signed-in customer's.
 */
export const Route = createFileRoute('/providers/$slug/book')({
  validateSearch: BookParams,
  loader: async ({ context, params }) => {
    try {
      const [, facts] = await Promise.all([
        context.queryClient.ensureQueryData(storefrontQuery(params.slug)),
        context.queryClient.ensureQueryData(providerQuery(params.slug, context.locale)),
      ]);
      return { name: facts.name };
    } catch (e) {
      if (isNotFound(e)) throw notFound();
      throw e;
    }
  },
  head: ({ match, loaderData }) => ({
    meta: [
      { title: loaderData ? `${pageTitle(match.context.locale, 'book')} · ${loaderData.name} · Northline` : pageTitle(match.context.locale, 'book') },
      { name: 'robots', content: 'noindex' },
    ],
    links: [{ rel: 'stylesheet', href: bookingCss }],
  }),
  pendingComponent: BookingSkeleton,
  component: BookRoute,
});

function BookRoute() {
  const { slug } = Route.useParams();
  const search = Route.useSearch();
  const navigate = Route.useNavigate();
  const onStep = (step: Step, extra?: { booking?: string }) =>
    void navigate({ search: prev => ({ ...prev, step, booking: extra?.booking ?? (step === 'done' ? prev.booking : undefined) }) });
  return <BookingWizard slug={slug} step={search.step ?? 'details'} serviceId={search.service} bookingId={search.booking} onStep={onStep} />;
}
