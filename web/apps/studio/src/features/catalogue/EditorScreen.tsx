import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ErrorState, Field, PageSkeleton, Segmented } from '@northline/ui';
import { useMerchant, useMerchantId } from '../shell/api';
import { listingQuery, type ListingKind } from './api';
import { useCatalogueT } from './messages';
import { portalOf } from './model';
import { ProductEditor } from './ProductEditor';
import { ServiceEditor } from './ServiceEditor';
import './catalogue.css';

/**
 * "Listing type" (design: showTypePicker) — only for businesses that sell both. Bundle is shown but not yet
 * available (DECISIONS.md › Catalogue).
 */
function TypePicker({ value, onChange }: { value: ListingKind; onChange: (k: ListingKind) => void }) {
  const t = useCatalogueT();
  return (
    <Field label={t('listingType')} hint={t('bundleSoon')}>
      <div>
        <Segmented<ListingKind | 'bundle'> name="listing-type" aria-label={t('listingType')} value={value}
          onChange={v => { if (v !== 'bundle') onChange(v); }}
          options={[{ value: 'product', label: t('typeProduct') }, { value: 'service', label: t('typeService') }, { value: 'bundle', label: <span aria-disabled="true" style={{ opacity: 0.5 }}>{t('typeBundle')}</span> }]} />
      </div>
    </Field>
  );
}

/** /b/$merchantId/listings/new — product editor for sellers, service editor for providers, a type picker for both. */
export function NewListingScreen({ kind: requested }: { kind?: ListingKind }) {
  const merchant = useMerchant();
  const portal = portalOf(merchant.type);
  const [kind, setKind] = useState<ListingKind>(portal === 'seller' ? 'product' : portal === 'provider' ? 'service' : requested ?? 'product');
  const picker = portal === 'both' ? <TypePicker value={kind} onChange={setKind} /> : undefined;
  return kind === 'service'
    ? <ServiceEditor key="service" portal={portal} typePicker={picker} />
    : <ProductEditor key="product" portal={portal} typePicker={picker} />;
}

/** /b/$merchantId/listings/$listingId — loads the listing and opens the matching editor. */
export function EditListingScreen({ listingId }: { listingId: string }) {
  const t = useCatalogueT();
  const merchantId = useMerchantId();
  const merchant = useMerchant();
  const portal = portalOf(merchant.type);
  const q = useQuery(listingQuery(merchantId, listingId));
  if (q.isPending) return <PageSkeleton kpis={0} rows={6} />;
  if (q.isError) return <ErrorState message={t('listingLoadError')} onRetry={() => void q.refetch()} />;
  const d = q.data;
  return d.kind === 'service'
    ? <ServiceEditor key={d.id} detail={d} portal={portal} />
    : <ProductEditor key={d.id} detail={d} portal={portal} />;
}
