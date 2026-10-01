import { useState } from 'react';
import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { useNavigate, useSearch } from '@tanstack/react-router';
import { Chip, DataTable, Drawer, ErrorState, formatNumber, PageSkeleton, Tag, useFormatters, useLocale, type DataTableColumn, type DataTableTone, type Locale } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useGrant } from '../shell/grant';
import { PlaceSelect } from './PlaceSelect';
import { deliveryQuery, monitorQuery, VIEWS, type Delivery, type Monitor, type Row, type Status, type View } from './api';
import { useOrdersT, type OrdersKey, type OrdersT } from './messages';
import './orders.css';

export interface OrdersSearch { view?: View; q?: string; province?: string; market?: string }

const TONE: Record<Status, DataTableTone> = {
  new: 'tag-neutral', live: 'tag-neutral', escrow: 'tag-neutral', escrow_48h: 'tag-neutral', late: 'tag-accent-2', stuck: 'tag-accent-2',
  issue: 'tag-accent-2', delivered: 'tag-accent', done: 'tag-accent', cancelled: 'tag-neutral',
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
    default: return t('none');
  }
}

interface TableRow { id: string; ref: string; type: string; who: string; amount: number; state: string; issue: string; row: Row }

/**
 * Orders & bookings (S-81, design 03 `orders`): orders and service bookings in one table — the ones that need
 * attention first — with the design's chips (needs attention, live, escrow > 48 h, late, all), a reference search and
 * the region model's province and market. Opening an order shows its delivery (S-86). Admin, dispatch and support.
 */
export function OrdersMonitor() {
  const t = useOrdersT();
  const search = useSearch({ strict: false }) as OrdersSearch;
  const navigate = useNavigate();
  const filter = { view: search.view ?? 'attention', q: search.q, province: search.province, market: search.market };
  const query = useQuery(monitorQuery(filter));
  const go = (next: Partial<OrdersSearch>) => void navigate({ to: '/orders', search: { ...search, ...next } as never });
  const places = <PlaceSelect filter={filter} onChange={p => go({ ...p })} />;
  if (query.isPending) return <PageSkeleton kpis={0} rows={6} />;
  if (query.isError && !query.data) {
    return (
      <div>
        <div className="nl-or-top"><span className="nl-or-kicker">{t('kicker')}</span>{places}</div>
        {query.error instanceof ValidationError ? <p role="alert" className="nl-or-error">{t('badFilter')}</p> : <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />}
      </div>
    );
  }
  return <MonitorView data={query.data!} filter={filter} places={places} onFilter={go} />;
}

function MonitorView({ data, filter, places, onFilter }: { data: Monitor; filter: OrdersSearch & { view: View }; places: React.ReactNode; onFilter: (next: Partial<OrdersSearch>) => void }) {
  const t = useOrdersT();
  const { locale } = useLocale();
  const fmt = useFormatters();
  const { roleName } = useGrant();
  const [open, setOpen] = useState<Row | null>(null);
  const [q, setQ] = useState(filter.q ?? '');
  const label = (v: View) => (v === 'all' ? t('v_all') : t(`v_${v}` as OrdersKey, { n: formatNumber(data.counts[v], locale) }));
  const rows: TableRow[] = data.items.map(r => {
    const seller = r.sellers.length > 1 ? t('shops', { n: r.sellers.length }) : (r.sellers[0] ?? t('none'));
    return {
      id: r.id, ref: r.ref ?? r.id, type: t(`t_${r.type}` as OrdersKey), who: t('who', { customer: r.customer ?? t('someone'), seller }),
      amount: r.amountCents, state: t(`s_${r.status}` as OrdersKey), issue: issueText(r, data.asOf, t, locale), row: r,
    };
  });
  const columns: DataTableColumn<TableRow>[] = [
    { key: 'ref', label: t('c_id'), primary: true }, { key: 'type', label: t('c_type'), filter: 'facet' }, { key: 'who', label: t('c_who') },
    { key: 'amount', label: t('c_amount'), type: 'money' }, { key: 'state', label: t('c_state'), type: 'tag' }, { key: 'issue', label: t('c_issue') },
  ];
  return (
    <div>
      <div className="nl-or-top"><span className="nl-or-kicker">{t('kicker')}</span>{places}</div>
      <h1 className="nl-or-title">{t('title', { week: formatNumber(data.week, locale), attention: formatNumber(data.counts.attention, locale) })}</h1>
      <div className="nl-or-bar">
        <div className="nl-or-chips" role="group" aria-label={t('views')}>
          {VIEWS.map(v => <Chip key={v} selected={filter.view === v} onClick={() => onFilter({ view: v })}>{label(v)}</Chip>)}
        </div>
        <form className="nl-or-search" role="search" onSubmit={e => { e.preventDefault(); onFilter({ q: q.trim() || undefined }); }}>
          <input className="input" type="search" aria-label={t('search')} placeholder={t('search')} value={q} onChange={e => setQ(e.target.value)} />
        </form>
      </div>
      <DataTable<TableRow> entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} pageSize={10}
        rowTones={r => ({ state: TONE[r.row.status] })} can={{ create: false, update: false, delete: false }} roleName={roleName}
        emptyText={t('empty')} openLabel={t('open')} onOpen={r => setOpen(r.row)} reportName="orders" />
      {data.truncated ? <p className="nl-or-note">{t('truncated', { n: formatNumber(data.items.length, locale) })}</p> : null}
      {open ? <RowDrawer row={open} onClose={() => setOpen(null)} fmt={fmt} /> : null}
    </div>
  );
}

