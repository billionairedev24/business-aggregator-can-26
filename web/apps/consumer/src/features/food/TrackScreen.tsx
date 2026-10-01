import { Link } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { isNotFound } from '@northline/client';
import { EmptyState, ErrorState, Skeleton, SiteLink, useFormatters } from '@northline/ui';
import { signInHref, useViewer } from '../session/api';
import { Tracking, trackingQuery, trackingStreamUrl } from './api';
import { CourierStatus } from '../tracking/CourierStatus';
import { useTrackingStream } from '../tracking/courier';
import { useFoodT } from './messages';

type T = ReturnType<typeof useFoodT>;
const ORDER: Record<Tracking['stage'], number> = { paid: 0, cooking: 1, ready: 2, on_the_way: 3, delivered: 4, refunded: 4, cancelled: 4 };
/** Where the dot sits on the design's route illustration: kitchen → corner → door. */
const DOT: Record<number, [number, number]> = { 0: [70, 90], 1: [70, 90], 2: [70, 90], 3: [200, 145], 4: [330, 200] };

/**
 * Food tracking (design 06 `foodTrack`, S-57): the order's stage as the kitchen display moves it (accepted → cooking,
 * ready, handed off → on the way / picked up, delivered), the steps, the ETA, and the route illustration. Live over
 * the order's event stream (S-88: kitchen steps and courier moves, with the courier's ETA and the drop-off PIN); polls
 * every 15 s while the stream is down.
 */
export function TrackScreen({ orderId }: { orderId: string }) {
  const t = useFoodT();
  const { user, loading } = useViewer();
  const q = useQuery({ ...trackingQuery(orderId), enabled: !!user, retry: false });
  useTrackingStream(trackingStreamUrl(orderId), 'food', Tracking, trackingQuery(orderId).queryKey, !!user && q.isSuccess);
  if (loading || (user && q.isPending)) return <div className="nl-page"><Skeleton width={120} height={20} /><Skeleton width={360} height={36} style={{ marginTop: 12 }} /><Skeleton height={180} style={{ marginTop: 20 }} /></div>;
  if (!user) return <div className="nl-page"><EmptyState action={<SiteLink href={signInHref(`/food/orders/${orderId}`)} className="btn btn-primary">{t('signIn')}</SiteLink>}>{t('trackMissing')}</EmptyState></div>;
  if (q.isError) {
    return <div className="nl-page">{isNotFound(q.error)
      ? <EmptyState action={<Link to="/food" className="btn btn-primary">{t('browseFood')}</Link>}>{t('trackMissing')}</EmptyState>
      : <ErrorState message={t('trackError')} onRetry={() => void q.refetch()} />}</div>;
  }
  return <Track t={t} o={q.data!} />;
}

function Track({ t, o }: { t: T; o: Tracking }) {
  const { money, date } = useFormatters();
  const pickup = o.mode === 'pickup';
  const at = ORDER[o.stage];
  const title = o.stage === 'delivered' && pickup ? t('st_deliveredPickupTitle')
    : o.stage === 'ready' && pickup ? t('st_readyPickupTitle', { kitchen: o.kitchen })
    : t(`st_${o.stage}Title`, { kitchen: o.kitchen });
  const arriving = o.stage === 'delivered' && o.deliveredAt ? t('arrived', { time: date(o.deliveredAt, 'time') })
    : o.eta ? t(pickup ? 'ready' : 'arriving', { time: date(o.eta, 'time') }) : '';
  const steps = pickup
    ? [t('step1'), o.prepMin ? t('step2', { min: o.prepMin }) : t('step2Plain'), t('pstep3'), t('pstep4')]
    : [t('step1'), o.prepMin ? t('step2', { min: o.prepMin }) : t('step2Plain'), t('step3'), t('step4'), t('step5')];
  const reached = pickup ? Math.min(at, 3) : at;
  const done = (i: number) => i <= reached;
  const [cx, cy] = DOT[pickup && at >= 3 ? 4 : at] ?? DOT[0]!;
  return (
    <div className="nl-track">
      <div>
        <span className="tag tag-accent-2">{t(`st_${o.stage}`)}</span>
        <h1>{title}</h1>
        <p className="nl-fco-muted">{t('summary', { ref: o.ref, kitchen: o.kitchen, total: money(o.totalCents), arriving })}</p>
        <ol className="nl-track-steps">
          {steps.map((s, i) => <li key={i} data-done={done(i) || undefined} aria-current={done(i) && !done(i + 1) ? 'step' : undefined}><span aria-hidden className="nl-track-dot" />{s}</li>)}
        </ol>
        {pickup ? null : <CourierStatus courier={o.courier} />}
        <p className="nl-fco-muted">{t('liveNote')}</p>
        <div className="nl-track-actions">
          {o.stage === 'delivered' || o.stage === 'on_the_way'
            ? <Link to="/account/problem/$kind/$id" params={{ kind: 'food', id: o.orderId }} className="btn btn-secondary">{t('somethingWrong')}</Link>
            : null}
          <Link to="/account/orders" className="btn btn-ghost">{t('orders')}</Link>
        </div>
      </div>
      <div className="nl-track-map halftone" aria-hidden>
        <svg viewBox="0 0 400 300">
          <path d="M70 90 L200 90 L200 200 L330 200" fill="none" stroke="var(--color-accent-2)" strokeWidth="4" strokeDasharray="8 6" />
          <circle cx="70" cy="90" r="9" fill="var(--color-neutral-700)" />
          <circle cx={cx} cy={cy} r="11" fill="var(--color-accent-2)" />
          <circle cx="330" cy="200" r="9" fill="var(--color-bg)" stroke="var(--color-text)" strokeWidth="3" />
        </svg>
        <div className="nl-track-caption">{pickup ? o.kitchen : t('courier')}</div>
      </div>
    </div>
  );
}
