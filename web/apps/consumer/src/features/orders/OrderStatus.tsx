import { EmptyState, ErrorState, SiteLink, Skeleton, useFormatters, useLocale } from '@northline/ui';
import { useZone } from '../location/regions';
import { isNotFound } from '@northline/client';
import { signInHref, useViewer } from '../session/api';
import { clock, runWhen, weekday, windowRange } from '../shop/format';
import { useOrder, useOrderStream, type OrderTracking } from './api';
import { useOrderT } from './messages';

/**
 * Order confirmed and tracking (S-52, design 06 `confirmed`): the check mark, "Order placed. Arriving tonight 6–9
 * pm.", the reference, total and shops, the four-step timeline and the run panel — kept live by the order's event
 * stream. Only for the customer who placed it.
 */
export function OrderStatus({ orderId }: { orderId: string }) {
  const t = useOrderT();
  const { user, loading } = useViewer();
  const order = useOrder(orderId, !!user);
  useOrderStream(orderId, !!user && order.isSuccess);
  if (loading || (user && order.isPending)) return <OrderSkeleton />;
  if (!user) {
    return (
      <div className="nl-page order-page">
        <EmptyState action={<SiteLink href={signInHref(`/orders/${orderId}`)} className="btn btn-primary">{t('signInAction')}</SiteLink>}>{t('signIn')}</EmptyState>
      </div>
    );
  }
  if (order.isError) {
    return (
      <div className="nl-page order-page">
        {isNotFound(order.error)
          ? <EmptyState action={<SiteLink href="/account/orders" className="btn btn-primary">{t('viewOrders')}</SiteLink>}>{t('notFound')}</EmptyState>
          : <ErrorState message={t('loadError')} onRetry={() => void order.refetch()} />}
      </div>
    );
  }
  return <OrderView order={order.data!} />;
}

function OrderView({ order }: { order: OrderTracking }) {
  const t = useOrderT();
  const { locale } = useLocale();
  const zone = useZone();
  const { money } = useFormatters();
  const d = order.delivery;
  const when = d.startsAt && d.endsAt && d.day
    ? t(`when_${runWhen({ day: d.day, startsAt: d.startsAt }, zone)}`, { range: windowRange(d.startsAt, d.endsAt, locale, zone), weekday: weekday(d.startsAt, locale, zone) })
    : d.etaAt ? t('whenDirect', { time: clock(d.etaAt, locale, zone) }) : t('whenSoon');
  // "… 6–9 p.m." already ends the sentence
  const title = (order.state === 'cancelled' ? t('cancelled')
    : order.state === 'refunded' ? t('refunded')
      : order.state === 'delivered' || order.state === 'confirmed' ? t('delivered')
        : order.state === 'picked_up' ? t('onTheWay', { when })
          : t('placed', { when })).replace(/\.\.$/, '.');
  const shops = order.shops.length;
  const packed = order.shops.filter(s => s.packed).length;
  const ref = order.ref ?? '';
  const total = money(order.totalCents);
  const sub = ['placed', 'accepted', 'packing'].includes(order.state)
    ? (packed === 0 ? t('sub', { ref, total, shops }) : t('subPacked', { ref, total, packed, shops }))
    : t('subDone', { ref, total });
  const done = order.state === 'cancelled' || order.state === 'refunded';

  return (
    <div className="nl-page order-page">
      <div className="order-grid">
        <div>
          <svg width="64" height="64" viewBox="0 0 72 72" aria-hidden className="order-check">
            <circle cx="36" cy="36" r="34" className="order-check-ring" />
            <path d="M22 37 L32 47 L51 27" className="order-check-mark" />
          </svg>
          <h1 className="order-title">{title}</h1>
          <p className="order-sub">{sub}</p>
          {!done ? (
            <ol className="order-steps" aria-live="polite">
              {order.steps.map(s => (
                <li key={s.key} className={`order-step order-step-${s.state}`}>
                  <span className="order-dot" aria-hidden />
                  <span>{s.key === 'packing' ? t('step_packing', { packed, shops }) : t(`step_${s.key}`)}<span className="nl-sr-only"> ({t(`stepState_${s.state}`)})</span></span>
                </li>
              ))}
            </ol>
          ) : null}
          <div className="order-actions">
            <SiteLink href="/account/orders" className="btn btn-primary">{t('viewOrders')}</SiteLink>
            <SiteLink href="/" className="btn btn-ghost">{t('backHome')}</SiteLink>
          </div>
        </div>
        <figure className="order-map halftone" aria-label={t('map')}>
          <svg viewBox="0 0 400 300" aria-hidden className="order-map-svg">
            <path d="M40 260 L120 260 L120 180 L250 180 L250 100 L330 100" className="order-route" />
            <circle cx="40" cy="260" r="8" className="order-stop" />
            <circle cx="120" cy="180" r="8" className="order-stop" />
            <circle cx="330" cy="100" r="9" className="order-home" />
          </svg>
          <figcaption className="order-map-note">
            {d.kind === 'pooled' && d.startsAt
              ? <>{d.runLabel ? t('run', { label: d.runLabel, time: clock(d.startsAt, locale, zone) }) : t('runNoLabel', { time: clock(d.startsAt, locale, zone) })}{d.households > 1 ? ` · ${t('households', { count: d.households })}` : ''}</>
              : d.etaAt ? t('direct', { time: clock(d.etaAt, locale, zone) }) : null}
          </figcaption>
        </figure>
      </div>
    </div>
  );
}

export function OrderSkeleton() {
  const t = useOrderT();
  return (
    <div className="nl-page order-page" aria-busy="true">
      <span className="nl-sr-only">{t('loading')}</span>
      <div className="order-grid">
        <div>
          <Skeleton width={64} height={64} radius="50%" />
          <Skeleton width="80%" height={36} style={{ marginTop: 16 }} />
          <Skeleton width="60%" height={14} style={{ marginTop: 10 }} />
          {Array.from({ length: 4 }, (_, i) => <Skeleton key={i} width="70%" height={20} style={{ marginTop: 12 }} />)}
        </div>
        <Skeleton height={0} radius={12} style={{ aspectRatio: '4 / 3', height: 'auto' }} />
      </div>
    </div>
  );
}
