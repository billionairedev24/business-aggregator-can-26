import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { CheckCircle } from '@phosphor-icons/react';
import { EmptyState, Price, SiteLink, Skeleton, useFormatters, useLocale } from '@northline/ui';
import { useZone } from '../location/regions';
import { useViewer } from '../session/api';
import { cartQuery } from '../cart/api';
import { useOrder } from '../orders/api';
import { clock, runWhen, weekday, windowRange } from '../shop/format';
import { useOrderT } from '../orders/messages';
import { useCheckoutT } from './messages';

/**
 * Order placed (E-15, in the checkout's system): a bordered panel with the success check, "Thank you — your order is
 * placed", the reference and total, when it arrives, what happens next (three numbered steps) and Track your order,
 * beside the order's summary box (items, delivery, tax, total). The cart is read again (the api removed the ordered
 * lines when the order was placed), so the header's count is right.
 */
export function CompletePage({ orderId, refCode }: { orderId?: string; refCode?: string }) {
  const t = useCheckoutT();
  const o = useOrderT();
  const { locale } = useLocale();
  const zone = useZone();
  const { price } = useFormatters();
  const qc = useQueryClient();
  const { user, loading } = useViewer();
  const order = useOrder(orderId ?? '', !!user && !!orderId);
  useEffect(() => { void qc.invalidateQueries({ queryKey: cartQuery.queryKey }); }, [qc]);
  if (!orderId) {
    return <div className="nl-page co-done"><h1 className="co-title">{t('doneMissing')}</h1><EmptyState action={<SiteLink href="/account/orders" className="btn btn-primary">{t('viewOrders')}</SiteLink>} /></div>;
  }
  const d = order.data?.delivery;
  const when = d && d.startsAt && d.endsAt && d.day
    ? o(`when_${runWhen({ day: d.day, startsAt: d.startsAt }, zone)}`, { range: windowRange(d.startsAt, d.endsAt, locale, zone), weekday: weekday(d.startsAt, locale, zone) })
    : d?.etaAt ? o('whenDirect', { time: clock(d.etaAt, locale, zone) }) : null;
  const ref = order.data?.ref ?? refCode ?? '';
  const steps = ['doneStepPacked', 'doneStepRun', 'doneStepDoor'] as const;
  return (
    <div className="nl-page co-done">
      <div className="co-done-grid">
        <section className="co-done-card" aria-labelledby="co-done-title">
          <div className="co-done-head">
            <CheckCircle size={32} weight="fill" className="co-done-icon" aria-hidden />
            <div className="co-done-text">
              <h1 id="co-done-title" className="co-title">{t('doneTitle')}</h1>
              <p className="co-done-sub">{order.data ? t('doneSub', { ref, total: price(order.data.totalCents) }) : t('doneSubNoTotal', { ref })}</p>
              {loading || order.isPending ? <Skeleton width={260} height={16} /> : when ? <p className="co-done-when">{t('doneWhen', { when: when.replace(/\.$/, '') })}</p> : null}
            </div>
          </div>
          <h2 className="co-done-h2">{t('doneNext')}</h2>
          <ol className="co-done-steps">
            {steps.map((s, i) => <li key={s}><span className="co-num" aria-hidden>{i + 1}</span>{t(s)}</li>)}
          </ol>
          {order.data ? <p className="co-muted">{t('doneShops', { count: order.data.shops.length })}</p> : null}
          <p className="co-muted">{t('doneEscrow')}</p>
          <div className="co-done-actions">
            <SiteLink href={`/orders/${encodeURIComponent(orderId)}`} className="btn btn-primary">{t('track')}</SiteLink>
            <SiteLink href="/shop" className="btn btn-secondary">{t('browse')}</SiteLink>
            <SiteLink href="/account/orders" className="btn btn-tertiary">{t('ordersLink')}</SiteLink>
          </div>
        </section>
        {order.data ? (
          <aside className="co-done-summary" aria-label={t('summary')}>
            <h2 className="co-done-h2">{t('summary')}</h2>
            <dl className="co-totals">
              <div className="co-row"><dt>{o('items')}</dt><dd>{price(order.data.subtotalCents)}</dd></div>
              <div className="co-row"><dt>{o('delivery')}</dt><dd>{price(order.data.deliveryFeeCents)}</dd></div>
              <div className="co-row"><dt>{o('tax')}</dt><dd>{price(order.data.taxCents)}</dd></div>
            </dl>
            <div className="co-total"><span>{t('orderTotal')}</span><Price cents={order.data.totalCents} size="xl" /></div>
            <ul className="co-done-shops">
              {order.data.shops.map(s => <li key={s.merchantId}><span>{s.name}</span><span className="co-muted">{o('shopItems', { count: s.items })}</span></li>)}
            </ul>
          </aside>
        ) : null}
      </div>
    </div>
  );
}
