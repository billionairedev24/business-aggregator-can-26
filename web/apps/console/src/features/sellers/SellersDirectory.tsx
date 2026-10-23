import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearch } from '@tanstack/react-router';
import { Prohibit } from '@phosphor-icons/react';
import {
  BoHeader, DataTable, ErrorState, FilterChip, formatCompactMoney, formatNumber, KpiStrip, PageSkeleton, Segmented, UnderlineTabs, useLocale,
  type DataTableColumn, type DataTableTone, type StatusTone,
} from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useRegions } from '../shell/api';
import { useGrant } from '../shell/grant';
import { usePlace, usePlaceName } from '../shell/scope';
import { SavedViews, statusTone } from '../shell/tables';
import { sellersQuery, type Directory, type Seller } from './api';
import { flagsText, percent, regionName } from './format';
import { useSellersT, type SellersKey } from './messages';
import { OversightDialog } from './OversightDialog';
import './sellers.css';

export const SELLER_VIEWS = ['all', 'live', 'review', 'paused', 'suspended'] as const;
export type SellerView = (typeof SELLER_VIEWS)[number];
export const DEPTS = ['all', 'services', 'shop', 'food'] as const;
export type Dept = (typeof DEPTS)[number];
export interface SellersSearch { q?: string; province?: string; market?: string; risk?: boolean; expiring?: boolean; view?: SellerView; dept?: Dept }

/** E-15 colour roles: a status is its semantic pair (never the action or commerce colour); a tier is a plain fact. */
export const STATUS_TONE: Record<NonNullable<Seller['status']>, StatusTone> = { active: 'success', pending: 'info', applicant: 'info', paused: 'neutral', suspended: 'danger' };
const TIER_TONE: Record<string, DataTableTone> = { master: 'tag-success', trusted: 'tag-info', registered: 'tag-neutral' };

const inView = (s: Seller, v: SellerView) =>
  v === 'all' || (v === 'live' && s.status === 'active') || (v === 'review' && (s.status === 'pending' || s.status === 'applicant'))
  || (v === 'paused' && s.status === 'paused') || (v === 'suspended' && s.status === 'suspended');
const inDept = (s: Seller, d: Dept) =>
  d === 'all' || (d === 'services' && (s.type === 'provider' || s.type === 'both')) || (d === 'shop' && (s.type === 'seller' || s.type === 'both')) || (d === 'food' && s.type === 'kitchen');
const expiring = (s: Seller) => s.flags.some(f => f.kind === 'check_expiring' || (f.kind === 'check_due' && f.status === 'expired'));

interface Row { id: string; name: string; cat: string; dept: string; region: string; tier: string; q: number | null; gmv: number; disp: string; flags: string; status: string; src: Seller }

/**
 * Sellers & providers (S-82; E-15 S-177, design/revamp/a.html screen 9 "Console sellers table"): the KPI strip, the
 * status views as tabs with counts, the department and quick filters with saved views, dense rows with status tags,
 * and the bulk bar's red Suspend… (a reason the business sees, one audited request each). A row opens the seller's
 * page. Admin, trust & safety and support open it; the top bar's scope (or `?province=&market=`) narrows it.
 */
export function SellersDirectory() {
  const t = useSellersT();
  const { locale } = useLocale();
  const search = useSearch({ strict: false }) as SellersSearch;
  const place = usePlace();
  const filter = { q: search.q, province: place.province, market: place.market };
  const query = useQuery(sellersQuery(filter));
  const placeName = usePlaceName();
  const head = (sub?: string) => <BoHeader title={t('title')} sub={sub} />;
  if (query.isPending) return <div className="bo-page">{head()}<PageSkeleton kpis={4} rows={8} /></div>;
  if (query.isError && !query.data) {
    return <div className="bo-page">{head()}{query.error instanceof ValidationError ? <p role="alert" className="nl-sl-error">{t('badFilter')}</p> : <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />}</div>;
  }
  const data = query.data!;
  return (
    <div className="bo-page">
      {head(t('sub2', { scope: placeName, n: data.items.length }))}
      <DirectoryView data={data} search={search} />
      {data.truncated ? <p className="nl-sl-note">{t('truncated', { n: formatNumber(data.items.length, locale) })}</p> : null}
    </div>
  );
}

