import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from '@tanstack/react-router';
import {
  ActionPanel, ActionPanelItem, Alert, ErrorState, FactList, PageSkeleton, RecordHeader, RecordLayout, RecordTabs, StatusTag, Tag, useFormatters, useLocale,
} from '@northline/ui';
import { ApiError } from '../../lib/http';
import { useGrant } from '../shell/grant';
import { afterSaleQuery, deliveryQuery, monitorQuery, type Row } from './api';
import { AfterSalePanel, CLOSED, StaffCancelDialog } from './AfterSalePanel';
import { useOrdersT, type OrdersKey } from './messages';
import { issueText, STATUS_TONE } from './OrdersMonitor';
import './orders.css';

/**
 * An order or a booking (S-177; the orders sheet of S-81/S-86/S-151 as a page): the head with its status and key facts,
 * what needs attention, the delivery and the cancellations and returns as tabs, and the action panel (cancel, role
 * `support`). Found by its reference through the monitor (`?view=all&q=<ref>`): there is no single-order read.
 */
export function OrderPage() {
  const t = useOrdersT();
  const { orderRef } = useParams({ strict: false }) as { orderRef: string };
  const query = useQuery(monitorQuery({ view: 'all', q: orderRef }));
  const back = <div className="bo-rec-back"><Link to="/orders">{t('back')}</Link></div>;
  if (query.isPending) return <div className="bo-page">{back}<PageSkeleton kpis={0} rows={6} /></div>;
  if (query.isError) return <div className="bo-page">{back}<ErrorState message={t('loadError')} onRetry={() => void query.refetch()} /></div>;
  const row = query.data.items.find(r => r.ref === orderRef || r.id === orderRef);
  if (!row) return <div className="bo-page">{back}<h1 className="bo-title">{orderRef}</h1><p role="alert">{t('notFound')}</p></div>;
  return <OrderView row={row} asOf={query.data.asOf} />;
}

function OrderView({ row, asOf }: { row: Row; asOf: string }) {
  const t = useOrdersT();
  const { locale } = useLocale();
  const fmt = useFormatters();
  const { can } = useGrant();
  const [cancelling, setCancelling] = useState(false);
  const isOrder = row.kind === 'order';
  const delivery = useQuery({ ...deliveryQuery(row.id), enabled: isOrder });
  const afterSale = useQuery({ ...afterSaleQuery(row.id), enabled: isOrder });
  const issue = issueText(row, asOf, t, locale);
  const seller = row.sellers.join(', ') || t('none');
  const canCancel = isOrder && can('support') && !!afterSale.data && !CLOSED.includes(afterSale.data.state);
  return (
    <div className="bo-page">
      <RecordHeader back={<Link to="/orders">{t('back')}</Link>} title={row.ref ?? row.id}
        status={<StatusTag tone={STATUS_TONE[row.status]} size="lg">{t(`s_${row.status}` as OrdersKey)}</StatusTag>}
        facts={[
          { label: t('f_kind'), value: t(`t_${row.type}` as OrdersKey) },
          { label: t('f_who'), value: t('who', { customer: row.customer ?? t('someone'), seller }) },
          { label: t('f_amount'), value: fmt.price(row.amountCents) },
          { label: t(row.kind === 'booking' ? 'bookedLabel' : 'placedLabel'), value: fmt.date(row.at, 'dateTime') },
          delivery.data ? { label: t('f_market'), value: delivery.data.market } : null,
        ]} />
      <RecordLayout asideLabel={t('actions')} aside={
        <ActionPanel title={t('actions')} note={isOrder ? (can('support') ? undefined : t('noAction')) : t('bookingNote')}>
          {isOrder ? <ActionPanelItem variant="destructive" label={t('cancelOrderDots')} description={t('cancelDesc')} disabled={!canCancel} onClick={() => setCancelling(true)} /> : null}
          <ActionPanelItem label={t('openDisputes')} href="/disputes" />
        </ActionPanel>}>
        {issue !== t('none') ? <Alert tone="error" title={t('secIssue')}>{issue}</Alert> : null}
        {isOrder ? (
          <RecordTabs label={t('tabsRecord')} tabs={[
            { value: 'delivery', label: t('detail'), render: () => <DeliveryTab query={delivery} /> },
            { value: 'history', label: t('tabHistory'), render: () => <AfterSalePanel orderId={row.id} /> },
          ]} />
        ) : null}
      </RecordLayout>
      {cancelling ? <StaffCancelDialog orderId={row.id} onClose={() => setCancelling(false)} /> : null}
    </div>
  );
}

function DeliveryTab({ query }: { query: ReturnType<typeof useQuery<import('./api').Delivery>> }) {
  const t = useOrdersT();
  const fmt = useFormatters();
  if (query.isPending) return null;
  if (query.isError) return <p className="nl-or-note">{query.error instanceof ApiError && query.error.status === 404 ? t('noDelivery') : t('deliveryError')}</p>;
  const d = query.data!;
  return (
    <>
      <FactList rows={[
        [t('d_state'), t(`ds_${d.state}` as OrdersKey)],
        [t('d_market'), d.market],
        d.run ? [t('d_run'), <><span>{d.run.label ?? d.run.id}</span>{d.run.late ? <> · <Tag tone="danger">{t('d_late')}</Tag></> : null}</>] : null,
        d.run?.courier?.name ? [t('d_courier'), d.run.courier.name] : null,
        d.dropoffEta ? [t('d_eta'), fmt.date(d.dropoffEta, 'time')] : null,
        d.deliveredAt ? [t('d_delivered'), fmt.date(d.deliveredAt, 'dateTime')] : null,
        d.proofKind ? [t('d_proof'), t(`proof_${d.proofKind}` as OrdersKey)] : null,
      ]} />
      <h3 className="bo-sheet-h3">{t('d_pickups')}</h3>
      <ul className="nl-or-list">
        {d.pickups.map(p => (
          <li key={p.merchantId}><strong>{p.name ?? p.merchantId}</strong> · {p.packedAt ? t('d_packed', { time: fmt.date(p.packedAt, 'time') }) : t('d_notPacked')}
            {p.pickedUpAt ? <> · {t('d_picked', { time: fmt.date(p.pickedUpAt, 'time') })}</> : null}</li>
        ))}
      </ul>
    </>
  );
}
