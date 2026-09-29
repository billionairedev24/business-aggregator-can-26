import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Package } from '@phosphor-icons/react';
import { Alert, DataTable, Drawer, ErrorState, PageHeader, PageSkeleton, Tag, formatDate, useFormatters, useLocale, type DataTableAction, type DataTableColumn, type DataTableTone, type Locale } from '@northline/ui';
import { useMerchantId, useRole } from '../shell/api';
import { addDays, clockWithPeriod, localDate, today } from '../../lib/time';
import { ordersQuery, usePackOrder, type Order, type OrderBoard } from './api';
import { useOrdersT } from './messages';
import './Orders.css';

type T = ReturnType<typeof useOrdersT>;

export interface OrderRow { id: string; ref: string; who: string; items: string; run: string; total: number; state: string; status: Order['status'] }

export const itemsText = (o: Order) => o.lines.map(l => (l.qty > 1 ? `${l.title} ×${l.qty}` : l.title)).join(', ');

export function runText(o: Order, t: T, locale: Locale, now = new Date()): string {
  if (o.status === 'delivered' || (o.deliveredAt && o.status === 'issue')) return o.deliveredAt ? t('runDelivered', { time: clockWithPeriod(o.deliveredAt, locale).replace(/ (am|pm)$/, '') }) : t('runNone');
  if (!o.windowStartsAt) return t('runNone');
  const day = localDate(o.windowStartsAt), d0 = today(now);
  const time = clockWithPeriod(o.windowStartsAt, locale);
  const hour = Number(new Intl.DateTimeFormat('en-US', { timeZone: 'America/Edmonton', hour: 'numeric', hourCycle: 'h23' }).format(new Date(o.windowStartsAt)));
  if (day === d0) return hour >= 17 ? t('runTonight', { time }) : t('runToday', { time });
  if (day === addDays(d0, 1)) return t('runTomorrow', { time });
  return t('runOn', { date: formatDate(o.windowStartsAt, locale), time });
}

export const statusText = (o: Pick<Order, 'status' | 'issueNote'>, t: T) => (o.status === 'issue' && o.issueNote ? t('s_issue_note', { note: o.issueNote }) : t(`s_${o.status}`));

export function headline(b: OrderBoard, t: T, locale: Locale, now = new Date()): string {
  const c = b.counts.nextCutoff;
  if (!c || b.counts.toPack === 0) return t('titleNone');
  const time = clockWithPeriod(c, locale), d = localDate(c), d0 = today(now);
  if (d === d0) {
    const tonight = b.items.some(o => o.status === 'to_pack' && o.windowStartsAt && localDate(o.windowStartsAt) === d0 && Number(new Intl.DateTimeFormat('en-US', { timeZone: 'America/Edmonton', hour: 'numeric', hourCycle: 'h23' }).format(new Date(o.windowStartsAt))) >= 17);
    return tonight ? t('titleTonight', { time }) : t('titleToday', { time });
  }
  if (d === addDays(d0, 1)) return t('titleTomorrow', { time });
  return t('titleLater', { date: formatDate(c, locale), time });
}

