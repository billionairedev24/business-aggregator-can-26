import { useState } from 'react';
import { EmptyState, ErrorState, SiteLink, Skeleton, useFormatters, useLocale } from '@northline/ui';
import { useZone } from '../location/regions';
import { isNotFound } from '@northline/client';
import { signInHref, useViewer } from '../session/api';
import { clock, runWhen, weekday, windowRange } from '../shop/format';
import { useConfirmDelivery, useOrder, useOrderStream, useProofPhoto, type OrderTracking } from './api';
import { useOrderT } from './messages';
import { CourierStatus } from '../tracking/CourierStatus';
import { Crumbs } from '../pages/Crumbs';
import { Scene } from '../art/Scene';
import { CourierTip, ReviewPanel } from '../aftercare/Aftercare';
import { CancelOrder, Cancellations, FailedDelivery, Returns } from './AfterSale';
import { useAfterSaleT } from './afterSaleMessages';

/**
 * Order detail and tracking (S-52; redesign § 2.1, AT Shop:688 / OrderDetailView): the head row (placed, total, shops,
 * order number), the status card ("Order placed. Arriving tonight 6–9 pm.", the four-step timeline, the courier live,
 * the proof and "Got everything"), the shops, and a side column with the delivery run, the payment summary and help —
 * kept live by the order's event stream. Only for the customer who placed it.
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
  const ta = useAfterSaleT();
  const { locale } = useLocale();
  const zone = useZone();
  const { money } = useFormatters();
  const d = order.delivery;
  const when = d.startsAt && d.endsAt && d.day
    ? t(`when_${runWhen({ day: d.day, startsAt: d.startsAt }, zone)}`, { range: windowRange(d.startsAt, d.endsAt, locale, zone), weekday: weekday(d.startsAt, locale, zone) })
    : d.etaAt ? t('whenDirect', { time: clock(d.etaAt, locale, zone) }) : t('whenSoon');
  // "… 6–9 p.m." already ends the sentence
  const title = (order.state === 'cancelled' ? t('cancelled')
    : order.state === 'refunded' || order.state === 'returned' ? t('refunded')
    : order.state === 'delivery_failed' ? ta('failedTitle')
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
  const done = order.state === 'cancelled' || order.state === 'refunded' || order.state === 'returned';
  const confirm = useConfirmDelivery(order.orderId);
  const paysOn = order.paysShopsAt ? new Intl.DateTimeFormat(locale, { weekday: 'long', month: 'long', day: 'numeric', timeZone: zone }).format(new Date(order.paysShopsAt)) : null;

  const placedOn = new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { month: 'long', day: 'numeric', year: 'numeric', timeZone: zone }).format(new Date(order.placedAt));
  const runNote = d.kind === 'pooled' && d.startsAt
    ? <>{d.runLabel ? t('run', { label: d.runLabel, time: clock(d.startsAt, locale, zone) }) : t('runNoLabel', { time: clock(d.startsAt, locale, zone) })}{d.households > 1 ? ` · ${t('households', { count: d.households })}` : ''}</>
    : d.etaAt ? t('direct', { time: clock(d.etaAt, locale, zone) }) : null;

  return (
    <div className="nl-page order-page">
      <Crumbs label={t('crumbs')} items={[{ label: t('crumbAccount'), href: '/account' }, { label: t('crumbOrders'), href: '/account/orders' }, { label: ref }]} />
      <div className="order-grid">
        <div className="order-main">
          <dl className="order-head">
            <div><dt>{t('headPlaced')}</dt><dd>{placedOn}</dd></div>
            <div><dt>{t('headTotal')}</dt><dd>{total}</dd></div>
            <div><dt>{t('headShops')}</dt><dd>{shops}</dd></div>
            <div className="order-head-ref"><dt>{t('headRef')}</dt><dd>{ref}</dd></div>
          </dl>
          <section className="order-card order-status" aria-labelledby="order-title">
            <h1 id="order-title" className="order-title">{title}</h1>
            <p className="order-sub">{sub}</p>
            <Cancellations order={order} />
            {!done ? (
              <ol className="order-steps" aria-live="polite">
                {order.steps.map(s => (
                  <li key={s.key} className={`order-step order-step-${s.state}`}>
                    <span className="order-dot" aria-hidden />
                    <span className="order-step-label">{s.key === 'packing' ? t('step_packing', { packed, shops }) : t(`step_${s.key}`)}<span className="nl-sr-only"> ({t(`stepState_${s.state}`)})</span></span>
                  </li>
                ))}
              </ol>
            ) : null}
            <CourierStatus courier={order.courier} />
            {order.deliveryProof ? <p className="order-note">{t(`proof_${order.deliveryProof}`)}</p> : null}
            <DoorPhoto orderId={order.orderId} enabled={order.deliveryProof === 'photo'} />
            {order.confirmedAt ? <p className="order-note">{t('confirmed')}</p> : paysOn ? <p className="order-note">{t('paysShops', { date: paysOn })}</p> : null}
            {confirm.isError ? <p className="order-error" role="alert">{t('confirmError')}</p> : null}
            <div className="order-actions">
              {order.canConfirm
                ? <button type="button" className="btn btn-primary" disabled={confirm.isPending} onClick={() => confirm.mutate()}>{confirm.isPending ? t('confirming') : t('confirm')}</button>
                : null}
              {order.state === 'delivered'
                ? <SiteLink href={`/account/problem/order/${encodeURIComponent(order.orderId)}`} className="btn btn-secondary">{t('somethingWrong')}</SiteLink>
                : null}
            </div>
          </section>
          <FailedDelivery order={order} />
          <CancelOrder order={order} />
          <section className="order-card" aria-labelledby="order-shops">
            <h2 id="order-shops" className="order-h2">{t('shopsTitle')}</h2>
            <ul className="order-shops">
              {order.shops.map(s => (
                <li key={s.merchantId} className="order-shop">
                  <span className="order-shop-pic" aria-hidden><Scene slot="banner/shop" subject={s.name} crop="square" sizes="64px" /></span>
                  <span className="order-shop-name">{s.name}</span>
                  <span className="order-shop-meta">{t('shopItems', { count: s.items })}</span>
                  {s.cancelled ? <span className="tag tag-neutral">{ta('shopCancelled')}</span>
                    : !done ? <span className={`tag ${s.packed ? 'tag-success' : 'tag-neutral'}`}>{s.packed ? t('shopPacked') : t('shopPacking')}</span> : null}
                </li>
              ))}
            </ul>
          </section>
          <Returns order={order} />
          {order.state === 'delivered' || order.state === 'confirmed' ? (
            <>
              <CourierTip orderId={order.orderId} />
              <ReviewPanel kind="order" id={order.orderId} />
            </>
          ) : null}
        </div>
        <aside className="order-side" aria-label={t('summary')}>
          <section className="order-card" aria-labelledby="order-delivery">
            <h2 id="order-delivery" className="order-h2">{t('deliveryTitle')}</h2>
            {runNote ? <p className="order-run">{runNote}</p> : <p className="order-run">{t('whenSoon')}</p>}
          </section>
          <section className="order-card" aria-labelledby="order-pay">
            <h2 id="order-pay" className="order-h2">{t('paymentTitle')}</h2>
            <dl className="order-totals">
              <div><dt>{t('items')}</dt><dd>{money(order.subtotalCents)}</dd></div>
              <div><dt>{t('delivery')}</dt><dd>{money(order.deliveryFeeCents)}</dd></div>
              <div><dt>{t('tax')}</dt><dd>{money(order.taxCents)}</dd></div>
              <div className="order-total"><dt>{t('total')}</dt><dd>{total}</dd></div>
            </dl>
          </section>
          <section className="order-card" aria-labelledby="order-help">
            <h2 id="order-help" className="order-h2">{t('helpTitle')}</h2>
            <SiteLink href="/account/orders" className="order-link">{t('viewOrders')}</SiteLink>
            <SiteLink href="/help" className="order-link">{t('helpLink')}</SiteLink>
            <SiteLink href="/" className="order-link">{t('backHome')}</SiteLink>
          </section>
        </aside>
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
        <Skeleton height={240} radius={8} />
      </div>
    </div>
  );
}

/** The courier's photo at the door, when the api has one to show (mobile gaps part 1); nothing otherwise. */
function DoorPhoto({ orderId, enabled }: { orderId: string; enabled: boolean }) {
  const t = useOrderT();
  const photo = useProofPhoto(orderId, enabled);
  const [broken, setBroken] = useState(false);
  if (!photo.data || broken) return null;
  return (
    <figure className="order-proof">
      <img src={photo.data.url} alt={t('proofAlt')} loading="lazy" onError={() => setBroken(true)} />
    </figure>
  );
}
