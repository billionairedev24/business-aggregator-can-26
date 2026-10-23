import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, DataTable, Drawer, ErrorState, PageSkeleton, Price, StatusTag, formatWhen, useFormatters, useLocale, type DataTableAction, type DataTableColumn, type DataTableQuickFilter, type DataTableView, type Locale, type StatusTone, timeZone, BoHeader, FactList, KpiStrip, SheetSection } from '@northline/ui';
import { useMerchantId, useRole } from '../shell/api';
import { addDays, localDate, today } from '../../lib/time';
import { ordersQuery, usePackOrder, type Order, type OrderBoard } from './api';
import { useOrdersT } from './messages';
import { CancelOrderPanel, ReturnsPanel } from './AfterSale';
import './Orders.css';

type T = ReturnType<typeof useOrdersT>;

export interface OrderRow { id: string; ref: string; placed: string; who: string; items: string; run: string; total: number; state: string; status: Order['status']; placedOn: string; windowOn: string }

export const itemsText = (o: Order) => o.lines.map(l => (l.qty > 1 ? `${l.title} ×${l.qty}` : l.title)).join(', ');

/** S-175: one status colour per order state, here and in the detail sheet (semantic roles: never the action colour). */
export const statusTone = (s: Order['status']): StatusTone =>
  s === 'to_pack' ? 'warning' : s === 'issue' ? 'danger' : s === 'delivered' ? 'success' : s === 'cancelled' ? 'neutral' : 'info';

const timeOf = (iso: string, locale: Locale) => formatWhen(iso, locale, 'time');

export function runText(o: Order, t: T, locale: Locale, now = new Date()): string {
  if (o.status === 'delivered' || (o.deliveredAt && o.status === 'issue')) return o.deliveredAt ? t('runDelivered', { time: timeOf(o.deliveredAt, locale) }) : t('runNone');
  if (!o.windowStartsAt) return t('runNone');
  const day = localDate(o.windowStartsAt), d0 = today(now);
  const time = timeOf(o.windowStartsAt, locale);
  const hour = Number(new Intl.DateTimeFormat('en-US', { timeZone: timeZone(), hour: 'numeric', hourCycle: 'h23' }).format(new Date(o.windowStartsAt)));
  if (day === d0) return hour >= 17 ? t('runTonight', { time }) : t('runToday', { time });
  if (day === addDays(d0, 1)) return t('runTomorrow', { time });
  return t('runOn', { date: formatWhen(o.windowStartsAt, locale), time });
}

/** The courier's pickup at this shop (S-86): picked up, waiting, due, or still finding a courier. */
export function courierText(p: NonNullable<Order['courierPickup']>, t: T, locale: Locale): string {
  if (p.pickedUpAt) return t('courierPickedUp', { time: timeOf(p.pickedUpAt, locale) });
  if (p.arrivedAt) return t('courierWaiting');
  const time = p.eta ? timeOf(p.eta, locale) : '—';
  const text = p.courierAssigned ? t('courierDue', { time }) : t('courierFinding', { time });
  return p.runLabel ? `${p.runLabel} · ${text}` : text;
}

export const statusText = (o: Pick<Order, 'status' | 'issueNote'>, t: T) => (o.status === 'issue' && o.issueNote ? t('s_issue_note', { note: o.issueNote }) : t(`s_${o.status}`));

export function headline(b: OrderBoard, t: T, locale: Locale, now = new Date()): string {
  const c = b.counts.nextCutoff;
  if (!c || b.counts.toPack === 0) return t('titleNone');
  const time = timeOf(c, locale), d = localDate(c), d0 = today(now);
  if (d === d0) {
    const tonight = b.items.some(o => o.status === 'to_pack' && o.windowStartsAt && localDate(o.windowStartsAt) === d0 && Number(new Intl.DateTimeFormat('en-US', { timeZone: timeZone(), hour: 'numeric', hourCycle: 'h23' }).format(new Date(o.windowStartsAt))) >= 17);
    return tonight ? t('titleTonight', { time }) : t('titleToday', { time });
  }
  if (d === addDays(d0, 1)) return t('titleTomorrow', { time });
  return t('titleLater', { date: formatWhen(c, locale), time });
}

