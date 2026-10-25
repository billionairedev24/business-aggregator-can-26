import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { Eye, EyeSlash } from '@phosphor-icons/react';
import { Button, DataTable, useFormatters, type DataTableAction, type DataTableColumn, type DataTableQuickFilter, type DataTableView, type StatusTone, BoHeader } from '@northline/ui';
import { useMerchant, useMerchantId, useRole } from '../shell/api';
import { useShellT } from '../shell/messages';
import { listingsQuery, useListingActions, type ListingItem } from './api';
import { useCatalogueT } from './messages';
import { LOW_STOCK, fees, minutesSince, permissions, portalOf, vetState, type VetState } from './model';
import './catalogue.css';
import { InsightCard } from '../assistant/InsightCard';

interface Row {
  id: string; name: string; image: string; sku: string; meta: string; type: string; price: number | null; net: number | null; quote: boolean; stock: number | null; sales: number;
  vet: string; vetCode: VetState; live: string; liveCode: 'live' | 'hidden' | 'draft'; kind: ListingItem['kind'];
}

/** S-175: vetting and visibility as statuses: approved/live success, waiting warning, rejected danger. */
const VET_TONE: Record<VetState, StatusTone> = { approved: 'success', pending: 'warning', review: 'warning', rejected: 'danger', draft: 'neutral' };
const LIVE_TONE: Record<Row['liveCode'], StatusTone> = { live: 'success', hidden: 'neutral', draft: 'neutral' };
export const stockTone = (n: number | null): StatusTone => (n == null ? 'neutral' : n <= 0 ? 'danger' : n <= LOW_STOCK ? 'warning' : 'neutral');
const lowStock = (r: Row) => r.kind === 'product' && r.stock != null && r.stock <= LOW_STOCK;

/**
 * /b/$merchantId/listings — the catalogue table (design/revamp/a.html § 8, the orders table's pattern): status tabs with
 * counts (live, hidden, drafts, in review, low stock), quick filters, bulk publish / hide, statuses in their colours;
 * sellers see products, providers services with what they get after {brand}'s fee, both everything with a Type column.
 */