function RowDrawer({ row, onClose, fmt }: { row: Row; onClose: () => void; fmt: ReturnType<typeof useFormatters> }) {
  const t = useOrdersT();
  const delivery = useQuery({ ...deliveryQuery(row.id), enabled: row.kind === 'order' });
  const seller = row.sellers.join(', ');
  return (
    <Drawer open onClose={onClose} title={`${row.ref ?? row.id} · ${t(`t_${row.type}` as OrdersKey)}`}>
      <dl className="nl-or-dl">
        <div><dt>{t('c_who')}</dt><dd>{t('who', { customer: row.customer ?? t('someone'), seller })}</dd></div>
        <div><dt>{t('c_amount')}</dt><dd>{fmt.money(row.amountCents)}</dd></div>
        <div><dt>{t('c_state')}</dt><dd><Tag tone={TONE[row.status].replace('tag-', '') as never}>{t(`s_${row.status}` as OrdersKey)}</Tag></dd></div>
        <div><dt>{t(row.kind === 'booking' ? 'bookedLabel' : 'placedLabel')}</dt><dd>{fmt.date(row.at, 'dateTime')}</dd></div>
      </dl>
      {row.kind === 'order' ? <DeliveryPanel query={delivery} fmt={fmt} /> : null}
    </Drawer>
  );
}

function DeliveryPanel({ query, fmt }: { query: UseQueryResult<Delivery>; fmt: ReturnType<typeof useFormatters> }) {
  const t = useOrdersT();
  if (query.isPending) return null;
  if (query.isError) {
    return <p className="nl-or-note">{query.error instanceof ApiError && query.error.status === 404 ? t('noDelivery') : t('deliveryError')}</p>;
  }
  const d = query.data!;
  return (
    <>
      <h3 className="nl-or-h3">{t('detail')}</h3>
      <dl className="nl-or-dl">
        <div><dt>{t('d_state')}</dt><dd>{t(`ds_${d.state}` as OrdersKey)}</dd></div>
        <div><dt>{t('d_market')}</dt><dd>{d.market}</dd></div>
        {d.run ? <div><dt>{t('d_run')}</dt><dd><span>{d.run.label ?? d.run.id}</span>{d.run.late ? <> · <Tag tone="accent-2">{t('d_late')}</Tag></> : null}</dd></div> : null}
        {d.run?.courier?.name ? <div><dt>{t('d_courier')}</dt><dd>{d.run.courier.name}</dd></div> : null}
        {d.dropoffEta ? <div><dt>{t('d_eta')}</dt><dd>{fmt.date(d.dropoffEta, 'time')}</dd></div> : null}
        {d.deliveredAt ? <div><dt>{t('d_delivered')}</dt><dd>{fmt.date(d.deliveredAt, 'dateTime')}</dd></div> : null}
        {d.proofKind ? <div><dt>{t('d_proof')}</dt><dd>{t(`proof_${d.proofKind}` as OrdersKey)}</dd></div> : null}
      </dl>
      <h3 className="nl-or-h3">{t('d_pickups')}</h3>
      <ul className="nl-or-list">
        {d.pickups.map(p => (
          <li key={p.merchantId}><strong>{p.name ?? p.merchantId}</strong> · {p.packedAt ? t('d_packed', { time: fmt.date(p.packedAt, 'time') }) : t('d_notPacked')}
            {p.pickedUpAt ? <> · {t('d_picked', { time: fmt.date(p.pickedUpAt, 'time') })}</> : null}</li>
        ))}
      </ul>
    </>
  );
}
