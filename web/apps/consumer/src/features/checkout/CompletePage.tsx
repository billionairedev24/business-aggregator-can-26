import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { CheckCircle } from '@phosphor-icons/react';
import { EmptyState, SiteLink, Skeleton, useFormatters, useLocale } from '@northline/ui';
import { useZone } from '../location/regions';
import { useViewer } from '../session/api';
import { cartQuery } from '../cart/api';
import { useOrder } from '../orders/api';
import { clock, runWhen, weekday, windowRange } from '../shop/format';
import { useOrderT } from '../orders/messages';
import { useCheckoutT } from './messages';
import { Art } from '../art/Art';

/**
 * Order placed (redesign § 2.1 `checkout/complete`, AT CheckoutComplete): the check, "Thank you — your order is
 * placed", the reference and total, when it arrives, what happens next, and Track your order. The cart is read again
 * (the api removed the ordered lines when the order was placed), so the header's count is right.
 */
export function CompletePage({ orderId, refCode }: { orderId?: string; refCode?: string }) {
  const t = useCheckoutT();
  const o = useOrderT();
  const { locale } = useLocale();
  const zone = useZone();
  const { money } = useFormatters();
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
  return (
    <div className="nl-page co-done">
      <section className="co-done-card" aria-labelledby="co-done-title">
        <span className="co-done-pic" aria-hidden><Art subject="courier" tone="mint" fit="contain" /></span>
        <CheckCircle size={56} weight="fill" className="co-done-icon" aria-hidden />
        <h1 id="co-done-title" className="co-title">{t('doneTitle')}</h1>
        <p className="co-done-sub">{order.data ? t('doneSub', { ref, total: money(order.data.totalCents) }) : t('doneSubNoTotal', { ref })}</p>
        {loading || order.isPending ? <Skeleton width={260} height={16} /> : when ? <p className="co-done-when">{t('doneWhen', { when: when.replace(/\.$/, '') })}</p> : null}
        {order.data ? <p className="co-muted">{t('doneShops', { count: order.data.shops.length })}</p> : null}
        <p className="co-muted">{t('doneEscrow')}</p>
        <div className="co-actions">
          <SiteLink href={`/orders/${encodeURIComponent(orderId)}`} className="btn btn-primary">{t('track')}</SiteLink>
          <SiteLink href="/shop" className="btn btn-secondary">{t('browse')}</SiteLink>
          <SiteLink href="/account/orders" className="btn btn-ghost">{t('ordersLink')}</SiteLink>
        </div>
      </section>
    </div>
  );
}