export function ListingsScreen({ search, view, onViewChange }: { search?: string; view?: string; onViewChange?: (v: string) => void } = {}) {
  const t = useCatalogueT();
  const shellT = useShellT();
  const merchantId = useMerchantId();
  const merchant = useMerchant();
  const role = useRole();
  const portal = portalOf(merchant.type);
  const navigate = useNavigate();
  const q = useQuery(listingsQuery(merchantId));
  const { visibility, remove } = useListingActions(merchantId);
  const can = permissions(role);
  const { price, number } = useFormatters();

  const labels = { live: t('statusLive'), hidden: t('statusHidden'), draft: t('statusDraft') };
  const rows = useMemo<Row[]>(() => (q.data ?? [])
    .filter(l => (portal === 'seller' ? l.kind === 'product' : portal === 'provider' ? l.kind === 'service' : true))
    .map((l: ListingItem) => {
      const state = vetState(l);
      const liveCode = l.vetting === 'draft' ? 'draft' : l.status;
      return {
        id: l.id, name: l.name, image: l.imageUrl ?? '', sku: l.sku ?? '', meta: l.kind === 'service' ? l.meta : l.sku ?? '', net: l.kind === 'service' && l.priceCents != null && l.pricingMode !== 'quote' ? fees(l.priceCents, null, merchant.tier).net : null, type: l.kind === 'service' ? t('typeService') : t('typeProduct'),
        price: l.priceCents ?? null, quote: l.pricingMode === 'quote', stock: l.kind === 'product' ? l.stock ?? null : null, sales: l.sales30d, kind: l.kind,
        vet: state === 'pending' ? t('vetPending', { min: minutesSince(l.submittedAt) }) : state === 'review' ? t('vetReview') : state === 'approved' ? t('vetApproved') : state === 'rejected' ? t('vetRejected') : t('vetDraft'),
        vetCode: state, live: labels[liveCode], liveCode,
      };
    }), [q.data, portal, t, merchant.tier]); // eslint-disable-line react-hooks/exhaustive-deps

  const columns: DataTableColumn<Row>[] = [
    { key: 'name', label: t(`col_${portal}`), thumb: 'image', sub: portal === 'seller' ? 'sku' : 'meta', subLabel: portal === 'seller' ? t('sku') : t('colDetails'), primary: true },
    ...(portal === 'both' ? [{ key: 'type', label: t('colType'), type: 'tag', options: [t('typeService'), t('typeProduct')], tones: { [t('typeService')]: 'tag-neutral', [t('typeProduct')]: 'tag-neutral' }, card: 'meta' } as DataTableColumn<Row>] : []),
    { key: 'price', label: t('colPrice'), type: 'money', format: (v, r) => (r.quote ? t('quote') : typeof v === 'number' ? price(v) : '—'), card: 'end' },
    ...(portal !== 'seller' ? [{ key: 'net', label: t('colNet'), type: 'money', editable: false, format: (v: unknown) => (typeof v === 'number' ? price(v) : '—'), card: 'line', cardFormat: (v: unknown) => t('youGet', { amount: price(v as number) }) } as DataTableColumn<Row>] : []),
    ...(portal !== 'provider' ? [{ key: 'stock', label: t('colStock'), type: 'num', card: 'line', status: (r: Row) => stockTone(r.stock),
      format: (v: unknown) => (typeof v !== 'number' ? '—' : v <= 0 ? t('outOfStock') : t('inStock', { n: number(v) })) } as DataTableColumn<Row>] : []),
    { key: 'sales', label: t(portal === 'provider' ? 'colBookings' : 'colSales'), type: 'num', editable: false },
    { key: 'vet', label: t('colVetting'), type: 'tag', editable: false, status: r => VET_TONE[r.vetCode], card: 'meta' },
    { key: 'live', label: t('colStatus'), type: 'tag', options: [labels.live, labels.hidden, labels.draft], status: r => LIVE_TONE[r.liveCode], card: 'tag' },
  ];
  const views: DataTableView<Row>[] = [
    { id: 'all', label: t('viewAll') },
    { id: 'live', label: labels.live, when: r => r.liveCode === 'live' },
    { id: 'hidden', label: labels.hidden, when: r => r.liveCode === 'hidden' },
    { id: 'draft', label: t('viewDrafts'), when: r => r.vetCode === 'draft' },
    { id: 'review', label: t('vetReview'), when: r => r.vetCode === 'pending' || r.vetCode === 'review' },
    { id: 'rejected', label: t('vetRejected'), when: r => r.vetCode === 'rejected', tone: 'danger', hideEmpty: true },
    ...(portal !== 'provider' ? [{ id: 'stock', label: t('viewStock'), when: lowStock, tone: 'danger' } as DataTableView<Row>] : []),
  ];
  const quick: DataTableQuickFilter<Row>[] = [
    { id: 'no-photo', label: t('quickNoPhoto'), when: r => !r.image },
    { id: 'not-selling', label: t(portal === 'provider' ? 'quickNoBookings' : 'quickNoSales'), when: r => r.sales === 0 },
  ];

  const actions: DataTableAction<Row>[] = [
    { id: 'pub', label: t('publish'), icon: Eye, perm: 'update', inline: true, emphasis: 'secondary', when: { key: 'live', in: [labels.hidden] } },
    { id: 'hide', label: t('hide'), icon: EyeSlash, perm: 'update', inline: true, when: { key: 'live', in: [labels.live] } },
  ];
  const open = (r: Row) => void navigate({ to: '/b/$merchantId/listings/$listingId', params: { merchantId, listingId: r.id } });

  return (
    <>
      <BoHeader title={t(`title_${portal}`)} sub={t(`sub_${portal}`)}
        actions={<Button variant="secondary" onClick={() => void navigate({ to: '/b/$merchantId/listings/bulk', params: { merchantId } })}>{t(`bulk_${portal}`)}</Button>} />
      <DataTable<Row>
        initialSearch={search}
        entity={t(`entity_${portal}`)} plural={t(`plural_${portal}`)} columns={columns} rows={rows}
        views={views} view={view} onViewChange={onViewChange} quickFilters={quick}
        can={{ create: can.create, update: can.update, delete: can.delete, export: true }}
        roleName={shellT(`role_${role}` as Parameters<typeof shellT>[0])}
        actions={actions} createLabel={t(`new_${portal}`)} searchPlaceholder={t('search')} emptyText={t('empty')}
        loading={q.isPending} error={q.isError ? t('loadError') : null} onRetry={() => void q.refetch()}
        onOpen={open} onEditClick={open}
        onCreateClick={() => void navigate({ to: '/b/$merchantId/listings/new', params: { merchantId } })}
        onAction={async (action, hit) => { await Promise.all(hit.map(r => visibility.mutateAsync({ id: r.id, action: action.id === 'pub' ? 'publish' : 'hide' }))); }}
        onDelete={async del => { await Promise.all(del.map(r => remove.mutateAsync(r.id))); }}
      />
      <InsightCard merchantId={merchantId} screen="listings" />
    </>
  );
}
