import { useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useSearch } from '@tanstack/react-router';
import {
  BoHeader, Button, Chip, DataTable, Drawer, ErrorState, FactList, formatNumber, PageSkeleton, SheetSection, Tag, useFormatters, useLocale,
  type DataTableColumn, type DataTableTone,
} from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { PlaceSelect } from '../orders/PlaceSelect';
import { useRegions } from '../shell/api';
import { useGrant } from '../shell/grant';
import { sellerQuery, sellersQuery, type Directory, type Seller } from './api';
import { checkTag, sellerMeta, trailText } from './SellerDetail';
import { compactMoney, flagText, flagsText, percent, regionName } from './format';
import { useSellersT, type SellersKey } from './messages';
import { OversightDialog } from './OversightDialog';
import './sellers.css';

export interface SellersSearch { q?: string; province?: string; market?: string; risk?: boolean; seller?: string }

const TIER_TONE: Record<string, DataTableTone> = { master: 'tag-accent', trusted: 'tag-accent', registered: 'tag-neutral' };
const STATUS_TONE: Record<string, DataTableTone> = { active: 'tag-accent', paused: 'tag-highlight', suspended: 'tag-accent-2', pending: 'tag-neutral', applicant: 'tag-neutral' };

interface Row { id: string; name: string; cat: string; type: string; tier: string; q: number | null; gmv: string; disp: string; flags: string; status: string; src: Seller }

/**
 * Sellers & providers (S-82; redesign § 2.3 → BO `orgs` table + sheet): every business that applied — tier, quality,
 * 90-day GMV, dispute rate, what puts it at risk and its status — searchable, by province and market, with Suspend /
 * Reinstate (the `suspend` action, a reason the business sees). A row opens the right sheet (`?seller=`); the full
 * page holds the other oversight actions. Admin, trust & safety and support open it.
 */
export function SellersDirectory() {
  const t = useSellersT();
  const { locale } = useLocale();
  const search = useSearch({ strict: false }) as SellersSearch;
  const navigate = useNavigate();
  const filter = { q: search.q, province: search.province, market: search.market };
  const query = useQuery(sellersQuery(filter));
  const go = (next: Partial<SellersSearch>) => void navigate({ to: '/sellers', search: { ...search, ...next } as never });
  const head = (sub?: string) => <BoHeader title={t('title')} sub={sub} actions={<PlaceSelect filter={filter} onChange={p => go(p)} />} />;
  if (query.isPending) return <div className="bo-page">{head()}<PageSkeleton kpis={0} rows={8} /></div>;
  if (query.isError && !query.data) {
    return <div className="bo-page">{head()}{query.error instanceof ValidationError ? <p role="alert" className="nl-sl-error">{t('badFilter')}</p> : <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />}</div>;
  }
  const data = query.data!;
  return (
    <div className="bo-page">
      {head(t('sub', { active: formatNumber(data.active, locale), atRisk: formatNumber(data.atRisk, locale) }))}
      <DirectoryView data={data} search={search} onSearch={go} />
    </div>
  );
}

