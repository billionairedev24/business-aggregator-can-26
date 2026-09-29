import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { Eye, EyeSlash } from '@phosphor-icons/react';
import { Button, DataTable, PageHeader, useFormatters, type DataTableAction, type DataTableColumn, type DataTableTone } from '@northline/ui';
import { useMerchant, useMerchantId, useRole } from '../shell/api';
import { useShellT } from '../shell/messages';
import { listingsQuery, useListingActions, type ListingItem } from './api';
import { useCatalogueT } from './messages';
import { minutesSince, permissions, portalOf, vetState } from './model';
import './catalogue.css';

interface Row {
  id: string; name: string; sku: string; type: string; price: number | null; quote: boolean; stock: number | null; sales: number;
  vet: string; vetTone: DataTableTone; live: string; liveCode: 'live' | 'hidden' | 'draft';
}

/**
 * /b/$merchantId/listings — design: products (portal-aware). Sellers see products ("Products & variants"), providers
 * services ("Services & prices"), both see everything with a Type column. Inline Publish / Hide, role-gated CRUD.
 */
export function ListingsScreen() {
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
  const { money } = useFormatters();

  const labels = { live: t('statusLive'), hidden: t('statusHidden'), draft: t('statusDraft') };
  const rows = useMemo<Row[]>(() => (q.data ?? [])
    .filter(l => (portal === 'seller' ? l.kind === 'product' : portal === 'provider' ? l.kind === 'service' : true))
    .map((l: ListingItem) => {
      const state = vetState(l);
      const liveCode = l.vetting === 'draft' ? 'draft' : l.status;
      return {
        id: l.id, name: l.name, sku: l.sku ?? '', type: l.kind === 'service' ? t('typeService') : t('typeProduct'),
        price: l.priceCents ?? null, quote: l.pricingMode === 'quote', stock: l.stock ?? null, sales: l.sales30d,
        vet: state === 'pending' ? t('vetPending', { min: minutesSince(l.submittedAt) }) : state === 'review' ? t('vetReview') : state === 'approved' ? t('vetApproved') : state === 'rejected' ? t('vetRejected') : t('vetDraft'),
        vetTone: state === 'approved' ? 'tag-accent' : state === 'draft' ? 'tag-neutral' : 'tag-accent-2',
        live: labels[liveCode], liveCode,
      };
    }), [q.data, portal, t]); // eslint-disable-line react-hooks/exhaustive-deps

  const columns: DataTableColumn<Row>[] = [
    { key: 'name', label: t(`col_${portal}`), sub: 'sku', subLabel: t('sku'), primary: true },
    ...(portal === 'both' ? [{ key: 'type', label: t('colType'), type: 'tag', options: [t('typeService'), t('typeProduct')], tones: { [t('typeService')]: 'tag-neutral', [t('typeProduct')]: 'tag-neutral' } } as DataTableColumn<Row>] : []),
    { key: 'price', label: t('colPrice'), type: 'money', format: (v, r) => (r.quote ? t('quote') : typeof v === 'number' ? money(v) : '—') },
    { key: 'stock', label: t('colStock'), type: 'num' },
    { key: 'sales', label: t('colSales'), type: 'num', editable: false },
    { key: 'vet', label: t('colVetting'), type: 'tag', editable: false },
    { key: 'live', label: t('colStatus'), type: 'tag', options: [labels.live, labels.hidden, labels.draft], tones: { [labels.live]: 'tag-accent', [labels.hidden]: 'tag-neutral', [labels.draft]: 'tag-neutral' } },
  ];

  const actions: DataTableAction<Row>[] = [
    { id: 'pub', label: t('publish'), icon: Eye, perm: 'update', inline: true, when: { key: 'live', in: [labels.hidden] } },
    { id: 'hide', label: t('hide'), icon: EyeSlash, perm: 'update', inline: true, when: { key: 'live', in: [labels.live] } },
  ];
  const open = (r: Row) => void navigate({ to: '/b/$merchantId/listings/$listingId', params: { merchantId, listingId: r.id } });
  const kicker = t(`kicker_${portal}`);

  return (
    <>
      <PageHeader kicker={kicker} title={t(`title_${portal}`)}
        actions={<Button variant="secondary" onClick={() => void navigate({ to: '/b/$merchantId/listings/bulk', params: { merchantId } })}>{t(`bulk_${portal}`)}</Button>} />
      <DataTable<Row>
        entity={t(`entity_${portal}`)} plural={t(`plural_${portal}`)} columns={columns} rows={rows}
        rowTones={r => ({ vet: r.vetTone })}
        can={{ create: can.create, update: can.update, delete: can.delete, export: true }}
        roleName={shellT(`role_${role}` as Parameters<typeof shellT>[0])}
        actions={actions} createLabel={t(`new_${portal}`)} searchPlaceholder={t('search')} emptyText={t('empty')}
        loading={q.isPending} error={q.isError ? t('loadError') : null} onRetry={() => void q.refetch()}
        onOpen={open} onEditClick={open}
        onCreateClick={() => void navigate({ to: '/b/$merchantId/listings/new', params: { merchantId } })}
        onAction={async (action, hit) => { await Promise.all(hit.map(r => visibility.mutateAsync({ id: r.id, action: action.id === 'pub' ? 'publish' : 'hide' }))); }}
        onDelete={async del => { await Promise.all(del.map(r => remove.mutateAsync(r.id))); }}
      />
    </>
  );
}
