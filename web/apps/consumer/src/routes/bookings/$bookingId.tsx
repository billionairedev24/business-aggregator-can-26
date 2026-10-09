import { createFileRoute } from '@tanstack/react-router';
import aftercareCss from '../../features/aftercare/aftercare.css?url';
import { BookingStatus } from '../../features/aftercare/BookingStatus';
import { pageTitle } from '../../features/shell/messages';

/** A booking after it's made (mobile gaps part 2): the live ETA on the day, then the review. Personal: browser only. */
export const Route = createFileRoute('/bookings/$bookingId')({
  head: ({ match }) => ({
    meta: [{ title: pageTitle(match.context.locale, 'booking') }, { name: 'robots', content: 'noindex' }],
    links: [{ rel: 'stylesheet', href: aftercareCss }],
  }),
  component: BookingRoute,
});

function BookingRoute() {
  const { bookingId } = Route.useParams();
  return <BookingStatus bookingId={bookingId} />;
}
