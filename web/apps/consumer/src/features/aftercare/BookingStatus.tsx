import { useQuery } from '@tanstack/react-query';
import { EmptyState, ErrorState, SiteLink, Skeleton, useFormatters } from '@northline/ui';
import { isNotFound } from '@northline/client';
import { bookingQuery } from '../booking/api';
import { signInHref, useViewer } from '../session/api';
import { ReviewPanel, VisitEta } from './Aftercare';
import { BookingActions } from './BookingActions';
import { Crumbs } from '../pages/Crumbs';
import { Scene } from '../art/Scene';
import { useAftercareT } from './messages';

/**
 * Booking detail (redesign § 2.1, AT Shop:898 "Your appointments" item expanded, MA:515 states; mobile gaps part 2):
 * what was booked and its state on a booked → on the way → on site → done line, the provider's minutes away while
 * they're on the way, the review once the job is done; beside it the details (who, when, where, the amount held in
 * escrow), the cancellation policy and help.
 */
export function BookingStatus({ bookingId }: { bookingId: string }) {
  const t = useAftercareT();
  const { date, money } = useFormatters();
  const { user, loading } = useViewer();
  const q = useQuery({ ...bookingQuery(bookingId), enabled: !!user });
  if (loading || (user && q.isPending)) return <div className="nl-page"><Skeleton width={320} height={36} /><Skeleton height={160} style={{ marginTop: 16 }} /></div>;
  if (!user) return <div className="nl-page"><EmptyState action={<SiteLink href={signInHref(`/bookings/${bookingId}`)} className="btn btn-primary">{t('bookingTitle')}</SiteLink>}>{t('bookingTitle')}</EmptyState></div>;
  if (q.isError || !q.data) {
    return <div className="nl-page">{isNotFound(q.error) ? <EmptyState action={<SiteLink href="/account/orders" className="btn btn-primary">{t('bookingTitle')}</SiteLink>}>{t('bookingTitle')}</EmptyState> : <ErrorState message={t('etaError')} onRetry={() => void q.refetch()} />}</div>;
  }
  const b = q.data;
  const who = b.memberFirstName ?? b.providerName;
  const state = b.state ?? 'confirmed';
  const done = state === 'completed' || state === 'signed_off';
  const stopped = state === 'cancelled' || state === 'disputed';
  const at = STEPS.indexOf(STEP_OF[state] ?? 'booked');
  return (
    <div className="nl-page nl-bkd">
      <Crumbs label={t('crumbs')} items={[{ label: t('crumbAccount'), href: '/account' }, { label: t('crumbBookings'), href: '/account/orders?tab=bookings' }, { label: b.ref }]} />
      <div className="nl-bkd-grid">
        <div className="nl-bkd-main">
          <section className="nl-bkd-card" aria-labelledby="bkd-title">
            <span className="nl-bkd-pic" aria-hidden><Scene slot="banner/booking" subject={b.title} fit="contain" sizes="(min-width: 960px) 640px, 100vw" /></span>
            <span className="nl-bkd-kicker">{t('bookingRef', { ref: b.ref })}</span>
            <h1 id="bkd-title" className="nl-after-title">{b.title}</h1>
            <p className="nl-after-muted">{b.providerName} · {date(b.startsAt, 'dateTime')}</p>
            <p className={`nl-bkd-state nl-bkd-state-${stopped ? 'stopped' : done ? 'done' : 'live'}`}>{t(`state_${STATE_KEYS.includes(state) ? state : 'confirmed'}` as 'state_confirmed', { name: who })}</p>
            {!stopped ? (
              <ol className="nl-bkd-steps">
                {STEPS.map((s, i) => (
                  <li key={s} className={`nl-bkd-step nl-bkd-step-${i < at || (done && i === at) ? 'done' : i === at ? 'current' : 'todo'}`}>
                    <span className="nl-bkd-dot" aria-hidden />
                    <span>{t(`step_${s}`)}<span className="nl-sr-only"> ({t(i < at || (done && i === at) ? 'stepDone' : i === at ? 'stepNow' : 'stepNext')})</span></span>
                  </li>
                ))}
              </ol>
            ) : null}
            {done ? <ReviewPanel kind="booking" id={b.bookingId} /> : !stopped ? <VisitEta bookingId={b.bookingId} name={who} /> : null}
          </section>
        </div>
        <aside className="nl-bkd-side" aria-label={t('detailsTitle')}>
          <section className="nl-bkd-card" aria-labelledby="bkd-details">
            <h2 id="bkd-details" className="nl-bkd-h2">{t('detailsTitle')}</h2>
            <dl className="nl-bkd-facts">
              <div><dt>{t('factWho')}</dt><dd><SiteLink href={`/providers/${encodeURIComponent(b.providerSlug)}`}>{b.providerName}</SiteLink></dd></div>
              <div><dt>{t('factWhen')}</dt><dd>{date(b.startsAt, 'dateTime')} – {date(b.endsAt, 'time')}</dd></div>
              {b.addressLine ? <div><dt>{t('factWhere')}</dt><dd>{b.addressLine}</dd></div> : null}
              <div><dt>{t('factPrice')}</dt><dd>{money(b.priceCents)}</dd></div>
              <div><dt>{t('factTax')}</dt><dd>{money(b.taxCents)}</dd></div>
              <div className="nl-bkd-total"><dt>{b.heldCents > 0 ? t('factHeld') : t('factTotal')}</dt><dd>{money(b.heldCents)}</dd></div>
            </dl>
          </section>
          <BookingActions booking={b} />
          <section className="nl-bkd-card" aria-labelledby="bkd-help">
            <h2 id="bkd-help" className="nl-bkd-h2">{t('helpTitle')}</h2>
            {done ? <SiteLink href={`/account/problem/booking/${encodeURIComponent(b.bookingId)}`} className="nl-bkd-link">{t('somethingWrong')}</SiteLink> : null}
            <SiteLink href="/account/orders?tab=bookings" className="nl-bkd-link">{t('allBookings')}</SiteLink>
            <SiteLink href="/help" className="nl-bkd-link">{t('helpLink')}</SiteLink>
          </section>
        </aside>
      </div>
    </div>
  );
}

/** The booking's journey (MA:515 states): booked → on the way → on site → done. */
const STEPS = ['booked', 'en_route', 'on_site', 'done'] as const;
const STEP_OF: Record<string, (typeof STEPS)[number]> = { requested: 'booked', confirmed: 'booked', en_route: 'en_route', on_site: 'on_site', completed: 'done', signed_off: 'done' };
const STATE_KEYS = ['requested', 'confirmed', 'en_route', 'on_site', 'completed', 'signed_off', 'disputed', 'cancelled'];