export function OrdersScreen({ search, view, onViewChange }: { search?: string; view?: string; onViewChange?: (v: string) => void } = {}) {
  const merchantId = useMerchantId();
  const role = useRole();
  const t = useOrdersT();
  const q = useQuery(ordersQuery(merchantId));
  const pack = usePackOrder(merchantId);
  const { locale } = useLocale();
  const f = useFormatters();
  const [open, setOpen] = useState<string | null>(null);

  const d0 = today();
  const rows = useMemo<OrderRow[]>(() => (q.data?.items ?? []).map(o => ({
    id: o.id, ref: o.ref ?? o.id, who: o.customerName ?? '—', items: itemsText(o), run: runText(o, t, locale), total: o.totalCents, state: statusText(o, t), status: o.status,
    placed: o.placedAt ? (localDate(o.placedAt) === d0 ? f.when(o.placedAt, 'time') : f.when(o.placedAt, 'dateTime')) : '—',
    placedOn: o.placedAt ? localDate(o.placedAt) : '', windowOn: o.windowStartsAt ? localDate(o.windowStartsAt) : '',
  })), [q.data, t, locale, f, d0]);

  const columns: DataTableColumn<OrderRow>[] = [
    { key: 'ref', label: t('colOrder'), primary: true },
    { key: 'placed', label: t('colPlaced'), priority: 40 },
    { key: 'who', label: t('colCustomer'), card: 'line' },
    { key: 'items', label: t('colItems'), card: 'line' },
    { key: 'run', label: t('colRun'), filter: 'facet', card: 'meta' },
    { key: 'total', label: t('colTotal'), type: 'money', format: v => f.price(v as number), card: 'end' },
    { key: 'state', label: t('colStatus'), type: 'tag', status: r => statusTone(r.status) },
  ];
  const is = (...s: Order['status'][]) => (r: OrderRow) => s.includes(r.status);
  const views: DataTableView<OrderRow>[] = [
    { id: 'all', label: t('viewAll') },
    { id: 'to_pack', label: t('s_to_pack'), when: is('to_pack') },
    { id: 'awaiting_pickup', label: t('s_awaiting_pickup'), when: is('awaiting_pickup') },
    { id: 'out_for_delivery', label: t('s_out_for_delivery'), when: is('out_for_delivery'), hideEmpty: true },
    { id: 'delivered', label: t('s_delivered'), when: is('delivered') },
    { id: 'issue', label: t('viewIssues'), when: is('issue'), tone: 'danger' },
    { id: 'cancelled', label: t('viewCancelled'), when: is('cancelled'), hideEmpty: true },
  ];
  const quick: DataTableQuickFilter<OrderRow>[] = [
    { id: 'placed-today', label: t('quickPlacedToday'), when: r => r.placedOn === d0 },
    { id: 'today', label: t('quickRunToday'), when: r => r.windowOn === d0 && r.status !== 'delivered' },
    { id: 'tomorrow', label: t('quickRunTomorrow'), when: r => r.windowOn === addDays(d0, 1) },
  ];
  const actions: DataTableAction<OrderRow>[] = [{ id: 'pack', label: t('markPacked'), perm: 'update', inline: true, emphasis: 'primary', when: { key: 'status', in: ['to_pack'] } }];
  const detail = q.data?.items.find(o => o.id === open);
  const roleName = t(`role_${role}` as Parameters<T>[0]);

  if (q.isPending) return <PageSkeleton kpis={4} rows={6} />;
  if (q.isError) return <><BoHeader title={t('title')} /><ErrorState message={t('loadError')} onRetry={() => void q.refetch()} /></>;
  const c = q.data.counts;
  return (
    <div className="nl-orders">
      <BoHeader title={t('title')} sub={headline(q.data, t, locale)} />
      <div aria-live="polite">
        {/* S-175: a delta's colour means good (green) or bad (red), never "a number went up": work still to do (to pack)
            is not bad, so it stays neutral; an open issue is bad; none open is good. */}
        <KpiStrip label={t('kpis')} items={[
          { key: 'pack', label: t('kpiToPack'), value: f.number(c.toPack), delta: c.toPack > 0 && c.nextRunLabel ? t('runDelta', { run: c.nextRunLabel }) : undefined },
          { key: 'awaiting', label: t('kpiAwaiting'), value: f.number(c.awaitingPickup) },
          { key: 'delivered', label: t('kpiDelivered'), value: f.number(c.deliveredToday) },
          { key: 'issues', label: t('kpiIssues'), value: f.number(c.issues), trend: c.issues > 0 ? 'down' : 'up', delta: c.issues > 0 ? t('issuesDelta') : t('issuesNone') },
        ]} />
      </div>
      {pack.isError ? <Alert tone="error">{t('packError')}</Alert> : null}
      <DataTable<OrderRow>
        initialSearch={search}
        entity={t('entity')} plural={t('plural')} roleName={roleName}
        columns={columns} rows={rows} views={views} view={view} onViewChange={onViewChange} quickFilters={quick} searchPlaceholder={t('search')}
        can={{ create: false, update: role !== 'bookkeeper', delete: false, export: true }}
        actions={actions}
        onAction={async (a, hit) => { if (a.id === 'pack') await Promise.all(hit.map(r => pack.mutateAsync(r.id))); }}
        onOpen={r => setOpen(r.id)}
        emptyText={t('empty')}
      />
      <p className="nl-orders-foot">{t('footer')}</p>
      <ReturnsPanel merchantId={merchantId} canDecide={role !== 'bookkeeper'} roleName={roleName} />
      <Drawer open={!!detail} onClose={() => setOpen(null)} title={detail ? t('detailTitle', { ref: detail.ref ?? '' }) : ''}
        footer={detail?.status === 'to_pack' && role !== 'bookkeeper' ? <button type="button" className="btn btn-primary" disabled={pack.isPending} onClick={() => pack.mutate(detail.id, { onSuccess: () => setOpen(null) })}>{t('markPacked')}</button> : undefined}>
        {detail ? <>
          <div className="nl-orders-status">
            <StatusTag tone={statusTone(detail.status)}>{statusText(detail, t)}</StatusTag>
            {detail.placedAt ? <span className="nl-orders-sub">{t('placedOn', { date: f.when(detail.placedAt, 'dateTime') })}</span> : null}
          </div>
          <SheetSection title={t('secItems')}>
            <ul className="nl-orders-lines">
              {detail.lines.map(l => (
                <li key={l.id}>
                  <span className="nl-orders-line-name">{l.qty} × {l.title}</span>
                  <span className="nl-orders-line-state">{t(`lineState_${l.state}` as Parameters<T>[0])}</span>
                  <Price className="nl-orders-line-price" size="sm" cents={l.qty * l.unitCents} />
                </li>
              ))}
              <li className="nl-orders-total"><span>{t('detailTotal')}</span><Price cents={detail.totalCents} /></li>
            </ul>
          </SheetSection>
          <SheetSection title={t('secCustomer')}>
            <FactList rows={[[t('detailCustomer'), detail.customerName ?? '—'], [t('detailArea'), detail.area ?? '—']]} />
          </SheetSection>
          <SheetSection title={t('secDelivery')}>
            <FactList rows={[
              [t('detailRun'), [detail.runLabel, runText(detail, t, locale)].filter(Boolean).join(' · ')],
              detail.cutoffAt ? [t('detailCutoff'), f.when(detail.cutoffAt, 'dateTime')] : null,
              detail.courierPickup ? [t('detailCourier'), courierText(detail.courierPickup, t, locale)] : null,
            ]} />
            <p className="nl-orders-note">{t('statusNote')}</p>
          </SheetSection>
          {role !== 'bookkeeper' ? <CancelOrderPanel key={detail.id} merchantId={merchantId} order={detail} onDone={() => setOpen(null)} /> : null}
        </> : null}
      </Drawer>
    </div>
  );
}
