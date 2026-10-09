import { useQuery } from '@tanstack/react-query';
import { EmptyState, ErrorState, SiteLink, Skeleton, useFormatters } from '@northline/ui';
import { isNotFound } from '@northline/client';
import { bookingQuery } from '../booking/api';
import { signInHref, useViewer } from '../session/api';
import { ReviewPanel, VisitEta } from './Aftercare';
import { useAftercareT } from './messages';

/**
 * A booking after it's made (mobile gaps part 2; the app's C9 ETA and C11 review on the web): what was booked, the
 * provider's minutes away while they're on the way, and the review once the job is done.
 */
export function BookingStatus({ bookingId }: { bookingId: string }) {
  const t = useAftercareT();
  const { date } = useFormatters();
  const { user, loading } = useViewer();
  const q = useQuery({ ...bookingQuery(bookingId), enabled: !!user });
  if (loading || (user && q.isPending)) return <div className="nl-page"><Skeleton width={320} height={36} /><Skeleton height={160} style={{ marginTop: 16 }} /></div>;
  if (!user) return <div className="nl-page"><EmptyState action={<SiteLink href={signInHref(`/bookings/${bookingId}`)} className="btn btn-primary">{t('bookingTitle')}</SiteLink>}>{t('bookingTitle')}</EmptyState></div>;
  if (q.isError || !q.data) {
    return <div className="nl-page">{isNotFound(q.error) ? <EmptyState action={<SiteLink href="/account/orders" className="btn btn-primary">{t('bookingTitle')}</SiteLink>}>{t('bookingTitle')}</EmptyState> : <ErrorState message={t('etaError')} onRetry={() => void q.refetch()} />}</div>;
  }
  const b = q.data;
  const who = b.memberFirstName ?? b.providerName;
  const done = b.state === 'completed' || b.state === 'signed_off';
  return (
    <div className="nl-page">
      <h1 className="nl-after-title">{b.title}</h1>
      <p className="nl-after-muted">{t('bookingRef', { ref: b.ref })} · {b.providerName} · {date(b.startsAt, 'dateTime')}</p>
      {done ? <ReviewPanel kind="booking" id={b.bookingId} /> : <VisitEta bookingId={b.bookingId} name={who} />}
    </div>
  );
}
