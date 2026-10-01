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

type NewKind = ListingKind | 'bundle';

/**
 * "Listing type" (design: showTypePicker) — Product · Service · Bundle for businesses that sell both; sellers choose
 * between a product and a bundle (S-65: a bundle is goods, so a seller-only business needs it too).
 */
function TypePicker({ value, onChange, kinds }: { value: NewKind; onChange: (k: NewKind) => void; kinds: NewKind[] }) {
  const t = useCatalogueT();
  const label: Record<NewKind, string> = { product: t('typeProduct'), service: t('typeService'), bundle: t('typeBundle') };
  return (
    <Field label={t('listingType')} hint={value === 'bundle' ? t('bundleHint') : undefined}>
      <div>
        <Segmented<NewKind> name="listing-type" aria-label={t('listingType')} value={value} onChange={onChange}
          options={kinds.map(k => ({ value: k, label: label[k] }))} />
      </div>
    </Field>
  );
}

/** /b/$merchantId/listings/new — product editor for sellers, service editor for providers, a type picker for both. */
export function NewListingScreen({ kind: requested }: { kind?: NewKind }) {
  const merchant = useMerchant();
  const portal = portalOf(merchant.type);
  const [kind, setKind] = useState<NewKind>(portal === 'provider' ? 'service' : portal === 'seller' && requested !== 'bundle' ? 'product' : requested ?? 'product');
  const kinds: NewKind[] = portal === 'both' ? ['product', 'service', 'bundle'] : portal === 'seller' ? ['product', 'bundle'] : [];
  const picker = kinds.length ? <TypePicker value={kind} onChange={setKind} kinds={kinds} /> : undefined;
  return kind === 'service'
    ? <ServiceEditor key="service" portal={portal} typePicker={picker} />
    : <ProductEditor key={kind} portal={portal} typePicker={picker} type={kind} />;
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