function DirectoryView({ data, search, onSearch }: { data: Directory; search: SellersSearch; onSearch: (next: Partial<SellersSearch>) => void }) {
  const t = useSellersT();
  const { locale } = useLocale();
  const regions = useRegions(locale).data;
  const { can, roleName } = useGrant();
  const [q, setQ] = useState(search.q ?? '');
  const [acting, setActing] = useState<{ seller: Seller; action: 'suspend' | 'reinstate' } | null>(null);
  const items = search.risk ? data.items.filter(s => s.flags.length) : data.items;
  const rows: Row[] = items.map(s => ({
    id: s.id, name: s.name, cat: s.category ? (s.category.names[locale] ?? s.category.names.en ?? '') : '',
    type: t('typeRegion', { type: t(`type_${s.type}` as SellersKey), region: regionName(s.province, regions, t) }),
    tier: s.tier ? t(`tier_${s.tier}` as SellersKey) : t('none'), q: s.quality ?? null, gmv: compactMoney(s.gmv90Cents, locale),
    disp: s.disputeRate == null ? t('none') : percent(s.disputeRate, locale), flags: flagsText(s, t),
    status: s.status ? t(`st_${s.status}` as SellersKey) : t('none'), src: s,
  }));
  const columns: DataTableColumn<Row>[] = [
    { key: 'name', label: t('c_name'), sub: 'cat', subLabel: t('c_cat'), primary: true },
    { key: 'type', label: t('c_type'), filter: 'facet' },
    { key: 'tier', label: t('c_tier'), type: 'tag', options: [t('tier_master'), t('tier_trusted'), t('tier_registered')] },
    { key: 'q', label: t('c_q'), type: 'num', format: v => (v == null ? t('none') : String(v)) },
    { key: 'gmv', label: t('c_gmv') }, { key: 'disp', label: t('c_disp') }, { key: 'flags', label: t('c_flags') },
    { key: 'status', label: t('c_status'), type: 'tag' },
  ];
  const suspendable = [t('st_active'), t('st_paused')];
  const opened = search.seller ? data.items.find(s => s.id === search.seller) : undefined;
  return (
    <>
      <div className="nl-sl-bar">
        <Chip selected={!!search.risk} onClick={() => onSearch({ risk: search.risk ? undefined : true })}>{t('atRiskChip')}</Chip>
        <form role="search" className="nl-sl-search" onSubmit={e => { e.preventDefault(); onSearch({ q: q.trim() || undefined }); }}>
          <input className="input" type="search" aria-label={t('search')} placeholder={t('search')} value={q} onChange={e => setQ(e.target.value)} />
        </form>
      </div>
      <DataTable<Row> entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} pageSize={10} roleName={roleName}
        rowTones={r => ({ tier: TIER_TONE[r.src.tier ?? ''] ?? 'tag-neutral', status: STATUS_TONE[r.src.status ?? ''] ?? 'tag-neutral' })}
        can={{ create: false, update: can('suspend'), delete: false }} emptyText={t('empty')} openLabel={t('open')} reportName="sellers"
        onOpen={r => onSearch({ seller: r.id })}
        actions={[
          { id: 'suspend', label: t('suspend'), perm: 'update', inline: true, bulk: false, danger: true, when: { key: 'status', in: suspendable } },
          { id: 'reinstate', label: t('reinstate'), perm: 'update', inline: true, bulk: false, when: { key: 'status', in: [t('st_suspended')] } },
        ]}
        onAction={(a, picked) => { const s = picked[0]?.src; if (s) setActing({ seller: s, action: a.id as 'suspend' | 'reinstate' }); return false; }} />
      {data.truncated ? <p className="nl-sl-note">{t('truncated', { n: formatNumber(data.items.length, locale) })}</p> : null}
      {search.seller ? <SellerSheet id={search.seller} fallback={opened} canSuspend={can('suspend')} onClose={() => onSearch({ seller: undefined })}
        onAct={(seller, action) => setActing({ seller, action })} /> : null}
      {acting ? <OversightDialog seller={acting.seller} action={acting.action} checks={[]} onClose={() => setActing(null)} /> : null}
    </>
  );
}

