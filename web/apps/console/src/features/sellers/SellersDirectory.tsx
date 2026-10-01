import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearch } from '@tanstack/react-router';
import { Chip, DataTable, ErrorState, formatNumber, PageSkeleton, useLocale, type DataTableColumn, type DataTableTone } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { PlaceSelect } from '../orders/PlaceSelect';
import { useRegions } from '../shell/api';
import { useGrant } from '../shell/grant';
import { sellersQuery, type Directory, type Seller } from './api';
import { compactMoney, flagsText, percent, regionName } from './format';
import { useSellersT, type SellersKey } from './messages';
import { OversightDialog } from './OversightDialog';
import './sellers.css';

export interface SellersSearch { q?: string; province?: string; market?: string; risk?: boolean }

const TIER_TONE: Record<string, DataTableTone> = { master: 'tag-accent', trusted: 'tag-accent-2', registered: 'tag-neutral' };
const STATUS_TONE: Record<string, DataTableTone> = { active: 'tag-accent', paused: 'tag-highlight', suspended: 'tag-accent-2', pending: 'tag-neutral', applicant: 'tag-neutral' };

interface Row { id: string; name: string; cat: string; type: string; tier: string; q: number | null; gmv: string; disp: string; flags: string; status: string; src: Seller }

/**
 * Sellers & providers (S-82, design 03 `sellers`): every business that applied — tier, quality, 90-day GMV, dispute
 * rate, what puts it at risk and its status — searchable, by province and market, with Suspend / Reinstate (the
 * `suspend` action, a reason the business sees). Admin, trust & safety and support open it.
 */
export function SellersDirectory() {
  const t = useSellersT();
  const search = useSearch({ strict: false }) as SellersSearch;
  const navigate = useNavigate();
  const filter = { q: search.q, province: search.province, market: search.market };
  const query = useQuery(sellersQuery(filter));
  const go = (next: Partial<SellersSearch>) => void navigate({ to: '/sellers', search: { ...search, ...next } as never });
  const top = <div className="nl-sl-top"><span className="nl-sl-kicker">{t('kicker')}</span><PlaceSelect filter={filter} onChange={p => go(p)} /></div>;
  if (query.isPending) return <PageSkeleton kpis={0} rows={8} />;
  if (query.isError && !query.data) {
    return <div>{top}{query.error instanceof ValidationError ? <p role="alert" className="nl-sl-error">{t('badFilter')}</p> : <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />}</div>;
  }
  return <DirectoryView data={query.data!} top={top} search={search} onSearch={go} />;
}

function DirectoryView({ data, top, search, onSearch }: { data: Directory; top: React.ReactNode; search: SellersSearch; onSearch: (next: Partial<SellersSearch>) => void }) {
  const t = useSellersT();
  const { locale } = useLocale();
  const regions = useRegions(locale).data;
  const navigate = useNavigate();
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
  return (
    <div>
      {top}
      <h1 className="nl-sl-title">{t('title', { active: formatNumber(data.active, locale), atRisk: formatNumber(data.atRisk, locale) })}</h1>
      <div className="nl-sl-bar">
        <Chip selected={!!search.risk} onClick={() => onSearch({ risk: search.risk ? undefined : true })}>{t('atRiskChip')}</Chip>
        <form role="search" className="nl-sl-search" onSubmit={e => { e.preventDefault(); onSearch({ q: q.trim() || undefined }); }}>
          <input className="input" type="search" aria-label={t('search')} placeholder={t('search')} value={q} onChange={e => setQ(e.target.value)} />
        </form>
      </div>
      <DataTable<Row> entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} pageSize={10} roleName={roleName}
        rowTones={r => ({ tier: TIER_TONE[r.src.tier ?? ''] ?? 'tag-neutral', status: STATUS_TONE[r.src.status ?? ''] ?? 'tag-neutral' })}
        can={{ create: false, update: can('suspend'), delete: false }} emptyText={t('empty')} openLabel={t('open')} reportName="sellers"
        onOpen={r => void navigate({ to: '/sellers/$sellerId', params: { sellerId: r.id } })}
        actions={[
          { id: 'suspend', label: t('suspend'), perm: 'update', inline: true, bulk: false, when: { key: 'status', in: suspendable } },
          { id: 'reinstate', label: t('reinstate'), perm: 'update', inline: true, bulk: false, when: { key: 'status', in: [t('st_suspended')] } },
        ]}
        onAction={(a, picked) => { const s = picked[0]?.src; if (s) setActing({ seller: s, action: a.id as 'suspend' | 'reinstate' }); return false; }} />
      {data.truncated ? <p className="nl-sl-note">{t('truncated', { n: formatNumber(data.items.length, locale) })}</p> : null}
      {acting ? <OversightDialog seller={acting.seller} action={acting.action} checks={[]} onClose={() => setActing(null)} /> : null}
    </div>
  );
}