export function OrdersScreen() {
  const merchantId = useMerchantId();
  const role = useRole();
  const t = useOrdersT();
  const q = useQuery(ordersQuery(merchantId));
  const pack = usePackOrder(merchantId);
  const { locale } = useLocale();
  const f = useFormatters();
  const [open, setOpen] = useState<string | null>(null);

  const rows = useMemo<OrderRow[]>(() => (q.data?.items ?? []).map(o => ({
    id: o.id, ref: o.ref ?? o.id, who: o.customerName ?? '—', items: itemsText(o), run: runText(o, t, locale), total: o.totalCents, state: statusText(o, t), status: o.status,
  })), [q.data, t, locale]);

  const columns: DataTableColumn<OrderRow>[] = [
    { key: 'ref', label: t('colOrder'), primary: true },
    { key: 'who', label: t('colCustomer') },
    { key: 'items', label: t('colItems') },
    { key: 'run', label: t('colRun'), filter: 'facet' },
    { key: 'total', label: t('colTotal'), type: 'money' },
    { key: 'state', label: t('colStatus'), type: 'tag' },
  ];
  const actions: DataTableAction<OrderRow>[] = [{ id: 'pack', label: t('markPacked'), icon: Package, perm: 'update', inline: true, when: { key: 'status', in: ['to_pack'] } }];
  const tone = (r: OrderRow): DataTableTone => (r.status === 'to_pack' ? 'tag-accent' : r.status === 'issue' ? 'tag-accent-2' : 'tag-neutral');
  const detail = q.data?.items.find(o => o.id === open);
  const roleName = t(`role_${role}` as Parameters<T>[0]);

  if (q.isPending) return <PageSkeleton kpis={0} rows={6} />;
  if (q.isError) return <><PageHeader kicker={t('kicker')} title={t('titleNone')} /><ErrorState message={t('loadError')} onRetry={() => void q.refetch()} /></>;
  const c = q.data.counts;
  return (
    <div className="nl-orders">
      <PageHeader kicker={t('kicker')} title={headline(q.data, t, locale)} />
      <div className="nl-orders-chips" aria-live="polite">
        <Tag tone={c.toPack > 0 ? 'accent' : 'neutral'}>{t('chipToPack', { n: c.toPack })}</Tag>
        <Tag tone="neutral">{t('chipAwaiting', { n: c.awaitingPickup })}</Tag>
        <Tag tone="neutral">{t('chipDelivered', { n: c.deliveredToday })}</Tag>
        <Tag tone={c.issues > 0 ? 'accent-2' : 'neutral'}>{t('chipIssues', { n: c.issues })}</Tag>
      </div>
      {pack.isError ? <Alert tone="error">{t('packError')}</Alert> : null}
      <DataTable<OrderRow>
        entity={t('entity')} plural={t('plural')} roleName={roleName}
        columns={columns} rows={rows} rowTones={r => ({ state: tone(r) })}
        can={{ create: false, update: role !== 'bookkeeper', delete: false, export: true }}
        actions={actions}
        onAction={async (a, hit) => { if (a.id === 'pack') await Promise.all(hit.map(r => pack.mutateAsync(r.id))); }}
        onOpen={r => setOpen(r.id)}
        emptyText={t('empty')}
      />
      <p className="nl-orders-foot">{t('footer')}</p>
      <Drawer open={!!detail} onClose={() => setOpen(null)} title={detail ? t('detailTitle', { ref: detail.ref ?? '' }) : ''}
        footer={detail?.status === 'to_pack' && role !== 'bookkeeper' ? <button type="button" className="btn btn-primary" disabled={pack.isPending} onClick={() => pack.mutate(detail.id, { onSuccess: () => setOpen(null) })}>{t('markPacked')}</button> : undefined}>
        {detail ? (
          <dl className="nl-orders-detail">
            <dt>{t('colStatus')}</dt><dd><span className={`tag ${detail.status === 'to_pack' ? 'tag-accent' : detail.status === 'issue' ? 'tag-accent-2' : 'tag-neutral'}`}>{statusText(detail, t)}</span></dd>
            <dt>{t('detailCustomer')}</dt><dd>{detail.customerName ?? '—'}</dd>
            <dt>{t('detailArea')}</dt><dd>{detail.area ?? '—'}</dd>
            <dt>{t('detailRun')}</dt><dd>{[detail.runLabel, runText(detail, t, locale)].filter(Boolean).join(' · ')}</dd>
            {detail.cutoffAt ? <><dt>{t('detailCutoff')}</dt><dd>{f.date(detail.cutoffAt, 'dateTime')}</dd></> : null}
            {detail.placedAt ? <><dt>{t('detailPlaced')}</dt><dd>{f.date(detail.placedAt, 'dateTime')}</dd></> : null}
            <dt>{t('detailLines')}</dt>
            <dd><ul className="nl-orders-lines">{detail.lines.map(l => <li key={l.id}><span>{l.qty} × {l.title}</span><span>{f.money(l.qty * l.unitCents)} · {t(`lineState_${l.state}` as Parameters<T>[0])}</span></li>)}</ul></dd>
            <dt>{t('detailTotal')}</dt><dd><strong>{f.money(detail.totalCents)}</strong></dd>
          </dl>
        ) : null}
      </Drawer>
    </div>
  );
}