/** One business in the right sheet (BO:811): at a glance, its figures against the floors, flags, checks, recent activity. */
function SellerSheet({ id, fallback, canSuspend, onClose, onAct }: {
  id: string; fallback?: Seller; canSuspend: boolean; onClose: () => void; onAct: (s: Seller, action: 'suspend' | 'reinstate') => void;
}) {
  const t = useSellersT();
  const { locale } = useLocale();
  const fmt = useFormatters();
  const regions = useRegions(locale).data;
  const query = useQuery(sellerQuery(id));
  const d = query.data;
  const s = d?.seller ?? fallback;
  const suspended = s?.status === 'suspended';
  const footer = s ? (
    <div className="nl-q-actions">
      <Link to="/sellers/$sellerId" params={{ sellerId: s.id }} className="btn btn-secondary">{t('openPage')}</Link>
      {canSuspend && s.status !== 'applicant' && s.status !== 'pending'
        ? <Button variant={suspended ? 'secondary' : 'danger'} onClick={() => onAct(s, suspended ? 'reinstate' : 'suspend')}>{suspended ? t('reinstate') : t('suspend')}</Button> : null}
    </div>
  ) : undefined;
  let body: ReactNode;
  if (query.isPending) body = <div aria-busy="true" className="nl-sl-note">…</div>;
  else if (query.isError || !d) body = <ErrorState message={t('detailError')} onRetry={() => void query.refetch()} />;
  else {
    const fmt1 = (n: number) => formatNumber(n, locale, { maximumFractionDigits: 1 });
    const floor = (v: number | null | undefined, f: number | null | undefined, inverted = false) =>
      v != null && f != null && (inverted ? v > f : v < f) ? <> · <span className="nl-sl-low">{t('belowFloor')}</span></> : null;
    const recent = [...d.trail].sort((a, b) => b.at.localeCompare(a.at)).slice(0, 3);
    body = <>
      <p className="bo-muted">{sellerMeta(d, t, locale)}</p>
      <SheetSection title={t('sheetGlance')}>
        <FactList rows={[
          [t('f_type'), t('typeRegion', { type: t(`type_${d.seller.type}` as SellersKey), region: regionName(d.seller.province, regions, t) })],
          [t('f_tier'), d.seller.tier ? t(`tier_${d.seller.tier}` as SellersKey) : t('none')],
          [t('f_status'), d.seller.status ? <Tag tone={suspended ? 'danger' : d.seller.status === 'active' ? 'success' : 'neutral'}>{t(`st_${d.seller.status}` as SellersKey)}</Tag> : t('none')],
          [t('f_rating'), t('reviews', { avg: fmt1(d.ratingAverage), n: d.ratingCount })],
          [t('f_quality'), <>{d.quality.value == null ? t('none') : formatNumber(d.quality.value, locale)}{floor(d.quality.value, d.quality.floor)}</>],
          [t('f_onTime'), <>{d.onTime.value == null ? t('none') : `${fmt1(d.onTime.value)}%`}{floor(d.onTime.value, d.onTime.floor)}</>],
          [t('f_disputes'), <>{d.disputes.value == null ? t('none') : `${fmt1(d.disputes.value)}%`}{floor(d.disputes.value, d.disputes.floor, true)}</>],
          [t('f_gmv'), compactMoney(d.seller.gmv90Cents, locale)],
        ]} />
      </SheetSection>
      <SheetSection title={t('sheetFlags')}>
        {d.seller.flags.length || d.seller.searchHidden
          ? <div className="nl-sl-tags">
              {d.seller.searchHidden ? <Tag tone="warning">{t(d.seller.searchHidden === 'rating_floor' ? 'hiddenFloor' : 'hidden')}</Tag> : null}
              {d.seller.flags.map((f, i) => <Tag key={i} tone="warning">{flagText(f, t)}</Tag>)}
            </div>
          : <p className="bo-muted">{t('noFlags')}</p>}
      </SheetSection>
      <SheetSection title={t('checksTitle')}>
        {d.checks.length ? <div className="nl-sl-tags">{d.checks.map(c => { const tag = checkTag(c, d.asOf, t); return <Tag key={c.id} tone={tag.tone}>{tag.text}</Tag>; })}</div> : <p className="bo-muted">{t('noChecksOnFile')}</p>}
      </SheetSection>
      {recent.length ? (
        <SheetSection title={t('sheetRecent')}>
          <ol className="nl-sl-timeline">{recent.map(e => <li key={e.id}><span>{fmt.date(e.at)}</span><span>{trailText(e, t)}</span></li>)}</ol>
        </SheetSection>
      ) : null}
    </>;
  }
  return <Drawer open onClose={onClose} title={s?.name ?? t('entity')} footer={footer}>{body}</Drawer>;
}