function DirectoryView({ data, search }: { data: Directory; search: SellersSearch }) {
  const t = useSellersT();
  const { locale } = useLocale();
  const navigate = useNavigate();
  const regions = useRegions(locale).data;
  const { can, roleName } = useGrant();
  const [acting, setActing] = useState<Seller[] | null>(null);
  const view = search.view ?? 'all';
  const dept = search.dept ?? 'all';
  const go = (next: Partial<SellersSearch>) => void navigate({ to: '/sellers', search: { ...search, ...next } as never, replace: true });
  const items = data.items.filter(s => inDept(s, dept) && (!search.risk || s.flags.length > 0) && (!search.expiring || expiring(s)));
  const shown = items.filter(s => inView(s, view));
  const rows: Row[] = shown.map(s => ({
    id: s.id, name: s.name, cat: s.category ? (s.category.names[locale] ?? s.category.names.en ?? '') : '',
    dept: t(`type_${s.type}` as SellersKey), region: [s.city, regionName(s.province, regions, t)].filter(Boolean).join(' · '),
    tier: s.tier ? t(`tier_${s.tier}` as SellersKey) : t('none'), q: s.quality ?? null, gmv: s.gmv90Cents,
    disp: s.disputeRate == null ? t('none') : percent(s.disputeRate, locale), flags: flagsText(s, t),
    status: s.status ? t(`st_${s.status}` as SellersKey) : t('none'), src: s,
  }));
  const columns: DataTableColumn<Row>[] = [
    { key: 'name', label: t('c_name'), sub: 'cat', subLabel: t('c_cat'), primary: true },
    { key: 'dept', label: t('c_dept'), sub: 'region', subLabel: t('c_type') },
    { key: 'status', label: t('c_status'), type: 'tag', filter: false },
    { key: 'tier', label: t('c_tier'), type: 'tag', options: [t('tier_master'), t('tier_trusted'), t('tier_registered')] },
    { key: 'q', label: t('c_q'), type: 'num', format: v => (v == null ? t('none') : String(v)) },
    { key: 'gmv', label: t('c_sales'), type: 'money' },
    { key: 'disp', label: t('c_disp') }, { key: 'flags', label: t('c_flags'), priority: -1 },
  ];
  const count = (v: SellerView) => formatNumber(items.filter(s => inView(s, v)).length, locale);
  const live = data.items.filter(s => s.status === 'active').length;
  const review = data.items.filter(s => s.status === 'pending' || s.status === 'applicant').length;
  const gmv = data.items.reduce((sum, s) => sum + s.gmv90Cents, 0);
  const suspendable = [t('st_active'), t('st_paused')];
  return (
    <>
      <KpiStrip label={t('kpis')} items={[
        { key: 'live', label: t('kLive'), value: formatNumber(live, locale), onClick: () => go({ view: 'live' }) },
        { key: 'review', label: t('kReview'), value: formatNumber(review, locale), onClick: () => go({ view: 'review' }) },
        { key: 'risk', label: t('kRisk'), value: formatNumber(data.atRisk, locale), delta: t('kRiskD'), trend: data.atRisk ? 'down' : 'flat', onClick: () => go({ risk: true, view: 'all' }) },
        { key: 'gmv', label: t('kGmv'), value: formatCompactMoney(gmv, locale), delta: t('kGmvD', { n: data.items.length }) },
      ]} />
      <DataTable<Row> dense entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} pageSize={25} roleName={roleName}
        initialSearch={search.q}
        views={<UnderlineTabs aria-label={t('views')} value={view} onChange={v => go({ view: v === 'all' ? undefined : v })}
          options={SELLER_VIEWS.map(v => ({ value: v, label: t(`v_${v}` as SellersKey), count: count(v) }))} />}
        filters={<>
          <Segmented name="sellers-dept" aria-label={t('dept')} value={dept} onChange={d => go({ dept: d === 'all' ? undefined : d })}
            options={DEPTS.map(d => ({ value: d, label: t(`d_${d}` as SellersKey) }))} />
          <FilterChip label={t('atRiskChip')} applied={!!search.risk} onToggle={() => go({ risk: search.risk ? undefined : true })} />
          <FilterChip label={t('expiringChip')} applied={!!search.expiring} onToggle={() => go({ expiring: search.expiring ? undefined : true })} />
          <SavedViews screen="sellers" current={{ view: search.view, dept: search.dept, risk: search.risk, expiring: search.expiring, q: search.q }}
            onApply={s => void navigate({ to: '/sellers', search: s as never })} />
        </>}
        rowTones={r => ({ tier: TIER_TONE[r.src.tier ?? ''] ?? 'tag-neutral', status: statusTone(r.src.status ? STATUS_TONE[r.src.status] : 'neutral') })}
        can={{ create: false, update: can('suspend'), delete: false }} emptyText={t('empty')} openLabel={t('open')} reportName="sellers"
        onOpen={r => void navigate({ to: '/sellers/$sellerId', params: { sellerId: r.id } })}
        actions={[{ id: 'suspend', label: t('bulkSuspend'), icon: Prohibit, perm: 'update', danger: true, when: { key: 'status', in: suspendable } }]}
        onAction={(_a, picked) => { setActing(picked.map(p => p.src)); return false; }} />
      {acting?.length ? <OversightDialog seller={acting[0]!} more={acting.slice(1)} action="suspend" checks={[]} onClose={() => setActing(null)} /> : null}
    </>
  );
}
