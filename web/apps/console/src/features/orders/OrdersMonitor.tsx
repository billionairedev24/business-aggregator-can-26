import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearch } from '@tanstack/react-router';
import {
  BoHeader, DataTable, ErrorState, formatNumber, KpiStrip, PageSkeleton, Segmented, UnderlineTabs, useLocale,
  type DataTableColumn, type Locale, type StatusTone,
} from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useGrant } from '../shell/grant';
import { usePlace, usePlaceName } from '../shell/scope';
import { SavedViews, statusTone } from '../shell/tables';
import { monitorQuery, VIEWS, type Monitor, type Row, type Status, type View } from './api';
import { useOrdersT, type OrdersKey, type OrdersT } from './messages';
import './orders.css';

export const TYPES = ['all', 'goods', 'food', 'service'] as const;
export type TypeFilter = (typeof TYPES)[number];
export interface OrdersSearch { view?: View; q?: string; province?: string; market?: string; type?: TypeFilter }

/** E-15 colour roles: issues danger, waiting on someone warning, finished success, the rest neutral (never the action colour). */
export const STATUS_TONE: Record<Status, StatusTone> = {
  new: 'info', live: 'info', escrow: 'neutral', escrow_48h: 'warning', late: 'danger', stuck: 'danger', issue: 'danger', delivered: 'success', done: 'success', cancelled: 'neutral', delivery_failed: 'danger',
};

/** "12 min", "2 h", "3.0 d" between two instants. */
export function ageBetween(since: string, now: string, t: OrdersT, locale: Locale): string {
  const minutes = Math.max(0, (Date.parse(now) - Date.parse(since)) / 60_000);
  if (minutes < 60) return t('ageMin', { n: Math.round(minutes) });
  if (minutes < 24 * 60) return t('ageH', { n: Math.round(minutes / 60) });
  return t('ageD', { n: formatNumber(minutes / 1440, locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) });
}

/** The Issue column: what makes the row need attention, from its status (no free text from customers). */
export function issueText(r: Row, asOf: string, t: OrdersT, locale: Locale): string {
  const age = r.since ? ageBetween(r.since, asOf, t, locale) : '';
  switch (r.status) {
    case 'late': return t(r.kind === 'booking' ? 'i_late_booking' : 'i_late_order', { age });
    case 'stuck': return t('i_stuck');
    case 'issue': return t(r.kind === 'booking' ? 'i_issue_booking' : 'i_issue_order');
    case 'escrow_48h': return t('i_escrow', { age });
    case 'delivery_failed': return t('i_failed');
    default: return t('none');
  }
}

interface TableRow { id: string; ref: string; type: string; who: string; amount: number; state: string; issue: string; row: Row }

/**
 * Orders & bookings (S-81; E-15 S-177): orders and service bookings in one dense table — the views as tabs with their
 * counts (needs attention first), the type filter and saved views, status tags — under the KPI strip; the top bar's
 * scope narrows it and its search finds a reference (`?q=`, every view). A row opens the order's page. Admin, dispatch
 * and support.
 */
export function OrdersMonitor() {
  const t = useOrdersT();
  const search = useSearch({ strict: false }) as OrdersSearch;
  const place = usePlace();
  const placeName = usePlaceName();
  const filter = { view: search.view ?? 'attention', q: search.q, province: place.province, market: place.market };
  const query = useQuery(monitorQuery(filter));
  const { locale } = useLocale();
  const head = (sub?: string) => <BoHeader title={t('title')} sub={sub} />;
  if (query.isPending) return <div className="bo-page">{head()}<PageSkeleton kpis={4} rows={6} /></div>;
  if (query.isError && !query.data) {
    return (
      <div className="bo-page">
        {head()}
        {query.error instanceof ValidationError ? <p role="alert" className="nl-or-error">{t('badFilter')}</p> : <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />}
      </div>
    );
  }
  const data = query.data!;
  return (
    <div className="bo-page">
      {head(t('sub2', { scope: placeName, week: formatNumber(data.week, locale), attention: formatNumber(data.counts.attention, locale) }))}
      <MonitorView data={data} search={search} view={filter.view} />
    </div>
  );
}

function MonitorView({ data, search, view }: { data: Monitor; search: OrdersSearch; view: View }) {
  const t = useOrdersT();
  const { locale } = useLocale();
  const navigate = useNavigate();
  const { roleName } = useGrant();
  const type = search.type ?? 'all';
  const go = (next: Partial<OrdersSearch>) => void navigate({ to: '/orders', search: { ...search, ...next } as never, replace: true });
  const rows: TableRow[] = data.items.filter(r => type === 'all' || r.type === type).map(r => {
    const seller = r.sellers.length > 1 ? t('shops', { n: r.sellers.length }) : (r.sellers[0] ?? t('none'));
    return {
      id: r.id, ref: r.ref ?? r.id, type: t(`t_${r.type}` as OrdersKey), who: t('who', { customer: r.customer ?? t('someone'), seller }),
      amount: r.amountCents, state: t(`s_${r.status}` as OrdersKey), issue: issueText(r, data.asOf, t, locale), row: r,
    };
  });
  const columns: DataTableColumn<TableRow>[] = [
    { key: 'ref', label: t('c_id'), primary: true }, { key: 'type', label: t('c_type') }, { key: 'who', label: t('c_who') },
    { key: 'amount', label: t('c_amount'), type: 'money' }, { key: 'state', label: t('c_state'), type: 'tag' }, { key: 'issue', label: t('c_issue') },
  ];
  const kpi = (v: Exclude<View, 'all' | 'failed'>) => ({ key: v, label: t(`k_${v}`), value: formatNumber(data.counts[v], locale), onClick: () => go({ view: v }) });
  return (
    <>
      <KpiStrip label={t('kpis')} items={[kpi('attention'), kpi('live'), kpi('escrow'), kpi('late')]} />
      <DataTable<TableRow> dense entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} pageSize={25} initialSearch={search.q}
        tabs={<UnderlineTabs aria-label={t('views')} value={view} onChange={v => go({ view: v })}
          options={VIEWS.map(v => ({ value: v, label: t(`tab_${v}` as OrdersKey), count: v === 'all' ? undefined : formatNumber(data.counts[v], locale), tone: v === 'attention' && data.counts.attention ? 'danger' as const : undefined }))} />}
        filters={<>
          <Segmented name="orders-type" aria-label={t('typeFilter')} value={type} onChange={v => go({ type: v === 'all' ? undefined : v })}
            options={TYPES.map(v => ({ value: v, label: t(`ty_${v}` as OrdersKey) }))} />
          <SavedViews screen="orders" current={{ view: search.view, type: search.type, q: search.q }} onApply={s => void navigate({ to: '/orders', search: s as never })} />
        </>}
        rowTones={r => ({ state: statusTone(STATUS_TONE[r.row.status]) })} can={{ create: false, update: false, delete: false }} roleName={roleName}
        emptyText={t('empty')} openLabel={t('open')} reportName="orders"
        onOpen={r => void navigate({ to: '/orders/$orderRef', params: { orderRef: r.ref } })} />
      {data.truncated ? <p className="nl-or-note">{t('truncated', { n: formatNumber(data.items.length, locale) })}</p> : null}
    </>
  );
}
